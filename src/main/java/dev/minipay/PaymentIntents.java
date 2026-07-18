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

    /** Where card money comes FROM, as far as this processor's books are
     *  concerned. It is external because the money genuinely is: it sits at an
     *  issuer until clearing brings it, and pretending otherwise would put a
     *  euro on two banks' books at once. */
    public static String scheme() { return "scheme:settlement"; }

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

        // ASK WHETHER WE ALREADY KNOW THIS PAYMENT BEFORE ASKING A BANK ABOUT IT.
        //
        // The first version asked the rail first and only then tried to insert.
        // A retried intent id therefore placed a REAL hold against a real
        // cardholder's credit limit, hit the insert conflict, rolled back, and
        // threw the authorisation reference away. Nothing afterwards could
        // capture or void a reference nobody had stored, so the customer's
        // credit was consumed permanently by a purchase that never existed, and
        // they would have found out at the next till.
        //
        // The common case is now settled without troubling an issuer at all.
        Result existing = existingIntent(id);
        if (existing != null) return existing;

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
                Ledger.ensureAccount(c, scheme(), "external", currency);
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
                    if (ps.executeUpdate() == 0) {
                        // Somebody inserted between our check and this insert.
                        // Rare, and it still must not strand a hold: we asked a
                        // bank for money a moment ago and we are about to stop
                        // owning the record that could ever release it, so we
                        // give it back before we let go.
                        c.rollback();
                        if (finalAuthRef != null) Rails.release(finalRail, finalAuthRef, businessAt);
                        Result now = existingIntent(id);
                        return now != null ? now : new WrongState(id, "unknown");
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

    /**
     * The state this intent is already in, or null if it is new.
     *
     * A DECLINED intent is reported as declined, not as Ok. The first version
     * routed every existing row through get(), which wraps any status in Ok, so
     * a merchant asking again about a payment that had failed was told 200 and
     * shipped the goods. A processor that reports a failure as a success is
     * worse than one that fails.
     */
    private static Result existingIntent(String id) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, amount, decline_reason FROM payment_intents WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String status = rs.getString(1);
                if ("declined".equals(status)) {
                    String why = rs.getString(3);
                    return new Declined(why == null ? "declined" : why);
                }
                return new Ok(id, status, rs.getBigDecimal(2));
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

                // A CAPTURE TAKES THE CARDHOLDER'S MONEY. IT DOES NOT PAY THE
                // MERCHANT.
                //
                // The merchant is paid later, in a batch, net of a fee, by a
                // settlement run. Collapsing the two is the most common way a
                // payments simulation stops being one, because it skips the
                // fee, skips the delay, and quietly asserts that a processor is
                // a wallet. So what a capture creates is a RECEIVABLE: money
                // this processor owes the merchant and has not yet handed over.
                //
                // The receivable exists on every rail. On a card the money is
                // at the issuer and arrives with the clearing; on a wallet it
                // is already here. What the merchant is owed is the same
                // question either way, which is exactly why it belongs in one
                // account rather than in a branch.
                UUID tx = derive((capture ? "capture:" : "cancel:") + id);
                Ledger.ensureAccount(c, Settlements.receivable(merchant), "receivable", currency);
                if (Ledger.claimTx(c, tx, capture ? "payment.captured" : "payment.canceled", businessAt)) {
                    if (capture) {
                        Ledger.post(c, tx, businessAt, "WALLET".equals(rail)
                                // the funds were held here, so they move here
                                ? List.of(new Ledger.Leg(holds(merchant), amount.negate()),
                                          new Ledger.Leg(Settlements.receivable(merchant), amount))
                                // the funds are at the ISSUER. What exists here
                                // is the claim on them, balanced against the
                                // scheme this processor will collect from.
                                : List.of(new Ledger.Leg(scheme(), amount.negate()),
                                          new Ledger.Leg(Settlements.receivable(merchant), amount)));
                    } else if ("WALLET".equals(rail)) {
                        Ledger.post(c, tx, businessAt, List.of(
                                new Ledger.Leg(holds(merchant), amount.negate()),
                                new Ledger.Leg(source(customer), amount)));
                    }
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
