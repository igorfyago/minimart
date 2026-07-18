package dev.minimart.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * WHAT TO DO WHEN A HANDLER THROWS · deliberately with no broker in it.
 *
 * Kafka is a transport. Whether a failed event should be retried, buried or
 * ignored is a business decision, and tangling the two means the decision can
 * only be tested by standing up a broker and contriving a partition. Separating
 * them means the interesting logic is testable directly, and the transport
 * becomes the thin thing it should have been all along.
 *
 * Four rules, and each one is a position:
 *
 *   DEDUPE   · at-least-once is the only delivery guarantee worth having, since
 *              the alternative loses events silently. So every event is claimed
 *              in handled_events, keyed by (event, CONSUMER), before it takes
 *              effect. Keying by event alone would let whichever consumer got
 *              there first mark it done for everybody, and the others would
 *              silently skip work they never did.
 *   ISOLATE  · a handler that throws costs that event and nothing else.
 *   RETRY    · a failed event is copied into a LOCAL queue, so retries run on
 *              our schedule rather than the broker's, and the offset can advance
 *              past it immediately. A retry queue that depends on the broker
 *              still holding the message is not a retry queue.
 *   BURY     · after a bounded number of attempts it becomes a dead letter,
 *              kept WITH its payload and its last error, because by the time
 *              somebody looks, the retention window may have closed and an
 *              event that cannot be replayed from the dead letter cannot be
 *              replayed at all.
 *
 * What this deliberately does NOT preserve is strict per-key ordering across a
 * failure: if event 2 fails and event 3 succeeds, they were applied out of
 * order. That is a real cost, accepted only because every handler using this
 * runtime is idempotent and order-insensitive. A handler that genuinely needs
 * ordering must not use it, and that is a constraint worth stating rather than
 * discovering.
 */
public final class EventRuntime {

    /** Handle one event. Throwing means "not handled", and this runtime decides
     *  whether that becomes a retry or a burial. */
    public interface Handler {
        void handle(String eventKey, String payload, Instant businessAt) throws Exception;
    }

    public enum Result { HANDLED, DUPLICATE, RETRY, BURIED }

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final String topic;
    private final String group;
    private final Handler handler;
    private final int maxAttempts;

    public EventRuntime(String topic, String group, Handler handler) {
        this(topic, group, handler, DEFAULT_MAX_ATTEMPTS);
    }

    public EventRuntime(String topic, String group, Handler handler, int maxAttempts) {
        this.topic = topic;
        this.group = group;
        this.handler = handler;
        this.maxAttempts = maxAttempts;
    }

    public String group() { return group; }

    /** Apply one event, exactly once, deciding what a failure means. */
    public Result apply(String eventKey, String payload, Instant businessAt) throws SQLException {
        if (alreadyHandled(eventKey)) return Result.DUPLICATE;
        try {
            handler.handle(eventKey, payload, businessAt);
            Outbox.recordHandled(eventKey, group, businessAt);
            clearRetry(eventKey);
            return Result.HANDLED;
        } catch (Exception e) {
            return recordFailure(eventKey, payload, businessAt, e) ? Result.BURIED : Result.RETRY;
        }
    }

    /**
     * Retry everything waiting, once each. Returns how many finally succeeded.
     *
     * Called at a tick boundary rather than on a timer, for the same reason the
     * reservation sweeper is: a compressed run has to be able to watch a retry
     * ladder pass in milliseconds without the logic noticing.
     */
    public int retryPending(Instant businessAt) throws SQLException {
        record Waiting(String eventKey, String payload) {}
        List<Waiting> waiting = new ArrayList<>();
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event_key, payload FROM event_retries WHERE consumer = ? ORDER BY first_seen_at")) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) waiting.add(new Waiting(rs.getString(1), rs.getString(2)));
            }
        }
        int recovered = 0;
        for (Waiting w : waiting) {
            if (apply(w.eventKey(), w.payload(), businessAt) == Result.HANDLED) recovered++;
        }
        return recovered;
    }

    /**
     * Replay a dead letter after whatever killed it has been fixed. The event
     * goes back to the same handler exactly as it arrived, so this needs no
     * archaeology, and the handled_events claim still applies, so replaying
     * something that did in fact take effect is harmless.
     */
    public boolean replayDeadLetter(String eventKey, Instant businessAt) throws SQLException {
        String payload;
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payload FROM dead_letters WHERE event_key = ? AND consumer = ? AND replayed_at IS NULL")) {
            ps.setString(1, eventKey); ps.setString(2, group);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                payload = rs.getString(1);
            }
        }
        try {
            // The handler runs unconditionally, and deliberately so. Burial
            // CLAIMED this event in handled_events, to stop the runtime
            // offering it again, so checking that claim here would mean a
            // buried event could never be replayed: the very thing this method
            // exists to do. What prevents a double replay is the
            // replayed_at IS NULL above, which is the right guard because it
            // asks the question actually being asked.
            handler.handle(eventKey, payload, businessAt);
            Outbox.recordHandled(eventKey, group, businessAt);
            try (Connection c = Db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE dead_letters SET replayed_at = now() WHERE event_key = ? AND consumer = ?")) {
                ps.setString(1, eventKey); ps.setString(2, group);
                ps.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            // still broken. It stays buried, and the error is refreshed so
            // whoever looks next sees why THIS attempt failed, not the first one.
            try (Connection c = Db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE dead_letters SET last_error = ? WHERE event_key = ? AND consumer = ?")) {
                ps.setString(1, describe(e)); ps.setString(2, eventKey); ps.setString(3, group);
                ps.executeUpdate();
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ state

    private boolean alreadyHandled(String eventKey) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM handled_events WHERE event_key = ? AND consumer = ?")) {
            ps.setString(1, eventKey); ps.setString(2, group);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private void clearRetry(String eventKey) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM event_retries WHERE event_key = ? AND consumer = ?")) {
            ps.setString(1, eventKey); ps.setString(2, group);
            ps.executeUpdate();
        }
    }

    /** Record the failure. True if this attempt exhausted the budget and the
     *  event has been buried. */
    private boolean recordFailure(String eventKey, String payload, Instant businessAt, Exception e)
            throws SQLException {
        int attempts;
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO event_retries(event_key, consumer, topic, payload, attempts, last_error, business_at)
                        VALUES (?,?,?,?,1,?,?)
                        ON CONFLICT (event_key, consumer) DO UPDATE
                            SET attempts = event_retries.attempts + 1,
                                last_error = EXCLUDED.last_error,
                                last_try_at = now()
                        RETURNING attempts""")) {
                    ps.setString(1, eventKey); ps.setString(2, group); ps.setString(3, topic);
                    ps.setString(4, payload); ps.setString(5, describe(e));
                    ps.setTimestamp(6, java.sql.Timestamp.from(businessAt));
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); attempts = rs.getInt(1); }
                }
                if (attempts < maxAttempts) { c.commit(); return false; }

                // Out of attempts. Bury it, and claim it, so the runtime stops
                // offering it: buried is a terminal state a human resolves, not
                // one the system keeps grinding against.
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO dead_letters(event_key, consumer, topic, payload, attempts, last_error, business_at)
                        VALUES (?,?,?,?,?,?,?) ON CONFLICT (event_key, consumer) DO NOTHING""")) {
                    ps.setString(1, eventKey); ps.setString(2, group); ps.setString(3, topic);
                    ps.setString(4, payload); ps.setInt(5, attempts); ps.setString(6, describe(e));
                    ps.setTimestamp(7, java.sql.Timestamp.from(businessAt));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM event_retries WHERE event_key = ? AND consumer = ?")) {
                    ps.setString(1, eventKey); ps.setString(2, group);
                    ps.executeUpdate();
                }
                Outbox.recordHandled(c, eventKey, group, businessAt);
                c.commit();
                return true;
            } catch (SQLException | RuntimeException x) { c.rollback(); throw x; }
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    // ------------------------------------------------------------------ reads

    public static long deadLetterCount(String consumer) throws SQLException {
        return count("SELECT COUNT(*) FROM dead_letters WHERE consumer = ? AND replayed_at IS NULL", consumer);
    }

    public static long pendingRetryCount(String consumer) throws SQLException {
        return count("SELECT COUNT(*) FROM event_retries WHERE consumer = ?", consumer);
    }

    private static long count(String sql, String consumer) throws SQLException {
        try (Connection c = Db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, consumer);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }
}
