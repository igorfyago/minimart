package dev.minimart.commerce;

import dev.minimart.core.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Step((UUID) rs.getObject(1), rs.getString(2), rs.getString(3),
                            State.valueOf(rs.getString(4).toUpperCase()), rs.getString(5),
                            rs.getTimestamp(6).toInstant()));
                }
            }
        }
        return out;
    }
}
