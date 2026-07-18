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
        return authorize(id, amount, currency, customer, merchant, null, businessAt);
    }

    /**
     * Authorise, on whichever rail the payment method belongs to.
     *
     * WITH a payment method, this behaves like a processor: the money is
     * authorised at whichever issuer actually holds it, and the merchant is
     * told only whether it worked. WITHOUT one, the older behaviour stands and
     * the funding source is an account here, which is what the simulation used
     * before there were any issuers to ask.
     *
     * The order of the two steps is the important part and it is deliberate:
     * THE ISSUER IS ASKED FIRST, and this processor's own books move only after
     * it says yes. Recording the money first and asking afterwards would mean
     * every decline needed unwinding, and the window between the two would be a
     * period where minipay believed it held money that no bank had agreed to.
     */
    public static Result authorize(String id, BigDecimal amount, String currency,
                                   String customer, String merchant, String paymentMethodId,
                                   Instant businessAt) throws SQLException {
        // Test instruments, the way every processor provides them: a customer
        // whose reference says "decline" always declines, so failure paths can
        // be exercised deterministically instead of hoped for.
        if (customer.contains("decline")) return new Declined("card_declined");

        String rail = "WALLET";
        String authorizationRef = null;
        if (paymentMethodId != null) {
            PaymentMethods.Method pm = PaymentMethods.find(paymentMethodId);
            if (pm == null) return new Declined("no such payment method");
            if (!"active".equals(pm.status())) return new Declined("payment method not usable");
            rail = pm.rail();

            Rails.Outcome outcome = Rails.authorize(rail, pm.instrument(), id, amount, currency, businessAt);
            if (!outcome.approved()) {
                // A DECLINE IS RECORDED, not merely returned. A processor that
                // forgets its declines cannot answer the only question a
                // merchant ever asks afterwards, which is why a customer could
                // not pay.
                recordDecline(id, amount, currency, customer, merchant, paymentMethodId, rail,
                        outcome.declineReason(), businessAt);
                return new Declined(outcome.declineReason());
            }
            authorizationRef = outcome.authorizationRef();
        }

        final String finalRail = rail;
        final String finalAuthRef = authorizationRef;
        try (Connection c = PayDb.open()) {
            c.setAutoCommit(false);
            try {
                Ledger.ensureAccount(c, source(customer), "external", currency);
                Ledger.ensureAccount(c, holds(merchant), "holds", currency);
                Ledger.ensureAccount(c, balance(merchant), "merchant", currency);

                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO payment_intents(id, amount, currency, customer_ref, merchant_ref, status,
                                                    business_at, payment_method, rail, issuer_authorization)
                        VALUES (?,?,?,?,?, 'requires_capture', ?,?,?,?) ON CONFLICT (id) DO NOTHING""")) {
                    ps.setString(1, id); ps.setBigDecimal(2, amount); ps.setString(3, currency);
                    ps.setString(4, customer); ps.setString(5, merchant);
                    ps.setTimestamp(6, java.sql.Timestamp.from(businessAt));
                    ps.setString(7, paymentMethodId); ps.setString(8, finalRail); ps.setString(9, finalAuthRef);
                    if (ps.executeUpdate() == 0) {   // already exists: report its current state
                        c.rollback();
                        return get(id);
                    }
                }
                // The processor's own ledger models money it is actually
                // holding. On a card rail the money is held at the ISSUER, not
                // here, so posting it here as well would double-count it: the
                // same euro would appear on two banks' books at once. What
                // minipay records for a card is the authorisation, and the
                // clearing that follows.
                if ("WALLET".equals(finalRail)) {
                    UUID tx = derive("auth:" + id);
                    if (Ledger.claimTx(c, tx, "payment.authorized", businessAt)) {
                        Ledger.post(c, tx, businessAt, List.of(
                                new Ledger.Leg(source(customer), amount.negate()),
                                new Ledger.Leg(holds(merchant), amount)));
                    }
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

    /** A decline is a fact worth keeping. A merchant whose customer could not
     *  pay asks exactly one question afterwards, and a processor with no record
     *  of the refusal cannot answer it. */
    private static void recordDecline(String id, BigDecimal amount, String currency, String customer,
                                      String merchant, String paymentMethodId, String rail,
                                      String reason, Instant businessAt) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO payment_intents(id, amount, currency, customer_ref, merchant_ref, status,
                                                 business_at, payment_method, rail, decline_reason)
                     VALUES (?,?,?,?,?, 'declined', ?,?,?,?) ON CONFLICT (id) DO NOTHING""")) {
            ps.setString(1, id); ps.setBigDecimal(2, amount); ps.setString(3, currency);
            ps.setString(4, customer); ps.setString(5, merchant);
            ps.setTimestamp(6, java.sql.Timestamp.from(businessAt));
            ps.setString(7, paymentMethodId); ps.setString(8, rail); ps.setString(9, reason);
            ps.executeUpdate();
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
                String status, customer, merchant, currency, rail, authRef; BigDecimal amount;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT status, customer_ref, merchant_ref, currency, amount, rail, issuer_authorization
                          FROM payment_intents WHERE id = ? FOR UPDATE""")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new NotFound(id); }
                        status = rs.getString(1); customer = rs.getString(2);
                        merchant = rs.getString(3); currency = rs.getString(4); amount = rs.getBigDecimal(5);
                        rail = rs.getString(6); authRef = rs.getString(7);
                    }
                }
                String target = capture ? "succeeded" : "canceled";
                if (target.equals(status)) { c.rollback(); return new Ok(id, status, amount); }  // idempotent
                if (!"requires_capture".equals(status)) { c.rollback(); return new WrongState(id, status); }

                // Tell the issuer BEFORE this processor's books say it is done.
                // A capture recorded here that the issuer never performed is
                // money this processor believes it has and no bank has released,
                // and the merchant would be paid out of a balance that does not
                // exist.
                boolean railOk = capture
                        ? Rails.capture(rail, authRef, businessAt)
                        : Rails.release(rail, authRef, businessAt);
                if (!railOk) { c.rollback(); return new Declined("issuer unavailable"); }

                UUID tx = derive((capture ? "capture:" : "cancel:") + id);
                if ("WALLET".equals(rail) && Ledger.claimTx(c, tx, capture ? "payment.captured" : "payment.canceled", businessAt)) {
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
