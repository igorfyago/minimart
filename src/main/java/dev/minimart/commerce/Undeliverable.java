package dev.minimart.commerce;

import dev.minimart.core.Json;
import dev.minimart.core.Ledger;
import dev.minimart.core.Outbox;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * THE COMPENSATION LEG · what the shop does when freight says no carrier
 * would.
 *
 * The fulfilment saga's unhappy ending arrives here as an event: freight
 * failed the shipment, announced it, and stopped, because the money was never
 * freight's to move. This consumer is the party whose money it is, reacting
 * on its own books, and it moves exactly as much of the truth as those books
 * hold:
 *
 *   THE GOODS · sold becomes on-hand again. The parcel never left the
 *     building, so "sold" was a claim the world declined to honour, and the
 *     inventory ledger takes it back the same double-entry way it was made.
 *   WALLET MONEY · lives on this ledger, so it goes back to the customer in
 *     the SAME transaction that restocks the shelf. One commit, both truths,
 *     because a crash between "goods returned" and "money returned" would
 *     manufacture exactly the half-honest state this codebase exists to
 *     refuse.
 *   CARD MONEY · is standing at the processor, and minipay has no refund
 *     rail. The wrong move is to improvise one from here; the honest move is
 *     a refund_cases row with status 'due', on the audit surface, where a
 *     debt with a name waits for a rail or a human. When minipay grows
 *     refunds, this consumer is where the call goes, and the case row is
 *     already its journal.
 *
 * Idempotent twice over, like every consumer in this estate: the runtime's
 * claim absorbs redelivery of the same event, and the derived transaction id
 * plus the state-guarded UPDATE absorb the same ORDER arriving under a key
 * nobody has seen. Order-insensitive too, as EventRuntime demands:
 * shipment.failed is terminal per order at the producer, so there is no
 * earlier shipment event this handler could regret missing.
 */
public final class Undeliverable {

    public static final String CONSUMER = "undeliverable";

    /** Freight's topic, restated rather than imported. The two services agree
     *  about BYTES on a wire, not about classes: naming the contract here is
     *  the same doctrine that has each service carry its own Json codec. */
    public static final String TOPIC_SHIPMENTS = "minifreight.shipments.v1";

    private Undeliverable() {}

    /**
     * React to one shipment.failed, inside the runtime's transaction.
     *
     * Throwing means "not handled" and the runtime decides between retry and
     * burial. The distinction drawn here: an event about an order this shop
     * has no record of can never succeed and goes to the dead letters, while
     * an order in any state other than 'fulfilled' is a fact, not a failure ·
     * either the compensation already ran, or the order died before it ever
     * shipped, and both mean there is nothing left to give back.
     */
    public static void onShipmentFailed(Connection c, String eventKey, String payload, Instant businessAt)
            throws Exception {
        if (!"shipment.failed".equals(Json.str(payload, "type"))) return;   // the rest of the topic is not ours

        String orderIdRaw = Json.str(payload, "orderId");
        if (orderIdRaw == null) {
            throw new IllegalArgumentException("shipment.failed carries no orderId: " + payload);
        }
        UUID orderId = UUID.fromString(orderIdRaw);

        // LOOK BEFORE LOCKING. Two reasons, both cheap and both load-bearing.
        //
        // The lock ordering: Orders.move locks ledger accounts and then the
        // order row; taking the order row FOR UPDATE here before deciding
        // whether to act would be the opposite order, and opposite orders are
        // a deadlock waiting for its interleaving. The unlocked read decides;
        // only a decision to act takes the lock, and rechecks under it.
        //
        // The 'reserved' answer: by construction it cannot happen · freight
        // learns an order exists from order.fulfilled, which is announced in
        // the same commit that leaves 'reserved' behind, so a shipment.failed
        // is always causally downstream of 'fulfilled'. But a guard that
        // silently swallowed 'reserved' would be BETTING on that causality
        // forever, and losing the bet would look like nothing at all: claim
        // recorded, no refund, both audits green. So the impossible case
        // throws. If it is transient it retries into correctness, and if it
        // is real it surfaces in the dead letters as the contract violation
        // it would be, instead of as a customer who paid for nothing.
        String state = peek(c, orderId);
        if (state == null) {
            // freight only ships orders this shop announced; a failure
            // notice for an order that does not exist is a defect
            // somebody must SEE, not a retry that will never improve
            throw new IllegalArgumentException("shipment.failed for an unknown order: " + orderId);
        }
        if ("reserved".equals(state)) {
            throw new IllegalStateException(
                    "shipment.failed for an order still reserved: freight cannot know this order yet · " + orderId);
        }
        if (!"fulfilled".equals(state)) return;    // nothing standing to give back

        String tenant, variantId, location, mode;
        long customerId, qty;
        BigDecimal amount;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT tenant, customer_id, variant_id, location, qty, amount, state, payment_mode
                FROM orders WHERE id = ? FOR UPDATE""")) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                tenant = rs.getString(1); customerId = rs.getLong(2); variantId = rs.getString(3);
                location = rs.getString(4); qty = rs.getLong(5); amount = rs.getBigDecimal(6);
                state = rs.getString(7); mode = rs.getString(8);
            }
        }
        if (!"fulfilled".equals(state)) return;    // it moved between the look and the lock

        boolean walletMoney = "wallet".equals(mode);

        // Derived from the ORDER, not the event key: however many distinct
        // keys announce this one failure, there is one compensation.
        UUID tx = Orders.derive("undeliverable:" + orderId);
        if (Ledger.claimTx(c, tx, "order.undeliverable", businessAt)) {
            List<Ledger.Leg> legs = new java.util.ArrayList<>();
            legs.add(new Ledger.Leg(Orders.sold(location, variantId), BigDecimal.valueOf(-qty)));
            legs.add(new Ledger.Leg(Orders.onHand(location, variantId), BigDecimal.valueOf(qty)));
            if (walletMoney) {
                legs.add(new Ledger.Leg(Orders.revenue(tenant), amount.negate()));
                legs.add(new Ledger.Leg(Orders.wallet(customerId), amount));
            }
            Ledger.post(c, tx, businessAt, legs);
        }

        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE orders SET state = 'undeliverable' WHERE id = ? AND state = 'fulfilled'")) {
            ps.setObject(1, orderId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO refund_cases(order_id, tenant, customer_id, amount, mode, intent_id, status, reason)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (order_id) DO NOTHING""")) {
            ps.setObject(1, orderId);
            ps.setString(2, tenant);
            ps.setLong(3, customerId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, mode);
            ps.setString(6, walletMoney ? null : Checkout.intentIdFor(orderId));
            ps.setString(7, walletMoney ? "refunded" : "due");
            ps.setString(8, Json.str(payload, "reason"));
            ps.executeUpdate();
        }

        // The announcement joins the same commit, keyed by order id: whoever
        // counts revenue downstream learns the shop stopped counting this one.
        Outbox.append(c, Orders.TOPIC_ORDERS,
                "order.undeliverable:" + orderId, orderId.toString(),
                Json.obj("type", "order.undeliverable",
                        "eventKey", "order.undeliverable:" + orderId,
                        "orderId", orderId.toString(), "tenant", tenant,
                        "customer", String.valueOf(customerId), "variant", variantId,
                        "qty", String.valueOf(qty), "amount", amount.toPlainString(),
                        "refund", walletMoney ? "refunded" : "due",
                        "at", businessAt.toString()),
                businessAt);
    }

    private static String peek(Connection c, UUID orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT state FROM orders WHERE id = ?")) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
