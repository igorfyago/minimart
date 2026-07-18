package dev.minimart.commerce;

import dev.minimart.core.Db;
import dev.minimart.core.Ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * THE ORDER SPINE · submit, fulfil, abort.
 *
 * submit() is ONE local ACID transaction that does three things together:
 * claims the caller-minted order id, holds the customer's money, and reserves
 * the stock. Either all of it happened or none of it did. Because the stock
 * reservation is a ledger posting against a non-negative account, two hundred
 * concurrent buyers cannot oversell ten units: the losers fail the balance
 * check under the row lock, exactly as an overdraft would.
 *
 * TIME is always a parameter. Nothing here reads the wall clock, which is what
 * lets a simulated year run in minutes without the logic noticing.
 */
public final class Orders {

    public sealed interface Result permits Ok, AlreadyProcessed, OutOfStock, InsufficientFunds {}
    public record Ok(UUID orderId, BigDecimal amount) implements Result {}
    public record AlreadyProcessed(UUID orderId) implements Result {}
    public record OutOfStock(String variantId) implements Result {}
    public record InsufficientFunds(long customerId) implements Result {}

    public static final Duration RESERVATION_TTL = Duration.ofMinutes(30);

    /** Order lifecycle events. Partitioned by order id, so per-order ordering holds. */
    public static final String TOPIC_ORDERS = "minimart.orders";

    private Orders() {}

    // ---------------------------------------------------------------- refs
    public static String wallet(long customerId)  { return "wallet:cust:" + customerId; }
    public static String holds(String tenant)     { return "holds:" + tenant; }
    public static String revenue(String tenant)   { return "revenue:" + tenant; }
    public static String world(String tenant)     { return "world:" + tenant; }
    public static String onHand(String loc, String v)   { return "stock:onhand:" + loc + ':' + v; }
    public static String reserved(String loc, String v) { return "stock:reserved:" + loc + ':' + v; }
    public static String sold(String loc, String v)     { return "stock:sold:" + loc + ':' + v; }
    private static String unit(String variantId) { return "UNIT:" + variantId; }

    /** Deterministic child ids, so a retried fulfil or abort is still one event. */
    static UUID derive(String s) { return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)); }

    // ------------------------------------------------------------- setup ops

    /** The supplier of ONE variant. An account holds exactly one currency, so a
     *  per-tenant supplier account would be pinned to whichever variant arrived
     *  first and every later variant would post a foreign-currency leg into it. */
    public static String supplier(String tenant, String variantId) { return "supplier:" + tenant + ':' + variantId; }

    /** Goods arrive from a supplier (an external account, so it may go negative). */
    public static void receiveStock(String tenant, String location, String variantId, long qty, Instant businessAt)
            throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            Ledger.ensureAccount(c, supplier(tenant, variantId), "external", unit(variantId));
            Ledger.ensureAccount(c, onHand(location, variantId), "stock", unit(variantId));
            Ledger.ensureAccount(c, reserved(location, variantId), "stock", unit(variantId));
            Ledger.ensureAccount(c, sold(location, variantId), "stock", unit(variantId));
            UUID tx = derive("receive:" + location + ':' + variantId + ':' + qty + ':' + businessAt);
            if (Ledger.claimTx(c, tx, "stock.received", businessAt)) {
                Ledger.post(c, tx, businessAt, List.of(
                        new Ledger.Leg(supplier(tenant, variantId), BigDecimal.valueOf(-qty)),
                        new Ledger.Leg(onHand(location, variantId), BigDecimal.valueOf(qty))));
            }
            c.commit();
        }
    }

    /** Simulated money enters the customer's wallet from the world account. */
    public static void fundWallet(String tenant, long customerId, BigDecimal amount, Instant businessAt)
            throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            Ledger.ensureAccount(c, world(tenant), "external", "SIMEUR");
            Ledger.ensureAccount(c, wallet(customerId), "customer", "SIMEUR");
            Ledger.ensureAccount(c, holds(tenant), "holds", "SIMEUR");
            Ledger.ensureAccount(c, revenue(tenant), "revenue", "SIMEUR");
            UUID tx = derive("fund:" + customerId + ':' + amount + ':' + businessAt);
            if (Ledger.claimTx(c, tx, "wallet.funded", businessAt)) {
                Ledger.post(c, tx, businessAt, List.of(
                        new Ledger.Leg(world(tenant), amount.negate()),
                        new Ledger.Leg(wallet(customerId), amount)));
            }
            c.commit();
        }
    }

    // ----------------------------------------------------------------- saga

    /** Hold the money and reserve the goods, atomically. */
    public static Result submit(UUID orderId, String tenant, long customerId,
                                String variantId, String location, long qty, Instant businessAt)
            throws SQLException {
        return submit(orderId, tenant, customerId, variantId, location, qty, businessAt, true);
    }

    /**
     * @param chargeWallet true to settle the money against minimart's own
     *   simulated wallet ledger; false to reserve stock ONLY, because a payment
     *   processor is going to charge the customer instead (see Checkout).
     */
    public static Result submit(UUID orderId, String tenant, long customerId,
                                String variantId, String location, long qty, Instant businessAt,
                                boolean chargeWallet)
            throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                // gate one: this order id has been seen before, do nothing
                if (!Ledger.claimTx(c, orderId, "order.submit", businessAt)) {
                    c.rollback();
                    return new AlreadyProcessed(orderId);
                }
                BigDecimal price = priceOf(c, variantId);
                BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));

                List<Ledger.Leg> legs = new java.util.ArrayList<>();
                if (chargeWallet) {
                    legs.add(new Ledger.Leg(wallet(customerId), amount.negate()));
                    legs.add(new Ledger.Leg(holds(tenant), amount));
                }
                legs.add(new Ledger.Leg(onHand(location, variantId), BigDecimal.valueOf(-qty)));
                legs.add(new Ledger.Leg(reserved(location, variantId), BigDecimal.valueOf(qty)));
                Ledger.post(c, orderId, businessAt, legs);

                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO orders(id, tenant, customer_id, variant_id, location, qty, amount, state,
                                           business_at, payment_mode)
                        VALUES (?,?,?,?,?,?,?, 'reserved', ?, ?)""")) {
                    ps.setObject(1, orderId); ps.setString(2, tenant); ps.setLong(3, customerId);
                    ps.setString(4, variantId); ps.setString(5, location); ps.setLong(6, qty);
                    ps.setBigDecimal(7, amount); ps.setTimestamp(8, java.sql.Timestamp.from(businessAt));
                    ps.setString(9, chargeWallet ? "wallet" : "psp");
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO reservations(id, order_id, variant_id, location, qty, state, expires_at, business_at)
                        VALUES (?,?,?,?,?, 'held', ?, ?)""")) {
                    ps.setObject(1, derive("res:" + orderId)); ps.setObject(2, orderId);
                    ps.setString(3, variantId); ps.setString(4, location); ps.setLong(5, qty);
                    ps.setTimestamp(6, java.sql.Timestamp.from(businessAt.plus(RESERVATION_TTL)));
                    ps.setTimestamp(7, java.sql.Timestamp.from(businessAt));
                    ps.executeUpdate();
                }
                // The announcement joins THIS transaction. If anything above
                // rolls back, the world is never told about an order that did
                // not happen. Keyed by order id, so a later cancellation can
                // never overtake its own placement on the topic.
                dev.minimart.core.Outbox.append(c, TOPIC_ORDERS, orderId.toString(),
                        "{\"type\":\"order.placed\",\"orderId\":\"" + orderId + "\",\"tenant\":\"" + tenant +
                        "\",\"customer\":" + customerId + ",\"variant\":\"" + variantId + "\",\"qty\":" + qty +
                        ",\"amount\":\"" + amount.toPlainString() + "\",\"at\":\"" + businessAt + "\"}",
                        businessAt);
                c.commit();
                return new Ok(orderId, amount);
            } catch (Ledger.Insufficient e) {
                c.rollback();
                return e.accountRef.startsWith("stock:")
                        ? new OutOfStock(variantId)
                        : new InsufficientFunds(customerId);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** The goods ship: held money becomes revenue, reserved units become sold. */
    public static void fulfil(UUID orderId, Instant businessAt) throws SQLException {
        move(orderId, businessAt, "fulfilled", "captured", true);
    }

    /** The order dies: the customer is made whole and the units go back on the shelf. */
    public static void abort(UUID orderId, Instant businessAt) throws SQLException {
        move(orderId, businessAt, "aborted", "released", false);
    }

    private static void move(UUID orderId, Instant businessAt, String orderState,
                             String resState, boolean capture) throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                // lock the reservation row: it, not the pooled account, is the
                // referee between capturing and releasing the same units
                String variantId, location, tenant, mode; long qty, customerId; BigDecimal amount; String state;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT o.tenant, o.customer_id, o.variant_id, o.location, o.qty, o.amount, r.state,
                               o.payment_mode
                        FROM orders o JOIN reservations r ON r.order_id = o.id
                        WHERE o.id = ? FOR UPDATE OF r""")) {
                    ps.setObject(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("no such order: " + orderId);
                        tenant = rs.getString(1); customerId = rs.getLong(2); variantId = rs.getString(3);
                        location = rs.getString(4); qty = rs.getLong(5); amount = rs.getBigDecimal(6);
                        state = rs.getString(7); mode = rs.getString(8);
                    }
                }
                if (!"held".equals(state)) { c.rollback(); return; }   // already settled, do nothing

                // If a processor holds the money, minimart's books carry no money
                // leg at all: only the goods moved here. Checkout captures or
                // cancels the PaymentIntent on the other side of the network.
                // This is read from payment_mode, fixed at creation, precisely so
                // that compensating a FAILED authorisation still knows the truth.
                boolean localMoney = "wallet".equals(mode);

                UUID tx = derive((capture ? "fulfil:" : "abort:") + orderId);
                if (Ledger.claimTx(c, tx, capture ? "order.fulfilled" : "order.aborted", businessAt)) {
                    List<Ledger.Leg> legs = new java.util.ArrayList<>();
                    if (localMoney) {
                        legs.add(new Ledger.Leg(holds(tenant), amount.negate()));
                        legs.add(new Ledger.Leg(capture ? revenue(tenant) : wallet(customerId), amount));
                    }
                    legs.add(new Ledger.Leg(reserved(location, variantId), BigDecimal.valueOf(-qty)));
                    legs.add(new Ledger.Leg(capture ? sold(location, variantId) : onHand(location, variantId),
                            BigDecimal.valueOf(qty)));
                    Ledger.post(c, tx, businessAt, legs);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE reservations SET state = ? WHERE order_id = ? AND state = 'held'")) {
                    ps.setString(1, resState); ps.setObject(2, orderId); ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE orders SET state = ? WHERE id = ?")) {
                    ps.setString(1, orderState); ps.setObject(2, orderId); ps.executeUpdate();
                }
                dev.minimart.core.Outbox.append(c, TOPIC_ORDERS, orderId.toString(),
                        "{\"type\":\"order." + orderState + "\",\"orderId\":\"" + orderId +
                        "\",\"tenant\":\"" + tenant + "\",\"customer\":" + customerId +
                        ",\"variant\":\"" + variantId + "\",\"qty\":" + qty +
                        ",\"amount\":\"" + amount.toPlainString() + "\",\"at\":\"" + businessAt + "\"}",
                        businessAt);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static BigDecimal priceOf(Connection c, String variantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT price FROM variants WHERE id = ?")) {
            ps.setString(1, variantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("no such variant: " + variantId);
                return rs.getBigDecimal(1);
            }
        }
    }
}
