package dev.minipay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ONE API IN FRONT OF SEVERAL RAILS.
 *
 * This is the difference between a processor and a merchant's wallet with good
 * manners. A merchant integrates once, sends a payment method, and never learns
 * whether the money came from a card issued by an affiliated bank, a card
 * issued by a stranger, or a balance held here. Each reaches a completely
 * different place.
 *
 * The affiliated case has a real name. ON-US is what an acquirer does when it
 * happens to have issued the card: the authorisation goes straight to that
 * issuer and never touches a network, which is genuinely cheaper and faster.
 * The discipline that keeps it honest is that ON-US MEANS SKIPPING THE NETWORK,
 * NOT SKIPPING THE BOUNDARY. minipay calls the affiliated bank over HTTP exactly
 * as it would call a stranger, and lesson 1 proves it by answering as a
 * stranger would.
 *
 * The issuer here is a stand-in speaking minibank's real protocol, so these
 * lessons run without the bank installed. The genuine end-to-end against the
 * live bank is asserted separately, because a test that needs four services
 * running is a test that quietly stops running.
 */
class RailsLessonTest {

    static HttpServer issuer;
    static final int ISSUER_PORT = 18210;
    static final Instant T0 = Instant.parse("2027-06-01T00:00:00Z");
    static final String MERCHANT = "helix";

    /** The stand-in issuer's books: a credit limit, and what is held against it. */
    static final ConcurrentHashMap<String, BigDecimal> limits = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> authState = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, BigDecimal> authAmount = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> authToken = new ConcurrentHashMap<>();
    static final AtomicInteger issuerCalls = new AtomicInteger();

    @BeforeAll
    static void boot() throws Exception {
        PayDb.bootstrap();
        issuer = HttpServer.create(new InetSocketAddress(ISSUER_PORT), 0);
        issuer.createContext("/issuer/v1/authorizations", RailsLessonTest::issuerHandler);
        issuer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        issuer.start();
        Rails.issuerBaseUrl = "http://localhost:" + ISSUER_PORT;
    }

    @AfterAll
    static void stop() { if (issuer != null) issuer.stop(0); }

    @BeforeEach
    void reset() throws Exception {
        Rails.issuerUnreachable = false;
        limits.clear(); authState.clear(); authAmount.clear(); authToken.clear();
        issuerCalls.set(0);
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE payment_methods, customers, idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
    }

    /**
     * LESSON 1 · AN AFFILIATED BANK IS STILL A DIFFERENT COMPANY.
     *
     * The temptation with an on-us card is enormous: the bank is ours, the
     * database is right there, and a query would be faster than an HTTP call.
     * Taking it would turn two services into one service wearing a costume, and
     * nothing would ever surface the mistake, because everything would work.
     *
     * So this asserts the boundary directly: the issuer was CALLED, over a
     * socket, and its answer decided the outcome. The stand-in issuer here
     * knows nothing about minipay and minipay knows nothing about its books.
     */
    @Test
    void lesson1_an_on_us_card_is_authorised_by_asking_the_issuer_over_http() throws Exception {
        String token = "mbc_igor_card";
        limits.put(token, new BigDecimal("1000.00"));

        var cus = PaymentMethods.customer(MERCHANT, "igor");
        var pm = PaymentMethods.attachCard(cus.id(), "ON_US", token, "minibank credit", "4476");

        String intent = "pi_" + UUID.randomUUID();
        var r = PaymentIntents.authorize(intent, new BigDecimal("79.00"), "EUR",
                "igor", MERCHANT, pm.id(), T0);

        assertInstanceOf(PaymentIntents.Ok.class, r, "authorised: " + r);
        assertEquals(1, issuerCalls.get(), "THE ISSUER WAS ASKED, over a socket, exactly once");
        assertEquals("approved", authState.values().iterator().next());
        assertEquals(0, new BigDecimal("921.00").compareTo(remaining(token)),
                "and the customer's real credit limit moved at the bank, not here");

        // and the processor recorded WHICH rail ran, without telling the merchant
        assertEquals("ON_US", railOf(intent));
        System.out.println("lesson 1: an on-us card authorised by HTTP to the issuer, limit now " + remaining(token));
    }

    /**
     * LESSON 2 · A STRANGER'S CARD BUYS FROM US TOO.
     *
     * The case that makes an ecosystem an ecosystem rather than a closed loop.
     * A customer whose bank has nothing to do with us can still buy, because
     * the processor knows how to reach an issuer it does not own and cannot
     * inspect.
     *
     * The declines are driven by the INSTRUMENT, the way every processor's
     * sandbox does it, because that is the only way to test a decline you do
     * not control: a test cannot depend on a stranger's bank being in a
     * particular mood.
     */
    @Test
    void lesson2_an_externally_issued_card_is_accepted_and_can_be_declined() throws Exception {
        var cus = PaymentMethods.customer(MERCHANT, "stranger-1");
        var good = PaymentMethods.attachCard(cus.id(), "EXTERNAL", "ext_ok_4242", "visa", "4242");
        var bad = PaymentMethods.attachCard(cus.id(), "EXTERNAL", "ext_declined_0002", "visa", "0002");

        var ok = PaymentIntents.authorize("pi_" + UUID.randomUUID(), new BigDecimal("50.00"),
                "EUR", "stranger-1", MERCHANT, good.id(), T0);
        assertInstanceOf(PaymentIntents.Ok.class, ok, "a foreign card is perfectly welcome");
        assertEquals(0, issuerCalls.get(), "and our affiliated bank was never involved");

        String declinedIntent = "pi_" + UUID.randomUUID();
        var no = PaymentIntents.authorize(declinedIntent, new BigDecimal("50.00"),
                "EUR", "stranger-1", MERCHANT, bad.id(), T0);
        assertInstanceOf(PaymentIntents.Declined.class, no);
        assertEquals("card declined", ((PaymentIntents.Declined) no).reason());

        // the decline is WRITTEN DOWN, because "why could my customer not pay"
        // is the only question a merchant asks afterwards
        assertEquals("declined", statusOf(declinedIntent));
        assertEquals("card declined", declineReasonOf(declinedIntent));
        System.out.println("lesson 2: an external card bought, another declined with a reason, and it was recorded");
    }

    /**
     * LESSON 3 · A DECLINED CARD LEAVES NOTHING BEHIND.
     *
     * The failure that would be invisible: recording the money first and asking
     * the issuer afterwards. Every decline would then need unwinding, and in
     * the window between the two this processor would believe it held money no
     * bank had agreed to.
     *
     * The issuer is asked FIRST, so a decline leaves no hold, no ledger entry
     * and no balance anywhere.
     */
    @Test
    void lesson3_a_decline_moves_no_money_anywhere() throws Exception {
        String token = "mbc_broke_card";
        limits.put(token, new BigDecimal("10.00"));

        var cus = PaymentMethods.customer(MERCHANT, "skint");
        var pm = PaymentMethods.attachCard(cus.id(), "ON_US", token, "minibank credit", "0001");

        var r = PaymentIntents.authorize("pi_" + UUID.randomUUID(), new BigDecimal("500.00"),
                "EUR", "skint", MERCHANT, pm.id(), T0);

        assertInstanceOf(PaymentIntents.Declined.class, r);
        assertEquals("insufficient credit", ((PaymentIntents.Declined) r).reason(),
                "the issuer's own words, passed through");
        assertEquals(0, new BigDecimal("10.00").compareTo(remaining(token)),
                "the customer's limit is untouched");
        assertEquals(0, entryCount(), "AND NOT ONE LEDGER ENTRY EXISTS: nothing to unwind");
        System.out.println("lesson 3: a decline left no hold, no entry and no balance");
    }

    /**
     * LESSON 4 · THE CAPTURE REACHES THE ISSUER BEFORE THE BOOKS SAY IT IS DONE.
     *
     * A capture recorded here that the issuer never performed is money this
     * processor believes it has and no bank has released, and the merchant
     * would eventually be paid out of a balance that does not exist. So the
     * issuer is told first, and if it cannot be told, nothing here changes.
     */
    @Test
    void lesson4_a_capture_the_issuer_never_saw_does_not_happen_here_either() throws Exception {
        String token = "mbc_shipping_card";
        limits.put(token, new BigDecimal("1000.00"));
        var cus = PaymentMethods.customer(MERCHANT, "buyer");
        var pm = PaymentMethods.attachCard(cus.id(), "ON_US", token, "minibank credit", "9999");

        String intent = "pi_" + UUID.randomUUID();
        assertInstanceOf(PaymentIntents.Ok.class,
                PaymentIntents.authorize(intent, new BigDecimal("120.00"), "EUR", "buyer", MERCHANT, pm.id(), T0));

        // the bank goes away between authorisation and shipment
        Rails.issuerUnreachable = true;
        var blocked = PaymentIntents.capture(intent, T0);
        assertInstanceOf(PaymentIntents.Declined.class, blocked, "the capture is refused, not assumed");
        assertEquals("requires_capture", statusOf(intent),
                "and the intent is UNCHANGED, so it can be captured properly later");

        // the bank comes back
        Rails.issuerUnreachable = false;
        assertInstanceOf(PaymentIntents.Ok.class, PaymentIntents.capture(intent, T0));
        assertEquals("succeeded", statusOf(intent));
        assertEquals("captured", authState.values().stream().findFirst().orElse("none"),
                "the issuer performed the capture, which is what makes the money real");
        System.out.println("lesson 4: an uncapturable payment stayed capturable, and completed when the bank returned");
    }

    /**
     * LESSON 5 · AN UNREACHABLE AFFILIATED BANK IS A DECLINE, NOT AN APPROVAL.
     *
     * The most tempting shortcut in the whole design. The bank is ours, the
     * customer is probably good for it, and declining a sale feels like the
     * cautious choice being expensive. It is still the right answer: an
     * approval nobody authorised is an amount the issuer never agreed to and
     * may refuse to honour, and the merchant has already shipped.
     *
     * Standing in for an issuer is a real practice with real rules, including a
     * reconciler for the holds it invents. Until that exists, unreachable means
     * declined, and saying so plainly is better than a shortcut nobody wrote
     * down.
     */
    @Test
    void lesson5_an_unreachable_issuer_declines_rather_than_guessing() throws Exception {
        String token = "mbc_fine_card";
        limits.put(token, new BigDecimal("5000.00"));
        var cus = PaymentMethods.customer(MERCHANT, "unlucky");
        var pm = PaymentMethods.attachCard(cus.id(), "ON_US", token, "minibank credit", "1111");

        Rails.issuerUnreachable = true;
        String intent = "pi_" + UUID.randomUUID();
        var r = PaymentIntents.authorize(intent, new BigDecimal("20.00"), "EUR", "unlucky", MERCHANT, pm.id(), T0);

        assertInstanceOf(PaymentIntents.Declined.class, r);
        assertEquals("issuer unavailable", ((PaymentIntents.Declined) r).reason(),
                "and the reason says so, rather than blaming the customer's card");
        assertEquals(0, entryCount(), "nothing was moved on a guess");
        assertEquals("declined", statusOf(intent), "and it is recorded, so the outage is visible afterwards");
        System.out.println("lesson 5: the bank was unreachable, so the sale was declined and nothing was invented");
    }

    /**
     * LESSON 6 · A RETRY REACHES THE ISSUER AS THE SAME AUTHORISATION.
     *
     * Networks retry. A processor that produced a fresh authorisation for each
     * retry would place two holds against one customer for one purchase, and
     * they would discover it at the next till.
     *
     * The authorisation reference is derived from the payment intent, so the
     * issuer sees the same one every time and answers identically.
     */
    @Test
    void lesson6_retrying_a_payment_holds_the_customers_money_once() throws Exception {
        String token = "mbc_retry_card";
        limits.put(token, new BigDecimal("1000.00"));
        var cus = PaymentMethods.customer(MERCHANT, "retrier");
        var pm = PaymentMethods.attachCard(cus.id(), "ON_US", token, "minibank credit", "2222");

        String intent = "pi_" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            var r = PaymentIntents.authorize(intent, new BigDecimal("300.00"), "EUR", "retrier", MERCHANT, pm.id(), T0);
            assertTrue(r instanceof PaymentIntents.Ok, "the same answer every time: " + r);
        }

        assertEquals(1, authState.size(), "ONE authorisation at the issuer, not four");
        assertEquals(0, new BigDecimal("700.00").compareTo(remaining(token)),
                "and 300 held once, not 1200 held four times");
        System.out.println("lesson 6: four attempts at one payment left exactly one hold of 300");
    }


    /**
     * LESSON 7 · A RETRIED PAYMENT NEVER STRANDS A HOLD.
     *
     * Found by review, and it was the most expensive bug in the payment path.
     * The first version asked the rail BEFORE checking whether it already knew
     * this payment. A customer whose first attempt declined and who tried again
     * caused a REAL hold against their real credit limit, then the acquirer's
     * insert conflicted, the transaction rolled back, and the authorisation
     * reference was thrown away. Nothing afterwards could capture or void a
     * reference nobody had stored, so the credit was consumed permanently by a
     * purchase that never existed and the customer would have found out at the
     * next till.
     *
     * Two things fix it and both are asserted here: the common case is answered
     * without troubling an issuer at all, and a declined payment is reported as
     * declined rather than as a success.
     */
    @Test
    void lesson7_a_retried_payment_leaves_no_hold_nobody_can_release() throws Exception {
        String token = "mbc_retry_safe";
        limits.put(token, new BigDecimal("1000.00"));
        var cus = PaymentMethods.customer(MERCHANT, "retry-safe");
        var pm = PaymentMethods.attachCard(cus.id(), "ON_US", token, "minibank credit", "3333");

        // the first attempt fails because the bank is unreachable
        Rails.issuerUnreachable = true;
        String intent = "pi_" + UUID.randomUUID();
        assertInstanceOf(PaymentIntents.Declined.class,
                PaymentIntents.authorize(intent, new BigDecimal("100.00"), "EUR", "retry-safe", MERCHANT, pm.id(), T0));

        // the bank comes back and the customer tries the same order again
        Rails.issuerUnreachable = false;
        int callsBefore = issuerCalls.get();
        var again = PaymentIntents.authorize(intent, new BigDecimal("100.00"), "EUR", "retry-safe", MERCHANT, pm.id(), T0);

        assertInstanceOf(PaymentIntents.Declined.class, again,
                "A DECLINED PAYMENT REPORTS DECLINED, not success. Reporting it as Ok would have the merchant ship.");
        assertEquals(callsBefore, issuerCalls.get(),
                "and the issuer was not troubled again, so no second hold could be created");
        assertEquals(0, authState.size(), "NO HOLD EXISTS ANYWHERE for a payment that never succeeded");
        assertEquals(0, new BigDecimal("1000.00").compareTo(remaining(token)),
                "the customer's credit limit is exactly as it was");
        System.out.println("lesson 7: a declined payment retried left no hold and reported declined");
    }

    /**
     * LESSON 8 · THE SAME REFERENCE MUST MEAN THE SAME MONEY.
     *
     * The issuer treats a repeated authorisation reference as a retry and
     * answers identically. That is right, and it becomes dangerous if the
     * amount is not checked: a reference reused for a larger sum was approved
     * against the smaller hold that actually existed, and the difference was
     * authorised by nobody.
     */
    @Test
    void lesson8_a_reference_reused_for_a_different_amount_is_refused() throws Exception {
        String token = "mbc_amount_check";
        limits.put(token, new BigDecimal("1000.00"));
        UUID ref = UUID.randomUUID();

        // this test speaks to the stand-in issuer directly, because the acquirer
        // derives its references and would never reuse one this way. The issuer
        // must still refuse it: a bank does not rely on its callers behaving.
        assertTrue(authorizeDirect(ref, token, "100.00"), "the first authorisation for 100");
        assertFalse(authorizeDirect(ref, token, "500.00"),
                "THE SAME REFERENCE FOR 500 IS REFUSED, or 400 would be authorised by nobody");
        assertEquals(0, new BigDecimal("900.00").compareTo(remaining(token)),
                "and only the original 100 is held");
        System.out.println("lesson 8: a reference reused for a larger amount was refused by the issuer");
    }

    // ------------------------------------------------------- the stand-in bank

    /** minibank's real protocol, answered by a stand-in, so these lessons do
     *  not need the bank installed to prove the processor's behaviour. */
    private static void issuerHandler(HttpExchange ex) throws IOException {
        issuerCalls.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (path.endsWith("/capture") || path.endsWith("/void")) {
            String id = path.substring(path.lastIndexOf("/authorizations/") + 16);
            id = id.substring(0, id.indexOf('/'));
            boolean capturing = path.endsWith("/capture");
            String state = authState.get(id);
            if (!"approved".equals(state) && !(capturing ? "captured" : "voided").equals(state)) {
                respond(ex, 409, "{\"error\":\"wrong state\"}");
                return;
            }
            if ("approved".equals(state)) {
                authState.put(id, capturing ? "captured" : "voided");
                if (!capturing) {
                    // a void gives the limit back
                    String tok = authToken.get(id);
                    limits.merge(tok, authAmount.get(id), BigDecimal::add);
                }
            }
            respond(ex, 200, "{\"authorization\":\"" + id + "\",\"ok\":true}");
            return;
        }

        String token = Json.str(body, "instrument");
        String authId = Json.str(body, "authorization_id");
        BigDecimal amount = new BigDecimal(Json.str(body, "amount"));

        if (authState.containsKey(authId)) {
            // A RETRY IS THE SAME AUTHORISATION, AND ONLY IF IT IS THE SAME
            // MONEY. The first version of this stand-in answered from the state
            // alone, which is exactly the bug the real issuer had, so the fake
            // agreed with the mistake and hid it. A stand-in that shares the
            // code under test's assumptions is not a test.
            BigDecimal held = authAmount.get(authId);
            if (held == null || held.compareTo(amount) != 0) {
                respond(ex, 200, "{\"approved\":false,\"reason\":\"authorization reference reused for a different amount\"}");
                return;
            }
            String state = authState.get(authId);
            boolean live = "approved".equals(state) || "captured".equals(state);
            respond(ex, 200, "{\"authorization\":\"" + authId + "\",\"approved\":" + live + "}");
            return;
        }
        BigDecimal left = limits.get(token);
        if (left == null) { respond(ex, 200, "{\"approved\":false,\"reason\":\"instrument not usable\"}"); return; }
        if (left.compareTo(amount) < 0) { respond(ex, 200, "{\"approved\":false,\"reason\":\"insufficient credit\"}"); return; }

        limits.put(token, left.subtract(amount));
        authState.put(authId, "approved");
        authAmount.put(authId, amount);
        authToken.put(authId, token);
        respond(ex, 200, "{\"authorization\":\"" + authId + "\",\"approved\":true}");
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ------------------------------------------------------------------ reads

    /** Speak to the issuer directly, as a second acquirer would. */
    private static boolean authorizeDirect(UUID ref, String token, String amount) throws Exception {
        var body = "{\"instrument\":\"" + token + "\",\"authorization_id\":\"" + ref
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"business_at\":\"" + T0 + "\"}";
        var http = java.net.http.HttpClient.newHttpClient();
        var r = http.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + ISSUER_PORT + "/issuer/v1/authorizations"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        return r.body().contains("\"approved\":true");
    }

    private static BigDecimal remaining(String token) { return limits.getOrDefault(token, BigDecimal.ZERO); }

    private static String statusOf(String intent) throws Exception { return one("status", intent); }
    private static String railOf(String intent) throws Exception { return one("rail", intent); }
    private static String declineReasonOf(String intent) throws Exception { return one("decline_reason", intent); }

    private static String one(String column, String intent) throws Exception {
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("SELECT " + column + " FROM payment_intents WHERE id = ?")) {
            ps.setString(1, intent);
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static long entryCount() throws Exception {
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("SELECT COUNT(*) FROM entries");
             var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
    }
}
