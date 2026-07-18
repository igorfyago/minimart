package dev.minimart.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * THE OUTBOX · an event, written in the same commit as the thing it announces.
 *
 * append() deliberately takes the CALLER'S connection. That is the entire
 * point: it joins the caller's open transaction, so the event and the business
 * data commit together or roll back together. Passing a fresh connection here
 * would quietly reintroduce the dual write this table exists to abolish.
 */
public final class Outbox {

    public record Row(long id, String topic, String eventKey, String key, String payload, Instant businessAt) {}

    private Outbox() {}

    /** Join the caller's transaction. Never opens its own connection.
     *
     *  eventKey is the identity of the BUSINESS EVENT (`order.placed:<id>`),
     *  written by the producer and unique, so the same event cannot be
     *  announced twice. partitionKey is the ORDERING key Kafka partitions on.
     *  They are different questions and conflating them is how a cancellation
     *  overtakes its own placement. */
    public static void append(Connection c, String topic, String eventKey, String partitionKey,
                              String payload, Instant businessAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO outbox(topic, event_key, key, payload, business_at) VALUES (?,?,?,?,?) " +
                "ON CONFLICT (event_key) DO NOTHING")) {
            ps.setString(1, topic);
            ps.setString(2, eventKey);
            ps.setString(3, partitionKey);
            ps.setString(4, payload);
            ps.setTimestamp(5, java.sql.Timestamp.from(businessAt));
            ps.executeUpdate();
        }
    }

    public static List<Row> pending(Connection c, int limit) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, topic, event_key, key, payload, business_at FROM outbox " +
                "WHERE published_at IS NULL ORDER BY id LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()));
            }
        }
        return out;
    }

    /** Called ONLY after the broker has acknowledged. The ordering is the whole
     *  crash-safety argument: mark-after-send means a crash resends, and a
     *  resend is harmless because consumers dedupe. Mark-before-send would
     *  silently lose events, which nothing downstream could ever detect. */
    public static void markPublished(Connection c, long id, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox SET published_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(at));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** The consumer side of at-least-once: claim an event key, once, per consumer.
     *  Returns true if this delivery is the one that should do the work. */
    public static boolean recordHandled(String eventKey, String consumer, Instant businessAt)
            throws SQLException {
        try (Connection c = Db.open()) {
            return recordHandled(c, eventKey, consumer, businessAt);
        }
    }

    public static boolean recordHandled(Connection c, String eventKey, String consumer, Instant businessAt)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO handled_events(event_key, consumer, business_at) VALUES (?,?,?) " +
                "ON CONFLICT (event_key, consumer) DO NOTHING")) {
            ps.setString(1, eventKey);
            ps.setString(2, consumer);
            ps.setTimestamp(3, java.sql.Timestamp.from(businessAt));
            return ps.executeUpdate() == 1;
        }
    }
}
