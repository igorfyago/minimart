package dev.minianalytics;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * CAN THIS SERVICE PROVE IT SAW EVERYTHING?
 *
 * An eventually-consistent read model is only trustworthy if its gaps are
 * DETECTABLE. Lag is fine, everyone accepts lag. Silent loss is not, because a
 * dashboard missing 3% of its events looks exactly like a dashboard missing
 * none, and it will be believed either way.
 *
 * So completeness is a query, not a hope. The producer knows which event keys
 * it published, this service knows which it handled, and the difference is the
 * answer. Note what is NOT done here: analytics does not reach into minimart's
 * database to find out. The expected keys are handed in by a reconciliation
 * caller that is allowed to read both, which keeps the dependency in one place
 * that can be seen instead of buried in a service that claims to be independent.
 */
public final class Audit {

    public record Completeness(int expected, int handled, List<String> missing) {
        public boolean complete() { return missing.isEmpty(); }
        public double coverage() { return expected == 0 ? 1.0 : (double) handled / expected; }
    }

    private Audit() {}

    public static Completeness completeness(List<String> expectedEventKeys) throws SQLException {
        List<String> missing = new ArrayList<>();
        try (Connection c = AnalyticsDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM handled_events WHERE event_key = ? AND consumer = ?")) {
            for (String key : expectedEventKeys) {
                ps.setString(1, key);
                ps.setString(2, Projection.CONSUMER);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) missing.add(key);
                }
            }
        }
        return new Completeness(expectedEventKeys.size(), expectedEventKeys.size() - missing.size(), missing);
    }

    /**
     * DOES THE LEDGER STILL AGREE WITH THE PROJECTION?
     *
     * The movements are the source of truth and the projection is a convenience,
     * so any disagreement is a bug in the projection and never the other way
     * round. Running this is how you find out that a handler forgot to update
     * one of the two, which is otherwise invisible until someone quotes a number
     * in a meeting.
     */
    public static BigDecimal drift(String tenant) throws SQLException {
        return Projection.mrr(tenant).subtract(Projection.mrrFromState(tenant));
    }

    /** Rebuild the projection from the movement ledger alone. If this changes
     *  any number, the projection had drifted. It is also the disaster recovery
     *  story: the read model is disposable by construction. */
    public static int rebuildProjection() throws SQLException {
        try (Connection c = AnalyticsDb.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE subscription_state s
                       SET monthly_amount = COALESCE(m.total, 0),
                           status = CASE WHEN COALESCE(m.total, 0) > 0 THEN s.status ELSE 'canceled' END
                      FROM (SELECT subscription_id, SUM(amount) AS total
                              FROM mrr_movements GROUP BY subscription_id) m
                     WHERE m.subscription_id = s.subscription_id""")) {
                int n = ps.executeUpdate();
                c.commit();
                return n;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }
}
