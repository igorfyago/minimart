package dev.minipay;

import dev.minimart.core.Ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * THE PAYMENT INTENT · authorise, capture, cancel.
 *
 * The same authorize/capture/void lifecycle a card network runs, and the same
 * one minibank proved: money leaves the funding source into a hold at
 * authorisation, and only becomes the merchant's at capture. A shipped order
 * captures; an abandoned one cancels and the customer never really paid.
 */
public final class PaymentIntents {

    public sealed interface Result permits Ok, NotFound, WrongState, Declined {}
    public record Ok(String id, String status, BigDecimal amount) implements Result {}
    public record NotFound(String id) implements Result {}
    public record WrongState(String id, String status) implements Result {}
    public record Declined(String reason) implements Result {}

    public static String source(String customer)  { return "source:" + customer; }
    public static String holds(String merchant)   { return "holds:" + merchant; }
    public static String balance(String merchant) { return "balance:" + merchant; }

    private PaymentIntents() {}

    private static UUID derive(String s) { return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)); }

    /** Authorise: money moves from the funding source into a hold. */
    public static Result authorize(String id, BigDecimal amount, String currency,
                                   String customer, String merchant, Instant businessAt) throws SQLException {
        try (Connection c = PayDb.open()) {
            c.setAutoCommit(false);
            try {
                Ledger.ensureAccount(c, source(customer), "external", currency);
                Ledger.ensureAccount(c, holds(merchant), "holds", currency);
                Ledger.ensureAccount(c, balance(merchant), "merchant", currency);

                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO payment_intents(id, amount, currency, customer_ref, merchant_ref, status, business_at)
                        VALUES (?,?,?,?,?, 'requires_capture', ?) ON CONFLICT (id) DO NOTHING""")) {
                    ps.setString(1, id); ps.setBigDecimal(2, amount); ps.setString(3, currency);
                    ps.setString(4, customer); ps.setString(5, merchant);
                    ps.setTimestamp(6, java.sql.Timestamp.from(businessAt));
                    if (ps.executeUpdate() == 0) {   // already exists: report its current state
                        c.rollback();
                        return get(id);
                    }
                }
                UUID tx = derive("auth:" + id);
                if (Ledger.claimTx(c, tx, "payment.authorized", businessAt)) {
                    Ledger.post(c, tx, businessAt, List.of(
                            new Ledger.Leg(source(customer), amount.negate()),
                            new Ledger.Leg(holds(merchant), amount)));
                }
                c.commit();
                return new Ok(id, "requires_capture", amount);
            } catch (Ledger.Insufficient e) {
                c.rollback();
                return new Declined("insufficient funds at " + e.accountRef);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Capture: the hold becomes the merchant's money. */
    public static Result capture(String id, Instant businessAt) throws SQLException {
        return settle(id, businessAt, true);
    }

    /** Cancel: the hold goes back to the funding source. */
    public static Result cancel(String id, Instant businessAt) throws SQLException {
        return settle(id, businessAt, false);
    }

    private static Result settle(String id, Instant businessAt, boolean capture) throws SQLException {
        try (Connection c = PayDb.open()) {
            c.setAutoCommit(false);
            try {
                String status, customer, merchant, currency; BigDecimal amount;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT status, customer_ref, merchant_ref, currency, amount FROM payment_intents WHERE id = ? FOR UPDATE")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new NotFound(id); }
                        status = rs.getString(1); customer = rs.getString(2);
                        merchant = rs.getString(3); currency = rs.getString(4); amount = rs.getBigDecimal(5);
                    }
                }
                String target = capture ? "succeeded" : "canceled";
                if (target.equals(status)) { c.rollback(); return new Ok(id, status, amount); }  // idempotent
                if (!"requires_capture".equals(status)) { c.rollback(); return new WrongState(id, status); }

                UUID tx = derive((capture ? "capture:" : "cancel:") + id);
                if (Ledger.claimTx(c, tx, capture ? "payment.captured" : "payment.canceled", businessAt)) {
                    Ledger.post(c, tx, businessAt, capture
                            ? List.of(new Ledger.Leg(holds(merchant), amount.negate()),
                                      new Ledger.Leg(balance(merchant), amount))
                            : List.of(new Ledger.Leg(holds(merchant), amount.negate()),
                                      new Ledger.Leg(source(customer), amount)));
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE payment_intents SET status = ?, settled_at = ? WHERE id = ?")) {
                    ps.setString(1, target);
                    ps.setTimestamp(2, java.sql.Timestamp.from(businessAt));
                    ps.setString(3, id);
                    ps.executeUpdate();
                }
                c.commit();
                return new Ok(id, target, amount);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public static Result get(String id) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, amount FROM payment_intents WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new NotFound(id);
                return new Ok(id, rs.getString(1), rs.getBigDecimal(2));
            }
        }
    }

    public static String toJson(Ok ok) {
        return "{\"id\":\"" + Json.esc(ok.id()) + "\",\"object\":\"payment_intent\",\"status\":\"" +
               ok.status() + "\",\"amount\":\"" + money(ok.amount()) + "\"}";
    }

    /** The ledger stores NUMERIC(20,8) because units of stock need the room.
     *  An API should not leak that: render money at its natural scale. */
    public static String money(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 2) s = s.setScale(2);
        return s.toPlainString();
    }
}
