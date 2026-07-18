package dev.minipay;

import dev.minimart.core.Ledger;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PAYING THE MERCHANT, WHICH IS NOT THE SAME EVENT AS TAKING THE MONEY.
 *
 * A capture takes money from the cardholder. A settlement gives money to the
 * merchant, later, in a batch, and net of a fee. Collapsing those two is the
 * single most common way a payments simulation stops being one, because it
 * skips the fee, skips the delay, and quietly asserts that a processor is a
 * wallet.
 *
 * The gap between them is where a lot of real payments engineering lives: it is
 * why a merchant's sales dashboard never matches their bank statement, why a
 * refund after a payout is awkward, and why "revenue" and "money in the bank"
 * are two different questions somebody will eventually ask you to reconcile.
 */
class SettlementLessonTest {

    static final String MERCHANT = "helix";
    static final Instant T0 = Instant.parse("2027-07-01T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2027, 7, 1);

    @BeforeAll
    static void boot() throws Exception { PayDb.bootstrap(); }

    @BeforeEach
    void reset() throws Exception {
        Settlements.rate = new BigDecimal("0.014");
        Settlements.fixed = new BigDecimal("0.25");
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE settlement_items, settlements, payment_methods, customers, idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
    }

    /**
     * LESSON 1 · A CAPTURE OWES THE MERCHANT. IT DOES NOT PAY THEM.
     *
     * The distinction the whole file exists for. After a capture the
     * cardholder's money is gone and the merchant has nothing in hand: what
     * they have is a claim. Anything that reported this as available balance
     * would be telling a merchant they have money they cannot spend, and every
     * payout afterwards would reconcile to the wrong number.
     */
    @Test
    void lesson1_capture_creates_a_debt_to_the_merchant_not_a_payout() throws Exception {
        sell("pi_a", "100.00", T0);

        assertEquals(0, new BigDecimal("100.00").compareTo(receivable()),
                "the merchant is owed the full amount, before any fee");
        assertEquals(0, balance().signum(),
                "AND THEY HAVE BEEN PAID NOTHING, because no settlement has run");
        assertEquals(0, new BigDecimal("100.00").compareTo(Settlements.outstanding(MERCHANT)),
                "which the processor can state as a number, rather than the merchant having to notice");
        System.out.println("lesson 1: captured 100.00 -> owed 100.00, paid out 0.00");
    }

    /**
     * LESSON 2 · THE PAYOUT IS NET, AND THE FEE IS POSTED SOMEWHERE.
     *
     * A processor that computes a net and writes only that has made the
     * difference disappear, and "where did the missing money go" becomes an
     * argument instead of a query. The fee is a leg of the same transaction, so
     * gross, net and fee are three numbers that must add up and the ledger
     * refuses to let them not.
     *
     * The fee shape is the real one, a percentage plus a fixed piece, because
     * the fixed piece is what makes small sales expensive: this is a fact about
     * payments rather than a detail.
     */
    @Test
    void lesson2_the_merchant_is_paid_net_and_the_fee_is_accounted_for() throws Exception {
        sell("pi_b", "100.00", T0);
        sell("pi_c", "10.00", T0);

        Settlements.Batch batch = Settlements.run(MERCHANT, "SIMEUR", DAY, T0);
        assertNotNull(batch);

        // 100.00 -> 1.40 + 0.25 = 1.65 ; 10.00 -> 0.14 + 0.25 = 0.39
        assertEquals(0, new BigDecimal("110.00").compareTo(batch.gross()));
        assertEquals(0, new BigDecimal("2.04").compareTo(batch.fee()));
        assertEquals(0, new BigDecimal("107.96").compareTo(batch.net()));

        assertEquals(0, batch.net().compareTo(balance()), "the merchant was paid the NET");
        assertEquals(0, batch.fee().compareTo(fees()), "and the fee is somewhere, not nowhere");
        assertEquals(0, receivable().signum(), "the debt is discharged in full");

        try (Connection c = PayDb.open()) {
            assertEquals(List.of(), Ledger.sumZeroViolations(c),
                    "gross out, net and fee in: the transaction sums to zero or it does not post");
        }
        // the small sale lost 3.9% and the large one 1.65%, which is the whole
        // reason a fixed component exists and why merchants care about it
        assertEquals(0, new BigDecimal("0.39").compareTo(Settlements.feeFor(new BigDecimal("10.00"))));
        System.out.println("lesson 2: gross 110.00 = net 107.96 + fee 2.04, and every part is posted");
    }

    /**
     * LESSON 3 · A MERCHANT IS PAID ONCE FOR A DAY, HOWEVER OFTEN IT IS RUN.
     *
     * Settlement runs on a schedule, and schedules double-fire: a retry, an
     * operator running it by hand, two workers waking together. Paying a
     * merchant twice is the failure this file exists to prevent, so it is a
     * UNIQUE constraint rather than a query somebody has to keep writing
     * correctly.
     */
    @Test
    void lesson3_running_settlement_five_times_pays_once() throws Exception {
        sell("pi_d", "50.00", T0);

        Settlements.Batch first = Settlements.run(MERCHANT, "SIMEUR", DAY, T0);
        assertNotNull(first);
        for (int i = 0; i < 4; i++) {
            assertNull(Settlements.run(MERCHANT, "SIMEUR", DAY, T0),
                    "already settled, so there is nothing to do and nothing is done");
        }

        assertEquals(0, first.net().compareTo(balance()), "paid exactly once");
        assertEquals(1, batchCount(), "one batch for the day");
        System.out.println("lesson 3: five settlement runs produced one batch and one payout of " + balance());
    }

    /**
     * LESSON 4 · TWO SETTLEMENT RUNS AT ONCE CANNOT BOTH PAY.
     *
     * The sequential version of this passes against an implementation that
     * collects the sales, computes a total, and then inserts. Run two of those
     * at the same instant and both collect the same sales.
     *
     * The rows are taken FOR UPDATE, so the second run waits and then finds
     * nothing left to settle.
     */
    @Test
    void lesson4_concurrent_settlement_runs_do_not_double_pay() throws Exception {
        for (int i = 0; i < 10; i++) sell("pi_race_" + i, "25.00", T0);

        List<Settlements.Batch> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Settlements.Batch>> fs = new ArrayList<>();
            for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> Settlements.run(MERCHANT, "SIMEUR", DAY, T0)));
            for (Future<Settlements.Batch> f : fs) {
                Settlements.Batch b = f.get();
                if (b != null) results.add(b);
            }
        }

        assertEquals(1, results.size(), "SIX RUNS, ONE BATCH");
        assertEquals(10, results.get(0).items(), "and it covered every sale exactly once");
        assertEquals(0, results.get(0).net().compareTo(balance()));
        assertEquals(0, receivable().signum(), "nothing left owing, and nothing paid twice");
        System.out.println("lesson 4: 6 concurrent settlement runs -> 1 batch of 10 items, paid once");
    }

    /**
     * LESSON 5 · A SALE MADE AFTER THE PAYOUT WAITS FOR THE NEXT ONE.
     *
     * Settlement covers a business DAY, not "everything up to now", so a sale
     * that lands after a day is settled belongs to its own day rather than
     * quietly joining a batch that has already been paid. Getting this wrong
     * produces a batch whose total changes after the merchant has been told it.
     */
    @Test
    void lesson5_a_later_sale_belongs_to_its_own_day() throws Exception {
        sell("pi_today", "60.00", T0);
        Settlements.Batch today = Settlements.run(MERCHANT, "SIMEUR", DAY, T0);
        assertNotNull(today);
        BigDecimal paidForToday = balance();

        // a sale the next day, after today's payout has gone
        Instant tomorrow = T0.plus(java.time.Duration.ofDays(1));
        sell("pi_tomorrow", "60.00", tomorrow);

        assertEquals(0, paidForToday.compareTo(balance()), "yesterday's payout is unchanged, as it must be");
        assertEquals(0, new BigDecimal("60.00").compareTo(Settlements.outstanding(MERCHANT)),
                "and the new sale is simply outstanding");

        Settlements.Batch nextDay = Settlements.run(MERCHANT, "SIMEUR", DAY.plusDays(1), tomorrow);
        assertNotNull(nextDay);
        assertEquals(1, nextDay.items(), "the next day's batch holds only the next day's sale");
        assertEquals(0, receivable().signum(), "and now nothing is owed");
        System.out.println("lesson 5: a sale after the payout waited for its own day's batch");
    }

    /**
     * LESSON 6 · THE RECEIVABLE AGREES WITH THE UNSETTLED SALES.
     *
     * Two ways of asking the same question, one from the ledger and one from the
     * payment records. A number that agrees with itself proves nothing, which is
     * why this audit reads from both sides and subtracts.
     */
    @Test
    void lesson6_the_ledger_and_the_payment_records_agree_about_what_is_owed() throws Exception {
        sell("pi_x", "80.00", T0);
        sell("pi_y", "20.00", T0);
        assertEquals(0, Settlements.receivableDrift(MERCHANT).signum(),
                "before settlement, the two views agree");

        Settlements.run(MERCHANT, "SIMEUR", DAY, T0);
        assertEquals(0, Settlements.receivableDrift(MERCHANT).signum(),
                "and after it, they still do");

        sell("pi_z", "15.00", T0.plus(java.time.Duration.ofHours(2)));
        assertEquals(0, Settlements.receivableDrift(MERCHANT).signum(),
                "and a fresh sale lands in both at once, because it is one transaction");
        System.out.println("lesson 6: the receivable balance and the unsettled sales agreed at every point");
    }


    /**
     * LESSON 7 · MONEY THAT PREDATES A NEW MODEL IS NAMED, NOT SWEPT UP.
     *
     * Found in production, on the first settlement run after capture started
     * creating a receivable instead of paying the merchant directly. Settlement
     * gathered every succeeded unsettled payment and tried to draw the total
     * from the receivable account. It failed, because payments captured under
     * the OLD model had credited the merchant directly and never posted a
     * receivable at all. The ledger refused to go negative, which is precisely
     * what it is for, and settlement stopped entirely.
     *
     * The bug was not in settlement. Introducing a new money model left a
     * question unanswered, and the unanswered question became a crash: what is
     * the status of money that completed before the concept existed?
     *
     * The answer is explicit rather than clever. A payment either posted a
     * receivable or it did not, settlement only takes the ones that did, and
     * the others are COUNTED so that "why is the outstanding total not the sum
     * of every succeeded payment" has an answer.
     */
    @Test
    void lesson7_a_payment_from_before_the_receivable_model_does_not_break_settlement() throws Exception {
        // one payment under the current model
        sell("pi_new", "100.00", T0);
        // and one exactly as the old code left them: succeeded, unsettled, with
        // no receivable behind it
        legacySale("pi_legacy", "70.00", T0);

        assertEquals(1, Settlements.predatingReceivables(MERCHANT),
                "the older payment is visible as what it is, rather than lurking in a total");
        assertEquals(0, new BigDecimal("100.00").compareTo(Settlements.outstanding(MERCHANT)),
                "and outstanding counts only what actually has money behind it");

        Settlements.Batch b = assertDoesNotThrow(() -> Settlements.run(MERCHANT, "SIMEUR", DAY, T0),
                "SETTLEMENT RUNS. Before the fix this threw and no merchant could be paid at all.");
        assertNotNull(b);
        assertEquals(1, b.items(), "it settled the one payment that had a receivable");
        assertEquals(0, new BigDecimal("100.00").compareTo(b.gross()), "and nothing it could not back");
        assertEquals(0, Settlements.receivableDrift(MERCHANT).signum(), "the two views still agree");
        System.out.println("lesson 7: a payment predating the model was named and skipped, and settlement ran");
    }

    /** A sale exactly as the old code left it: succeeded and unsettled, with no
     *  receivable posted behind it. */
    private static void legacySale(String intentId, String amount, Instant at) throws Exception {
        sell(intentId, amount, at);
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("UPDATE payment_intents SET receivable_posted = FALSE WHERE id = ?")) {
            ps.setString(1, intentId);
            ps.executeUpdate();
        }
        // and take the receivable back out, as it never would have been posted
        try (Connection c = PayDb.open()) {
            c.setAutoCommit(false);
            java.util.UUID tx = java.util.UUID.randomUUID();
            Ledger.claimTx(c, tx, "legacy.adjust", at);
            Ledger.ensureAccount(c, "legacy:sink", "external", "SIMEUR");
            Ledger.post(c, tx, at, java.util.List.of(
                    new Ledger.Leg(Settlements.receivable(MERCHANT), new BigDecimal(amount).negate()),
                    new Ledger.Leg("legacy:sink", new BigDecimal(amount))));
            c.commit();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A completed sale: authorise on the processor's own funds, then capture. */
    private static void sell(String intentId, String amount, Instant at) throws Exception {
        var r = PaymentIntents.authorize(intentId, new BigDecimal(amount), "SIMEUR",
                "cust_" + UUID.randomUUID().toString().substring(0, 8), MERCHANT, at);
        assertInstanceOf(PaymentIntents.Ok.class, r, "authorised: " + r);
        assertInstanceOf(PaymentIntents.Ok.class, PaymentIntents.capture(intentId, at));
    }

    private static BigDecimal receivable() throws Exception { return bal(Settlements.receivable(MERCHANT)); }
    private static BigDecimal balance() throws Exception { return bal(PaymentIntents.balance(MERCHANT)); }
    private static BigDecimal fees() throws Exception { return bal(Settlements.fees("minipay")); }

    private static BigDecimal bal(String account) throws Exception {
        try (Connection c = PayDb.open()) {
            try { return Ledger.balance(c, account); }
            catch (IllegalArgumentException none) { return BigDecimal.ZERO; }
        }
    }

    private static long batchCount() throws Exception {
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("SELECT COUNT(*) FROM settlements");
             var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
    }
}
