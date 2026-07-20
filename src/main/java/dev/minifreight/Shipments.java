package dev.minifreight;

import dev.minimart.core.Json;
import dev.minimart.core.Outbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * THE PARCEL'S STATE MACHINE, WITH ONE DOOR.
 *
 * Two independent sources report on a parcel: the carrier pushes webhooks, and
 * the driver polls when the webhooks go quiet. They race, they repeat
 * themselves, and they arrive out of order · a delivered can be followed by
 * the in_transit that was minted before it. The first design had the webhook
 * handler and the poller each writing state, and every defect that mattered
 * lived in the difference between them.
 *
 * So there is exactly one way a parcel moves: advance(). It does three things
 * in one transaction, in an order that is the whole argument:
 *
 *   RECORD  · the tracking event lands behind a (carrier, event_key) unique
 *             constraint, so a carrier that stutters is answered twice and
 *             heard once. Scoped per carrier, because the key is the carrier's
 *             own name for the event and two carriers may pick the same one.
 *   COMPARE · the shipment row is locked and the new status is ranked against
 *             the current one. A parcel only moves FORWARD. The late
 *             in_transit behind a delivered is kept as history and changes
 *             nothing, and it is acknowledged as handled, because an error
 *             answer would make the carrier retry a fact we already have.
 *   ANNOUNCE· the outbox row joins the same commit, keyed by order id so
 *             per-order ordering holds for whoever listens downstream.
 *
 * Both reporting paths mint the SAME event key for the same fact ·
 * tracking:status · so the poll that races its own webhook collapses into a
 * duplicate here instead of becoming a second announcement.
 */
public final class Shipments {

    /** Freight announces on its own topic. Publishing into minimart's order
     *  topic would make the order producer responsible for a schema it has
     *  never seen, and topic ownership is service ownership. */
    public static final String TOPIC_SHIPMENTS = "minifreight.shipments.v1";

    public enum Advance { APPLIED, DUPLICATE, STALE, NO_SUCH_SHIPMENT }

    /** Forward is the only direction. failed and stuck are deliberately not in
     *  this ladder: they are decisions freight makes about a shipment, not
     *  facts a carrier reports about a parcel, and they leave by other doors. */
    static int rank(String status) {
        return switch (status) {
            case "requested" -> 0;
            case "labelled" -> 1;
            case "in_transit" -> 2;
            case "delivered" -> 3;
            default -> -1;
        };
    }

    private Shipments() {}

    // ------------------------------------------------------------- creation

    /**
     * One order.fulfilled event becomes at most one shipment, forever.
     *
     * The unique constraint on order_id is the real gate; the ON CONFLICT
     * makes a redelivery a quiet no-op instead of an exception that would read
     * as a failure and send the event to a retry queue it can never leave.
     * The consumer's claim already deduplicates the same delivery; this
     * deduplicates the same ORDER arriving under a key we have not seen, which
     * a replayed backlog legitimately produces.
     *
     * The destination is AUTHORED: this estate has no address book, so the
     * parcel ships to the customer's locker id. Stated so nobody reads a
     * logistics network into a string we made up.
     */
    public static UUID createFromOrder(Connection c, String payload, Instant at) throws SQLException {
        String orderId = Json.str(payload, "orderId");
        String tenant = Json.str(payload, "tenant");
        String variant = Json.str(payload, "variant");
        String qty = Json.str(payload, "qty");
        String customer = Json.str(payload, "customer");
        if (orderId == null || tenant == null || variant == null) {
            throw new IllegalArgumentException("order.fulfilled is missing orderId, tenant or variant: " + payload);
        }

        UUID shipmentId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shipments(id, order_id, tenant, variant, qty, destination, state)
                VALUES (?,?,?,?,?,?, 'requested')
                ON CONFLICT (order_id) DO NOTHING""")) {
            ps.setObject(1, shipmentId);
            ps.setObject(2, UUID.fromString(orderId));
            ps.setString(3, tenant);
            ps.setString(4, variant);
            ps.setLong(5, qty == null ? 1 : Long.parseLong(qty));
            ps.setString(6, "locker:cust:" + (customer == null ? "unknown" : customer));
            if (ps.executeUpdate() == 0) return null;    // this order already ships
        }
        Outbox.append(c, TOPIC_SHIPMENTS, "shipment.requested:" + shipmentId, orderId,
                Json.obj("type", "shipment.requested", "eventKey", "shipment.requested:" + shipmentId,
                        "shipmentId", shipmentId.toString(), "orderId", orderId,
                        "tenant", tenant, "variant", variant, "at", at.toString()), at);
        return shipmentId;
    }

    // ------------------------------------------------------------ the door

    public static Advance advance(Connection c, UUID shipmentId, String carrier, String tracking,
                                  String status, String eventKey, Instant at) throws SQLException {
        if (rank(status) < 0) throw new IllegalArgumentException("not a parcel status: " + status);

        // RECORD · behind the per-carrier unique key
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO tracking_events(shipment_id, carrier, status, event_key)
                VALUES (?,?,?,?) ON CONFLICT (carrier, event_key) DO NOTHING""")) {
            ps.setObject(1, shipmentId);
            ps.setString(2, carrier);
            ps.setString(3, status);
            ps.setString(4, eventKey);
            if (ps.executeUpdate() == 0) return Advance.DUPLICATE;
        }

        // COMPARE · under the row lock, so the webhook and the poll serialise
        String orderId, current;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT order_id, state FROM shipments WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, shipmentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Advance.NO_SUCH_SHIPMENT;
                orderId = rs.getString(1);
                current = rs.getString(2);
            }
        }
        // A DECIDED SHIPMENT IS NOBODY'S TO REOPEN. failed and stuck rank -1,
        // which without this guard would make them rank BELOW every carrier
        // report and reopen through the front door · a signed webhook marching
        // a stuck shipment to delivered, past the human the stuck state exists
        // to summon. Found by adversarial review, not by a test, which is why
        // lesson 7 now closes with exactly this attempt.
        if (rank(current) < 0) return Advance.STALE;
        if (rank(status) <= rank(current)) return Advance.STALE;   // history, not news

        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE shipments SET state = ?, carrier = COALESCE(carrier, ?),
                       tracking_ref = COALESCE(tracking_ref, ?), updated_at = now()
                WHERE id = ?""")) {
            ps.setString(1, status);
            ps.setString(2, carrier);
            ps.setString(3, tracking);
            ps.setObject(4, shipmentId);
            ps.executeUpdate();
        }

        // ANNOUNCE · in the same commit, keyed by the order
        Outbox.append(c, TOPIC_SHIPMENTS, "shipment." + status + ":" + shipmentId, orderId,
                Json.obj("type", "shipment." + status, "eventKey", "shipment." + status + ":" + shipmentId,
                        "shipmentId", shipmentId.toString(), "orderId", orderId,
                        "carrier", carrier, "tracking", tracking == null ? "" : tracking,
                        "at", at.toString()), at);
        return Advance.APPLIED;
    }

    /** Which shipment is this carrier talking about? Webhooks name the parcel
     *  in the carrier's vocabulary, so the lookup is (carrier, tracking), and
     *  an answer of null means a webhook about a parcel we never labelled ·
     *  which, past the signature check, is a carrier bug worth a 404. */
    public static UUID byTracking(Connection c, String carrier, String tracking) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT shipment_id FROM carrier_steps WHERE carrier = ? AND state = 'accepted' AND detail = ?")) {
            ps.setString(1, carrier);
            ps.setString(2, tracking);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (UUID) rs.getObject(1) : null;
            }
        }
    }

    // -------------------------------------------------- freight's own doors

    /** Every carrier said no. Terminal, announced, and only from 'requested':
     *  a parcel that got a label is somewhere, and "somewhere" is never failed
     *  by a driver pass, only by a human who checked. */
    public static boolean fail(Connection c, UUID shipmentId, String reason, Instant at) throws SQLException {
        return terminal(c, shipmentId, "failed", reason, at);
    }

    /** The driver ran out of attempts without ever learning the truth. Not
     *  failed, because a label may exist; not retried forever, because an
     *  unbounded repair is an outage with better manners. A person finishes
     *  this one, through resolve(). */
    public static boolean stuck(Connection c, UUID shipmentId, String reason, Instant at) throws SQLException {
        return terminal(c, shipmentId, "stuck", reason, at);
    }

    /**
     * The repair path, designed on day one rather than discovered in an
     * incident: a human who has spoken to the carrier moves a stuck shipment
     * to what they learned is true. Journaled as a tracking event from
     * 'ops', because a state change with no author is the kind of history
     * this estate refuses to keep.
     */
    public static boolean resolve(Connection c, UUID shipmentId, String verdict, String note, Instant at)
            throws SQLException {
        if (!"failed".equals(verdict) && !"delivered".equals(verdict)) {
            throw new IllegalArgumentException("a human resolves to failed or delivered, not " + verdict);
        }
        String orderId = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT order_id FROM shipments WHERE id = ? AND state = 'stuck' FOR UPDATE")) {
            ps.setObject(1, shipmentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;     // only stuck shipments are a human's to move
                orderId = rs.getString(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE shipments SET state = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, verdict);
            ps.setObject(2, shipmentId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO tracking_events(shipment_id, carrier, status, event_key)
                VALUES (?, 'ops', ?, ?) ON CONFLICT DO NOTHING""")) {
            ps.setObject(1, shipmentId);
            ps.setString(2, verdict);
            ps.setString(3, "ops:" + shipmentId + ":" + verdict + ":" + at);
            ps.executeUpdate();
        }
        Outbox.append(c, TOPIC_SHIPMENTS, "shipment." + verdict + ":" + shipmentId, orderId,
                Json.obj("type", "shipment." + verdict, "eventKey", "shipment." + verdict + ":" + shipmentId,
                        "shipmentId", shipmentId.toString(), "orderId", orderId,
                        "resolvedBy", "ops", "note", note == null ? "" : note, "at", at.toString()), at);
        return true;
    }

    private static boolean terminal(Connection c, UUID shipmentId, String state, String reason, Instant at)
            throws SQLException {
        String orderId = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT order_id FROM shipments WHERE id = ? AND state = 'requested' FOR UPDATE")) {
            ps.setObject(1, shipmentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                orderId = rs.getString(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE shipments SET state = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, state);
            ps.setObject(2, shipmentId);
            ps.executeUpdate();
        }
        Outbox.append(c, TOPIC_SHIPMENTS, "shipment." + state + ":" + shipmentId, orderId,
                Json.obj("type", "shipment." + state, "eventKey", "shipment." + state + ":" + shipmentId,
                        "shipmentId", shipmentId.toString(), "orderId", orderId,
                        "reason", reason == null ? "" : reason, "at", at.toString()), at);
        return true;
    }
}
