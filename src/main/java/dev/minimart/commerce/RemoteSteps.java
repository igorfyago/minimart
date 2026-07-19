package dev.minimart.commerce;

import dev.minimart.core.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * THE REMOTE STEP JOURNAL · what the outbox does for events, this does for calls.
 *
 * Outbox.append() joins the caller's transaction, because an event and the fact
 * it announces must commit together. This does the opposite on purpose: every
 * write here is its OWN commit, and begin() commits BEFORE the request leaves
 * the process.
 *
 * That inversion is the whole design. A journal row inside the caller's
 * transaction would roll back with it, and the row that matters most is
 * precisely the one written by a call whose transaction never committed. What
 * is being recorded is not "this happened", it is "we asked, and here is what we
 * know about the answer" · which has to survive the asking failing.
 *
 * FOUR STATES, AND THE FOURTH IS THE INTERESTING ONE:
 *
 *   in_flight  the request left, no answer yet. A row still in_flight after the
 *              process restarts means a step whose outcome nobody ever learned.
 *   ok         the far side said yes.
 *   failed     the far side said no, or was never reached at all. Nothing is
 *              standing over there and nothing needs undoing.
 *   unknown    we did not get an answer, and THE REQUEST MAY HAVE LANDED. A read
 *              timeout on an authorisation is this: minipay may be holding a
 *              real customer's money against an order minimart has abandoned.
 *              Treating that as 'failed' is the defect this state exists to end.
 *
 * Nothing here heals anything. It records, so that Reconciler can name what is
 * wrong and a human can decide. A journal that also moved money would be a
 * second, less careful checkout.
 */
public final class RemoteSteps {

    /** What is known about a step that crossed the network. */
    public enum State { IN_FLIGHT, OK, FAILED, UNKNOWN;
        String wire() { return name().toLowerCase(); }
    }

    public static final String AUTHORIZE = "authorize";
    public static final String CAPTURE   = "capture";
    public static final String CANCEL    = "cancel";
    /** The LOCAL half of ship(). It is journalled with the remote steps because
     *  it is only dangerous in their company: a fulfil that fails on its own
     *  costs nothing, and a fulfil that fails after a capture has already taken
     *  the customer's money is the customer paying for goods still on the shelf. */
    public static final String FULFIL    = "fulfil";

    public record Step(UUID orderId, String action, String intentId,
                       State state, String detail, Instant businessAt) {}

    private RemoteSteps() {}

    /**
     * Record that a step is about to be attempted, and commit that before
     * attempting it.
     *
     * A RETRY DELIBERATELY RESETS THE ROW to in_flight rather than being ignored.
     * The row answers "what do we know right now", not "what happened once", and
     * a second attempt genuinely does put the outcome back in question.
     */
    public static void begin(UUID orderId, String action, String intentId, Instant businessAt)
            throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO remote_steps(order_id, action, intent_id, state, detail, business_at)
                     VALUES (?,?,?,?,NULL,?)
                     ON CONFLICT (order_id, action) DO UPDATE
                       SET state = EXCLUDED.state, detail = NULL,
                           business_at = EXCLUDED.business_at, updated_at = now()""")) {
            ps.setObject(1, orderId);
            ps.setString(2, action);
            ps.setString(3, intentId);
            ps.setString(4, State.IN_FLIGHT.wire());
            ps.setTimestamp(5, java.sql.Timestamp.from(businessAt));
            ps.executeUpdate();
        }
    }

    /**
     * Record what came back. Called on every path out of the attempt, including
     * the ones that throw, which is why the callers use finally-shaped code.
     *
     * THIS NEVER THROWS, and that asymmetry with begin() is the whole design.
     *
     * begin() runs BEFORE the request leaves, so if it cannot write, the right
     * answer is to abandon the attempt: nothing has moved yet, and an
     * unrecorded call is exactly what this table exists to prevent. It throws.
     *
     * finish() runs AFTER. By then the remote outcome is a fact in someone
     * else's database, and this row is only our note about it. Letting the note
     * throw put the failure in the WRONG PLACE twice over. In the success path
     * a captured payment whose journal write failed came back to the caller as
     * "the capture failed", so the shop believed the money had not moved when
     * it had. In the failure path the write threw a fresh SQLException over the
     * original one, discarding the diagnosis at the exact moment it mattered,
     * and a dead database is the likeliest reason the local step failed at all.
     *
     * So a lost note degrades this table, and the reconciler is what covers
     * that: it compares the two services and does not consult this journal to
     * do it. A lost note must never degrade the answer the caller acts on.
     *
     * @return true when the note landed, for callers that want to know
     */
    public static boolean finish(UUID orderId, String action, State state, String detail) {
        try {
            write(orderId, action, state, detail);
            return true;
        } catch (SQLException e) {
            // Nowhere to escalate: raising it here is precisely the bug above.
            // Visible on stderr, and the reconciler still catches the money.
            System.err.println("remote_steps: could not record " + action + " for order "
                    + orderId + " as " + state.wire() + ": " + e.getMessage());
            return false;
        }
    }

    private static void write(UUID orderId, String action, State state, String detail)
            throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE remote_steps SET state = ?, detail = ?, updated_at = now() " +
                     "WHERE order_id = ? AND action = ?")) {
            ps.setString(1, state.wire());
            ps.setString(2, detail);
            ps.setObject(3, orderId);
            ps.setString(4, action);
            ps.executeUpdate();
        }
    }

    /**
     * TAKE THIS STEP IN HAND, OR REFUSE. The driver's only way in.
     *
     * Three refusals in one statement, and they are three different failures:
     *
     *   state = 'ok'          somebody already settled it. Nothing to do.
     *   attempts >= ceiling   the driver has tried this enough times. Stopping is
     *                         the feature: an unbounded retry is an outage that
     *                         never gets reported, and noteExhaustion() is what
     *                         turns the stopping into something an operator sees.
     *   claimed_at is live    another driver holds it. Two copies of this must not
     *                         both call minipay about one hold.
     *
     * It is a CONDITIONAL UPDATE and not a read followed by a write, which is
     * the whole point. Read-then-write lets two drivers both see attempts = 0 and
     * both go, and the mechanism that is supposed to make double-acting
     * impossible becomes a mechanism that makes it likely under exactly the load
     * where it matters. The same argument the outbox makes about publishing an
     * event once, one table over.
     *
     * The claim EXPIRES rather than being held, because a driver that dies
     * holding a lock strands the step for good, and a stranded step is what this
     * table was built to end. The lease is long enough that no live attempt is
     * ever overtaken and short enough that a crash costs one lease, not a shift.
     *
     * A step the driver has never seen is INSERTED here, not required to exist.
     * The most common repair is a void for an order that never got as far as
     * asking for one, and demanding a row first would mean the driver could only
     * finish work somebody else had already started.
     *
     * @return true when THIS caller holds the step and may act
     */
    public static boolean claim(UUID orderId, String action, String intentId,
                                Instant businessAt, int maxAttempts, Duration lease)
            throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO remote_steps(order_id, action, intent_id, state, detail,
                                              business_at, attempts, claimed_at)
                     VALUES (?,?,?,?,'claimed by the driver',?,1,now())
                     ON CONFLICT (order_id, action) DO UPDATE
                       SET attempts = remote_steps.attempts + 1,
                           claimed_at = now(), updated_at = now()
                       WHERE remote_steps.state <> 'ok'
                         AND remote_steps.attempts < ?
                         AND (remote_steps.claimed_at IS NULL
                              OR remote_steps.claimed_at < now() - make_interval(secs => ?))""")) {
            ps.setObject(1, orderId);
            ps.setString(2, action);
            ps.setString(3, intentId);
            ps.setString(4, State.IN_FLIGHT.wire());
            ps.setTimestamp(5, java.sql.Timestamp.from(businessAt));
            ps.setInt(6, maxAttempts);
            ps.setDouble(7, lease.toSeconds());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Let go of the step, leaving the attempt counted.
     *
     * THE LEASE COVERS THE ATTEMPT, NOT THE STEP. Holding it afterwards would
     * mean a step gets one try per lease rather than one try per pass, so a
     * ceiling of five attempts would silently become five attempts spread over
     * half an hour and the repair nobody is waiting on would be the slowest
     * thing in the estate. What must not overlap is two drivers CALLING minipay
     * about one hold, and that is over the moment the call is.
     *
     * The expiry stays for the case it was built for: a driver that dies mid
     * attempt never reaches this line, and its claim rots off on its own rather
     * than stranding the step for good.
     */
    public static void releaseClaim(UUID orderId, String action) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE remote_steps SET claimed_at = NULL WHERE order_id = ? AND action = ?")) {
            ps.setObject(1, orderId);
            ps.setString(2, action);
            ps.executeUpdate();
        }
    }

    /**
     * Say, on the row itself, that the driver has stopped trying.
     *
     * A ceiling with nothing at the top of it is just a quieter silence. The
     * note goes in detail because detail is already what Reconciler prints
     * beside the discrepancy, so a step the driver gave up on becomes a phrase
     * in the report an operator reads at three in the morning without anything
     * new having to be built or subscribed to.
     *
     * It PREPENDS rather than replaces: what finish() wrote is the diagnosis and
     * this is only the news that nobody is coming back for it. Guarded on the
     * prefix so that running the driver again does not stutter the same sentence
     * across the row.
     *
     * @return true when this call is the one that wrote the note
     */
    public static boolean noteExhaustion(UUID orderId, String action, int maxAttempts)
            throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE remote_steps
                        SET detail = 'exhausted after ' || attempts || ' driver attempts, left for a human'
                                     || COALESCE(' · ' || detail, ''),
                            updated_at = now()
                      WHERE order_id = ? AND action = ? AND state <> 'ok'
                        AND attempts >= ? AND COALESCE(detail, '') NOT LIKE 'exhausted after %'""")) {
            ps.setObject(1, orderId);
            ps.setString(2, action);
            ps.setInt(3, maxAttempts);
            return ps.executeUpdate() == 1;
        }
    }

    /** Steps the driver has given up on. Work that is now definitely a person's. */
    public static List<Step> exhausted(Connection c, int maxAttempts) throws SQLException {
        List<Step> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT order_id, action, intent_id, state, detail, business_at FROM remote_steps " +
                "WHERE state <> 'ok' AND attempts >= ? ORDER BY updated_at")) {
            ps.setInt(1, maxAttempts);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(read(rs)); }
        }
        return out;
    }

    /** How many times the driver has taken this step in hand. */
    public static int attempts(Connection c, UUID orderId, String action) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT attempts FROM remote_steps WHERE order_id = ? AND action = ?")) {
            ps.setObject(1, orderId);
            ps.setString(2, action);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    /** Everything that did not finish cleanly. The operator's work queue. */
    public static List<Step> unsettled(Connection c) throws SQLException {
        return query(c, "SELECT order_id, action, intent_id, state, detail, business_at " +
                        "FROM remote_steps WHERE state <> 'ok' ORDER BY updated_at", null);
    }

    public static List<Step> forOrder(Connection c, UUID orderId) throws SQLException {
        return query(c, "SELECT order_id, action, intent_id, state, detail, business_at " +
                        "FROM remote_steps WHERE order_id = ? ORDER BY action", orderId);
    }

    /** What is known about one step, or null if it was never attempted. */
    public static Step find(Connection c, UUID orderId, String action) throws SQLException {
        for (Step s : forOrder(c, orderId)) if (s.action().equals(action)) return s;
        return null;
    }

    private static List<Step> query(Connection c, String sql, UUID orderId) throws SQLException {
        List<Step> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (orderId != null) ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(read(rs)); }
        }
        return out;
    }

    /** One row, in the column order every query above selects. */
    private static Step read(ResultSet rs) throws SQLException {
        return new Step((UUID) rs.getObject(1), rs.getString(2), rs.getString(3),
                State.valueOf(rs.getString(4).toUpperCase()), rs.getString(5),
                rs.getTimestamp(6).toInstant());
    }
}
