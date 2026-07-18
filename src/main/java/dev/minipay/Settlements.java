package dev.minipay;

import dev.minimart.core.Ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PAYING THE MERCHANT, WHICH IS NOT THE SAME EVENT AS TAKING THE MONEY.
 *
 * A capture takes money from the cardholder. A settlement gives money to the
 * merchant, later, in a batch, and net of a fee. Collapsing the two is the most
 * common way a payments simulation stops being one, because it skips the fee,
 * skips the delay, and quietly asserts that a processor is a wallet.
 *
 * The gap between those two moments is where a lot of real payments engineering
 * lives. It is why a merchant's sales dashboard never matches their bank
 * statement, why a refund issued after a payout is awkward, and why "revenue"
 * and "money in the account" are two different questions that a merchant will
 * eventually ask you to reconcile.
 *
 * Three accounts, and the shape of the double entry says the whole story:
 *
 *   receivable:{merchant} · money owed to the merchant, created at capture
 *   balance:{merchant}    · money actually paid out to them
 *   fees:{acquirer}       · what this processor kept
 *
 * A settlement moves the whole receivable, splits it, and the transaction sums
 * to zero: the fee is not deducted from a number, it is POSTED somewhere, so
 * "where did the missing 2 euros go" is a query rather than an argument.
 */
public final class Settlements {

    /**
     * The published rate. Deliberately the shape a real card fee takes, a
     * percentage plus a fixed piece, because the fixed piece is what makes
     * small transactions expensive and that is a real fact about payments
     * rather than a detail: at 0.25 fixed, a 1 euro sale loses a quarter of
     * itself and a 100 euro sale barely notices.
     */
    public static volatile BigDecimal rate = new BigDecimal("0.014");
    public static volatile BigDecimal fixed = new BigDecimal("0.25");

    public record Batch(String id, String merchant, String currency, LocalDate businessDate,
                        BigDecimal gross, BigDecimal fee, BigDecimal net, int items) {}

    public static String receivable(String merchant) { return "receivable:" + merchant; }
    public static String fees(String acquirer)       { return "fees:" + acquirer; }

    private Settlements() {}

    /** What this processor charges for one sale. Rounded half up, at the item
     *  level rather than on the batch total, because that is where a merchant
     *  will check it and a total that cannot be derived from its lines is a
     *  total nobody trusts. */
    public static BigDecimal feeFor(BigDecimal amount) {
        return amount.multiply(rate).add(fixed).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Pay a merchant for one business day.
     *
     * Idempotent by the UNIQUE on (merchant, currency, business_date): running
     * settlement twice pays once. That is enforced by the schema rather than by
     * the batching query being written correctly forever, because paying a
     * merchant twice is the failure this whole file exists to prevent.
     *
     * Returns null when there was nothing to settle, which is not an error: most
     * merchants have quiet days and a batch of zero would be noise in a report
     * somebody has to read.
     */
    public static Batch run(String merchant, String currency, LocalDate businessDate, Instant at)
            throws SQLException {
        record Item(String intentId, BigDecimal amount) {}

        try (Connection c = PayDb.open()) {
            c.setAutoCommit(false);
            try {
                List<Item> items = new ArrayList<>();
                // FOR UPDATE, so two settlement runs cannot each collect the
                // same sales and discover the collision only at the insert.
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT id, amount FROM payment_intents
                         WHERE merchant_ref = ? AND currency = ? AND status = 'succeeded'
                           AND settlement_id IS NULL
                           -- ONLY what actually posted a receivable. A payment
                           -- that completed before the receivable model existed
                           -- credited the merchant directly and has nothing here
                           -- to draw down, and gathering it would make the batch
                           -- larger than the money backing it.
                           AND receivable_posted
                           AND business_at >= ? AND business_at < ?
                         ORDER BY id
                         FOR UPDATE""")) {
                    ps.setString(1, merchant); ps.setString(2, currency);
                    ps.setTimestamp(3, java.sql.Timestamp.from(businessDate.atStartOfDay(ZoneOffset.UTC).toInstant()));
                    ps.setTimestamp(4, java.sql.Timestamp.from(businessDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) items.add(new Item(rs.getString(1), rs.getBigDecimal(2)));
                    }
                }
                if (items.isEmpty()) { c.rollback(); return null; }

                BigDecimal gross = BigDecimal.ZERO, fee = BigDecimal.ZERO;
                for (Item i : items) {
                    gross = gross.add(i.amount());
                    fee = fee.add(feeFor(i.amount()));
                }
                BigDecimal net = gross.subtract(fee);

                String id = "st_" + UUID.nameUUIDFromBytes(
                        (merchant + ':' + currency + ':' + businessDate).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString().replace("-", "").substring(0, 16);

                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO settlements(id, merchant, currency, business_date, gross, fee, net, item_count)
                        VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (merchant, currency, business_date) DO NOTHING""")) {
                    ps.setString(1, id); ps.setString(2, merchant); ps.setString(3, currency);
                    ps.setObject(4, businessDate);
                    ps.setBigDecimal(5, gross); ps.setBigDecimal(6, fee); ps.setBigDecimal(7, net);
                    ps.setInt(8, items.size());
                    if (ps.executeUpdate() == 0) { c.rollback(); return null; }   // already paid today
                }

                for (Item i : items) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO settlement_items(settlement_id, payment_intent_id, amount, fee)
                            VALUES (?,?,?,?)""")) {
                        ps.setString(1, id); ps.setString(2, i.intentId());
                        ps.setBigDecimal(3, i.amount()); ps.setBigDecimal(4, feeFor(i.amount()));
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE payment_intents SET settlement_id = ? WHERE id = ?")) {
                        ps.setString(1, id); ps.setString(2, i.intentId());
                        ps.executeUpdate();
                    }
                }

                // THE FEE IS POSTED, NOT DEDUCTED. A processor that computes a
                // net and writes only that has made the difference disappear,
                // and "where did the missing money go" becomes an argument
                // instead of a query.
                Ledger.ensureAccount(c, receivable(merchant), "receivable", currency);
                Ledger.ensureAccount(c, PaymentIntents.balance(merchant), "merchant", currency);
                Ledger.ensureAccount(c, fees("minipay"), "revenue", currency);

                UUID tx = UUID.nameUUIDFromBytes(("settle:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (Ledger.claimTx(c, tx, "settlement", at)) {
                    Ledger.post(c, tx, at, List.of(
                            new Ledger.Leg(receivable(merchant), gross.negate()),
                            new Ledger.Leg(PaymentIntents.balance(merchant), net),
                            new Ledger.Leg(fees("minipay"), fee)));
                }
                c.commit();
                return new Batch(id, merchant, currency, businessDate, gross, fee, net, items.size());
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * AUDIT · money captured that nobody has been paid for.
     *
     * Not a failure on its own: a sale captured today and settled tomorrow is
     * outstanding all night and that is correct. It becomes a question when it
     * stops moving, and the point is that the question CAN be asked. A
     * receivable nobody can enumerate is a merchant who has to notice for
     * themselves that they were never paid.
     */
    public static BigDecimal outstanding(String merchant) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COALESCE(SUM(amount), 0) FROM payment_intents
                      WHERE merchant_ref = ? AND status = 'succeeded' AND settlement_id IS NULL
                        AND receivable_posted""")) {
            ps.setString(1, merchant);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBigDecimal(1); }
        }
    }

    /**
     * AUDIT · the receivable balance must equal what is genuinely unsettled.
     *
     * Two ways of asking the same question, one from the ledger and one from the
     * payment records, which is the only kind of check worth running: a number
     * that agrees with itself proves nothing.
     */
    /**
     * AUDIT · payments that completed before the receivable model existed.
     *
     * Not a failure and not a backlog: they were paid out under the older rules
     * and are finished. They are counted so that "why is the outstanding total
     * not the sum of every succeeded payment" has an answer, which is the only
     * reason a number like this is worth exposing at all. A quantity nobody can
     * name is the thing that makes a reconciliation impossible to close.
     */
    public static long predatingReceivables(String merchant) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COUNT(*) FROM payment_intents
                      WHERE merchant_ref = ? AND status = 'succeeded'
                        AND settlement_id IS NULL AND NOT receivable_posted""")) {
            ps.setString(1, merchant);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    public static BigDecimal receivableDrift(String merchant) throws SQLException {
        try (Connection c = PayDb.open()) {
            BigDecimal fromLedger;
            try {
                fromLedger = Ledger.balance(c, receivable(merchant));
            } catch (IllegalArgumentException noAccount) {
                fromLedger = BigDecimal.ZERO;
            }
            return fromLedger.subtract(outstanding(merchant));
        }
    }
}
