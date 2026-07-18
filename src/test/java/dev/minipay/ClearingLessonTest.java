package dev.minipay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLEARING · two banks arriving at the same number without sharing a database.
 *
 * Authorisation is real time because a customer is standing there. Clearing is
 * not: it is the acquirer telling the issuer, later and in bulk, which
 * authorisations it actually completed, and it is where the two work out what
 * one owes the other. That division is the architecture of card payments in one
 * line, and it is why this system uses both a synchronous call and a broker:
 * SYNCHRONOUS WHERE SOMEBODY IS WAITING FOR A DECISION, ASYNCHRONOUS EVERYWHERE
 * ELSE.
 *
 * INTERCHANGE is what makes it worth modelling. The issuer keeps a fee for
 * having lent the customer the money and carried the risk, so the acquirer
 * receives less than the merchant charged. Gross, interchange and net are three
 * numbers that two organisations must agree on, and the only honest way to know
 * they agree is for both to compute them independently and compare.
 *
 * The issuer here is a stand-in running minibank's REAL clearing arithmetic, so
 * the reconciliation is a genuine comparison of two independent calculations
 * rather than a number echoed back.
 */
class ClearingLessonTest {

    static HttpServer issuer;
    static final int ISSUER_PORT = 18220;
    static final String MERCHANT = "helix";
    static final Instant T0 = Instant.parse("2027-08-01T09:00:00Z");
    static final LocalDate DAY = LocalDate.of(2027, 8, 1);

    /** The stand-in issuer's holds, and what it has already cleared. */
    static final ConcurrentHashMap<UUID, BigDecimal> holds = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> clearedBatches = new ConcurrentHashMap<>();
    static final BigDecimal ISSUER_RATE = new BigDecimal("0.008");

    @BeforeAll
    static void boot() throws Exception {
        PayDb.bootstrap();
        issuer = HttpServer.create(new InetSocketAddress(ISSUER_PORT), 0);
        issuer.createContext("/issuer/v1/authorizations", ClearingLessonTest::authorizeHandler);
        issuer.createContext("/issuer/v1/clearing", ClearingLessonTest::clearingHandler);
        issuer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        issuer.start();
        Rails.issuerBaseUrl = "http://localhost:" + ISSUER_PORT;
        Clearing.issuerBaseUrl = "http://localhost:" + ISSUER_PORT;
    }

    @AfterAll
    static void stop() { if (issuer != null) issuer.stop(0); }

    @BeforeEach
    void reset() throws Exception {
        Rails.issuerUnreachable = false;
        Clearing.issuerUnreachable = false;
        Clearing.interchangeRate = new BigDecimal("0.008");
        holds.clear(); clearedBatches.clear();
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE clearing_items, clearing_batches, settlement_items, settlements, payment_methods, customers, idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
    }

    /**
     * LESSON 1 · THE TWO SIDES COMPUTE THE SAME NUMBER SEPARATELY.
     *
     * The whole point. The acquirer works out what it is owed from its own
     * records; the issuer works out what it owes from the holds it is actually
     * carrying. Neither uses the other's figure. They then compare, and the
     * comparison means something precisely because nobody copied.
     */
    @Test
    void lesson1_acquirer_and_issuer_agree_without_sharing_a_database() throws Exception {
        cardSale("pi_1", "100.00");
        cardSale("pi_2", "250.00");

        Clearing.Batch built = Clearing.build("minibank", "EUR", DAY, T0);
        assertNotNull(built);
        assertEquals(0, new BigDecimal("350.00").compareTo(built.gross()));
        // 100 -> 0.80, 250 -> 2.00
        assertEquals(0, new BigDecimal("2.80").compareTo(built.interchange()));
        assertEquals(0, new BigDecimal("347.20").compareTo(built.net()),
                "what the acquirer expects to receive, computed from its own records");

        Clearing.Batch acked = Clearing.submit(built.id(), T0);
        assertEquals("acknowledged", acked.state());
        assertTrue(acked.agreed(),
                "THE ISSUER ARRIVED AT THE SAME NET, from the holds it was carrying, having copied nothing");
        assertEquals(0, acked.net().compareTo(acked.issuerNet()));
        System.out.println("lesson 1: acquirer said " + acked.net() + ", issuer independently said "
                + acked.issuerNet() + ", on 350.00 gross");
    }

    /**
     * LESSON 2 · A DISAGREEMENT IS VISIBLE, NOT SMOOTHED OVER.
     *
     * The test that decides whether lesson 1 proved anything. If the acquirer
     * simply adopted whatever the issuer replied, every reconciliation would
     * pass and none would mean anything, and this lesson could not exist.
     *
     * Here the acquirer's interchange rate is wrong, as it would be after a
     * pricing change one side applied and the other did not. Both numbers
     * survive, side by side, and the batch reports that they differ.
     */
    @Test
    void lesson2_when_the_two_sides_disagree_both_numbers_survive() throws Exception {
        cardSale("pi_3", "1000.00");

        // the acquirer believes interchange is 2%, the issuer knows it is 0.8%
        Clearing.interchangeRate = new BigDecimal("0.02");
        Clearing.Batch built = Clearing.build("minibank", "EUR", DAY, T0);
        assertEquals(0, new BigDecimal("980.00").compareTo(built.net()), "what the acquirer expected");

        Clearing.Batch acked = Clearing.submit(built.id(), T0);
        assertEquals(0, new BigDecimal("992.00").compareTo(acked.issuerNet()), "what the issuer actually owes");
        assertEquals(0, new BigDecimal("980.00").compareTo(acked.net()),
                "AND THE ACQUIRER'S OWN NUMBER IS UNCHANGED: adopting theirs would erase the evidence");
        assertFalse(acked.agreed(), "the batch reports the disagreement rather than hiding it");
        System.out.println("lesson 2: acquirer 980.00 vs issuer 992.00, both kept, disagreement reported");
    }

    /**
     * LESSON 3 · AN ISSUER DOES NOT CLEAR WHAT IT NEVER AUTHORISED.
     *
     * Clearing more than was authorised is the oldest trick in the business,
     * and the defence is that the issuer checks each line against a hold it is
     * actually carrying rather than trusting the batch.
     *
     * A line it cannot match is counted as unmatched rather than rejecting the
     * whole batch: rejecting would punish a hundred good lines for one bad one,
     * and ignoring it would hide the only evidence of a dispute.
     */
    @Test
    void lesson3_a_line_the_issuer_cannot_match_is_reported_not_paid() throws Exception {
        cardSale("pi_4", "40.00");

        Clearing.Batch built = Clearing.build("minibank", "EUR", DAY, T0);
        // An acquirer CLAIMING more than it authorised: an extra line, and the
        // batch total inflated to match, which is what the dangerous version of
        // this looks like. Adding a line without inflating the claim would be
        // harmless, because the acquirer would not have asked for the money.
        UUID phantom = UUID.randomUUID();
        addLine(built.id(), phantom, "5000.00");
        inflateClaim(built.id(), "5000.00");

        Clearing.Batch acked = Clearing.submit(built.id(), T0);
        assertEquals(0, new BigDecimal("39.68").compareTo(acked.issuerNet()),
                "THE ISSUER PAID FOR THE 40.00 IT AUTHORISED AND NOTHING FOR THE 5000.00 IT DID NOT");
        assertEquals(0, new BigDecimal("5039.68").compareTo(acked.net()),
                "the acquirer's inflated claim survives, because erasing it would erase the evidence");
        assertFalse(acked.agreed(), "and the totals differ, which is how anybody finds out");
        assertEquals(1, unmatchedCount(built.id()), "the unmatched line is counted, not swallowed");
        System.out.println("lesson 3: a claim for 5039.68 cleared for 39.68, with 1 line reported unmatched");
    }

    /**
     * LESSON 4 · A BATCH RESENT IS NOT A SECOND BATCH.
     *
     * Networks retry and operators re-run things. An issuer that paid twice for
     * a resent batch would be handing over a day's takings again, which is the
     * single most expensive mistake available in clearing.
     */
    @Test
    void lesson4_resending_a_batch_does_not_clear_it_twice() throws Exception {
        cardSale("pi_5", "75.00");
        Clearing.Batch built = Clearing.build("minibank", "EUR", DAY, T0);

        Clearing.Batch first = Clearing.submit(built.id(), T0);
        BigDecimal firstNet = first.issuerNet();
        for (int i = 0; i < 4; i++) Clearing.submit(built.id(), T0);

        assertEquals(1, clearedBatches.size(), "ONE batch at the issuer, however many times it was sent");
        assertEquals(0, firstNet.compareTo(Clearing.find(built.id()).issuerNet()), "and the same answer each time");

        // and building the day again produces nothing, so the sales cannot be
        // gathered into a second batch either
        assertNull(Clearing.build("minibank", "EUR", DAY, T0),
                "the day is already cleared, so there is nothing left to gather");
        System.out.println("lesson 4: five submissions and a rebuild produced one cleared batch");
    }

    /**
     * LESSON 5 · AN UNREACHABLE ISSUER LEAVES THE BATCH UNACKNOWLEDGED.
     *
     * The honest state when a message was sent and no answer came is "we do not
     * know", and clearing is the place where that matters most: the acquirer
     * cannot tell whether the issuer has the batch. Marking it acknowledged
     * would claim an agreement that may not exist; discarding it would lose a
     * day's money.
     */
    @Test
    void lesson5_an_unreachable_issuer_leaves_the_batch_open_and_retryable() throws Exception {
        cardSale("pi_6", "90.00");
        Clearing.Batch built = Clearing.build("minibank", "EUR", DAY, T0);

        Clearing.issuerUnreachable = true;
        Clearing.Batch tried = Clearing.submit(built.id(), T0);
        assertEquals("submitted", tried.state(), "sent, and not acknowledged");
        assertNull(tried.issuerNet(), "we do not claim to know what they think");
        assertFalse(tried.agreed(), "and it is certainly not reconciled");

        Clearing.issuerUnreachable = false;
        Clearing.Batch retried = Clearing.submit(built.id(), T0);
        assertEquals("acknowledged", retried.state(), "and it completes when the issuer returns");
        assertTrue(retried.agreed());
        System.out.println("lesson 5: an unreachable issuer left the batch submitted, and a retry closed it");
    }

    /**
     * LESSON 6 · EVERY COMPLETED CARD SALE IS CLEARED OR VISIBLY WAITING.
     *
     * The completeness question, asked the way the rest of this estate asks it:
     * not "did anything go wrong" but "is there anything I cannot account for".
     * Money captured and never told to anybody is money the merchant has been
     * promised and the issuer has not been asked for.
     */
    @Test
    void lesson6_nothing_captured_is_left_unaccounted_for() throws Exception {
        cardSale("pi_7", "30.00");
        cardSale("pi_8", "20.00");
        assertEquals(0, new BigDecimal("50.00").compareTo(Clearing.uncleared("EUR")),
                "two sales, neither cleared yet, and the number says so");

        Clearing.Batch b = Clearing.build("minibank", "EUR", DAY, T0);
        Clearing.submit(b.id(), T0);
        assertEquals(0, Clearing.uncleared("EUR").signum(), "and afterwards nothing is outstanding");

        // a wallet sale is not part of clearing at all: it never left the building
        PaymentIntents.authorize("pi_wallet", new BigDecimal("15.00"), "EUR", "cust_w", MERCHANT, T0);
        PaymentIntents.capture("pi_wallet", T0);
        assertEquals(0, Clearing.uncleared("EUR").signum(),
                "a wallet payment has no issuer to clear with, and is correctly not counted as outstanding");
        System.out.println("lesson 6: card sales cleared to zero outstanding, and a wallet sale was never in scope");
    }

    // -------------------------------------------------- the stand-in issuer

    /** minibank's real interchange arithmetic, so the reconciliation compares
     *  two genuine calculations rather than one number echoed back. */
    private static void clearingHandler(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String batch = dev.minimart.core.Json.str(body, "batch");
        if (clearedBatches.containsKey(batch)) {         // a resend is the same batch
            respond(ex, 200, clearedBatches.get(batch));
            return;
        }
        List<String> refs = dev.minimart.core.Json.each(body, "authorization");
        List<String> amounts = dev.minimart.core.Json.each(body, "amount");

        BigDecimal gross = BigDecimal.ZERO, interchange = BigDecimal.ZERO;
        int matched = 0, unmatched = 0;
        for (int i = 0; i < Math.min(refs.size(), amounts.size()); i++) {
            UUID ref = UUID.fromString(refs.get(i));
            BigDecimal amount = new BigDecimal(amounts.get(i));
            BigDecimal held = holds.get(ref);
            // the issuer clears only what it is actually carrying, at the
            // amount it approved
            if (held != null && held.compareTo(amount) == 0) {
                gross = gross.add(amount);
                interchange = interchange.add(amount.multiply(ISSUER_RATE).setScale(2, RoundingMode.HALF_UP));
                matched++;
            } else {
                unmatched++;
            }
        }
        String out = "{\"reference\":\"" + batch + "\",\"gross\":\"" + gross.toPlainString()
                + "\",\"interchange\":\"" + interchange.toPlainString()
                + "\",\"net\":\"" + gross.subtract(interchange).toPlainString()
                + "\",\"matched\":" + matched + ",\"unmatched\":" + unmatched + "}";
        clearedBatches.put(batch, out);
        respond(ex, 200, out);
    }

    private static void authorizeHandler(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/capture") || path.endsWith("/void")) { respond(ex, 200, "{\"ok\":true}"); return; }
        UUID id = UUID.fromString(dev.minimart.core.Json.str(body, "authorization_id"));
        BigDecimal amount = new BigDecimal(dev.minimart.core.Json.str(body, "amount"));
        holds.put(id, amount);
        respond(ex, 200, "{\"authorization\":\"" + id + "\",\"approved\":true}");
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ------------------------------------------------------------------ helpers

    /** A completed sale on a card issued by the affiliated bank. */
    private static void cardSale(String intentId, String amount) throws Exception {
        var cus = PaymentMethods.customer(MERCHANT, "c_" + intentId);
        var pm = PaymentMethods.attachCard(cus.id(), "ON_US", "mbc_" + intentId, "minibank credit", "0000");
        assertInstanceOf(PaymentIntents.Ok.class,
                PaymentIntents.authorize(intentId, new BigDecimal(amount), "EUR",
                        "c_" + intentId, MERCHANT, pm.id(), T0));
        assertInstanceOf(PaymentIntents.Ok.class, PaymentIntents.capture(intentId, T0));
    }

    /** Slip an extra line into a built batch, as a corrupted batch would carry. */
    private static void addLine(String batchId, UUID ref, String amount) throws Exception {
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("""
                     INSERT INTO clearing_items(batch_id, payment_intent_id, authorization_ref, amount, interchange)
                     VALUES (?,?,?,?,0)""")) {
            ps.setString(1, batchId); ps.setString(2, "phantom_" + ref);
            ps.setString(3, ref.toString()); ps.setBigDecimal(4, new BigDecimal(amount));
            ps.executeUpdate();
        }
    }

    /** Inflate what the acquirer CLAIMS, keeping the schema's own arithmetic
     *  constraint satisfied, so the batch is internally consistent and simply
     *  wrong about the world. */
    private static void inflateClaim(String batchId, String extra) throws Exception {
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("""
                     UPDATE clearing_batches
                        SET gross = gross + ?::numeric, net = net + ?::numeric, item_count = item_count + 1
                      WHERE id = ?""")) {
            ps.setString(1, extra); ps.setString(2, extra); ps.setString(3, batchId);
            ps.executeUpdate();
        }
    }

    private static int unmatchedCount(String batchId) throws Exception {
        String stored = clearedBatches.get(batchId);
        return stored == null ? -1 : Integer.parseInt(dev.minimart.core.Json.str(stored, "unmatched"));
    }
}
