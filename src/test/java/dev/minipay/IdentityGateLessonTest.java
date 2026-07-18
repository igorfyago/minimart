package dev.minipay;

import com.sun.net.httpserver.HttpServer;
import dev.minipay.auth.ApiKeys;
import dev.minipay.auth.Enforcement;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE GATE LESSONS · identity is worth nothing on the one endpoint that asks.
 *
 * The seam that resolves a caller was correct and was wired into exactly one
 * handler out of nine, which is the failure mode a security seam actually has:
 * not a wrong check, an absent one. Everything below goes over a real socket,
 * because "is this handler wired" is a question only the socket answers.
 *
 * Two callers throughout: HELIX, a merchant minding its own business, and
 * NPC B, external software holding a real key of its own. Every attack here
 * is NPC B holding a VALID credential and reaching for something that is not
 * its own, which is the only interesting shape. An attacker with no
 * credential is a different lesson, and it is the enforcement one.
 */
class IdentityGateLessonTest {

    static HttpServer server;
    static final int PORT = 18300;
    static final String BASE = "http://localhost:" + PORT;
    static final HttpClient http = HttpClient.newHttpClient();

    static String npcBKey;      // "pk_...:sk_...", bound to npc-shop-b, scope charge
    static String npcBKeyId;
    static String readOnlyKey;  // bound to npc-shop-b, scope read

    @BeforeAll
    static void boot() throws Exception {
        PayDb.bootstrap();
        server = PayApi.start(PORT);
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
        System.clearProperty(Enforcement.PROPERTY);
    }

    @BeforeEach
    void reset() throws Exception {
        System.clearProperty(Enforcement.PROPERTY);
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, payment_methods, customers, "
                     + "api_keys, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
        ApiKeys.IssuedKey charge = ApiKeys.issue("npc-shop-b", "NPC B", "charge");
        npcBKey = charge.keyId() + ":" + charge.secret();
        npcBKeyId = charge.keyId();
        ApiKeys.IssuedKey read = ApiKeys.issue("npc-shop-b", "NPC B console", "read");
        readOnlyKey = read.keyId() + ":" + read.secret();
    }

    /**
     * LESSON 1 · an intent id is a name, not a capability.
     *
     * /v1/list publishes intent ids to whoever asks, which is right for a
     * console and fatal if the id is also the authorisation. Capture and
     * cancel take an id and nothing else, so the ownership question has to be
     * asked by looking the intent up. Nobody was asking it.
     */
    @Test
    void capturingAnotherMerchantsAuthorisedPaymentIsRefused() throws Exception {
        assertEquals(200, post("/v1/payment_intents", """
                {"id":"pi_helix_1","amount":"40.00","customer":"cust_helix","merchant":"helix"}""",
                "helix-1", null).statusCode());

        var stolen = post("/v1/payment_intents/pi_helix_1/capture", "{}", null, npcBKey);
        assertEquals(403, stolen.statusCode(), "NPC B's own key does not reach helix's money");

        var canceled = post("/v1/payment_intents/pi_helix_1/cancel", "{}", null, npcBKey);
        assertEquals(403, canceled.statusCode(), "nor does cancel, which is the same reach with a worse motive");

        // and the money is exactly where helix left it
        var still = get("/v1/payment_intents/pi_helix_1", null);
        assertTrue(still.body().contains("\"status\":\"requires_capture\""), still.body());
        System.out.println("lesson 1: a foreign intent id bought neither a capture nor a cancel");
    }

    /**
     * LESSON 2 · a payout names where the money lands.
     *
     * The settlement run takes a merchant and pays them. A caller who may name
     * any merchant may point somebody else's payout wherever they like.
     */
    @Test
    void settlementAndCustomerCreationStayInsideTheCallersMerchant() throws Exception {
        var payout = post("/v1/settlements/run", "{\"merchant\":\"helix\"}", null, npcBKey);
        assertEquals(403, payout.statusCode(), payout.body());

        var customer = post("/v1/customers",
                "{\"merchant\":\"helix\",\"customer_ref\":\"alice\"}", null, npcBKey);
        assertEquals(403, customer.statusCode(),
            "registering a customer UNDER helix is how every later ownership check gets defeated");
        System.out.println("lesson 2: a keyed caller could not name another merchant on the batch or customer path");
    }

    /**
     * LESSON 3 · the check you can skip by leaving a field out.
     *
     * The payment-method check was gated on a payment method being named. Omit
     * it, and the wallet path charged whatever customer the body named: the
     * exact attack the first check exists to stop, reached by sending LESS.
     */
    @Test
    void omittingThePaymentMethodDoesNotSkipTheCustomerCheck() throws Exception {
        String helixCustomer = customerId(post("/v1/customers",
                "{\"merchant\":\"helix\",\"customer_ref\":\"alice\"}", null, null));

        var theft = post("/v1/payment_intents", """
                {"amount":"99.00","customer":"%s","merchant":"npc-shop-b"}""".formatted(helixCustomer),
                "b-1", npcBKey);
        assertEquals(403, theft.statusCode(),
            "NPC B named its own merchant and helix's customer, and sent no payment_method at all");

        // the control: NPC B's own customer, same request shape, works
        String ownCustomer = customerId(post("/v1/customers",
                "{\"merchant\":\"npc-shop-b\",\"customer_ref\":\"bob\"}", null, npcBKey));
        var honest = post("/v1/payment_intents", """
                {"amount":"9.00","customer":"%s","merchant":"npc-shop-b"}""".formatted(ownCustomer),
                "b-2", npcBKey);
        assertEquals(200, honest.statusCode(), honest.body());
        System.out.println("lesson 3: an omitted payment_method no longer omits the question");
    }

    /**
     * LESSON 4 · a bad credential is not the absence of one.
     *
     * Mapping an invalid key to anonymous made revocation cosmetic: the
     * revoked key kept working, with FEWER restrictions than before it was
     * revoked, because anonymous passes every check. Permissive is a statement
     * about callers who prove nothing, never about callers who prove
     * something false.
     */
    @Test
    void anInvalidOrRevokedKeyIsRefusedEvenWhilePermissive() throws Exception {
        assertFalse(Enforcement.on(), "this lesson is about the permissive phase");

        var garbage = post("/v1/payment_intents", """
                {"amount":"1.00","customer":"c","merchant":"npc-shop-b"}""", "x-1", "pk_nope:sk_nope");
        assertEquals(401, garbage.statusCode(), garbage.body());

        ApiKeys.revoke(npcBKeyId);
        var revoked = post("/v1/payment_intents", """
                {"amount":"1.00","customer":"c","merchant":"npc-shop-b"}""", "x-2", npcBKey);
        assertEquals(401, revoked.statusCode(), "a revoked key stops working, or it was never revoked");

        // and the answer says nothing about WHICH failure it was: unknown,
        // wrong secret and revoked read identically, or this endpoint becomes
        // a way to enumerate live key ids
        assertEquals(garbage.body(), revoked.body());
        assertFalse(revoked.body().contains(npcBKeyId), revoked.body());
        System.out.println("lesson 4: a false credential was refused, and told the caller nothing");
    }

    /**
     * LESSON 5 · the permissive phase has an off switch, and it is off.
     *
     * The seeded NPC traffic and the demos hold no credentials, so activation
     * is a deployment decision rather than a deploy-time surprise. What
     * changes when it is made: a money-moving endpoint stops accepting a
     * caller who proved nothing. What does not change: reads.
     */
    @Test
    void enforcementIsOffByDefaultAndRefusesAnonymousMoneyWhenOn() throws Exception {
        String anonymous = """
                {"amount":"5.00","customer":"cust_anon","merchant":"helix"}""";

        assertEquals(200, post("/v1/payment_intents", anonymous, "off-1", null).statusCode(),
            "OFF is the behaviour that predates identity, and the demos depend on it");

        System.setProperty(Enforcement.PROPERTY, "1");
        try {
            assertTrue(Enforcement.on());
            assertEquals(401, post("/v1/payment_intents", anonymous, "on-1", null).statusCode());
            assertEquals(401, post("/v1/settlements/run", "{}", null, null).statusCode());
            assertEquals(401, post("/v1/clearing/run", "{}", null, null).statusCode());
            assertEquals(401, post("/v1/customers",
                    "{\"merchant\":\"helix\",\"customer_ref\":\"x\"}", null, null).statusCode());

            // reads stay open: enforcement is about moving money, and the
            // console is not the thing an attacker uses to move any
            assertEquals(200, get("/v1/balance?merchant=helix", null).statusCode());
            assertEquals(200, get("/v1/list", null).statusCode());

            // and a real credential still works, which is the whole point of
            // having wired the seam before switching it on
            String own = customerId(post("/v1/customers",
                    "{\"merchant\":\"npc-shop-b\",\"customer_ref\":\"bob\"}", null, npcBKey));
            assertEquals(200, post("/v1/payment_intents", """
                    {"amount":"5.00","customer":"%s","merchant":"npc-shop-b"}""".formatted(own),
                    "on-2", npcBKey).statusCode());
        } finally {
            System.clearProperty(Enforcement.PROPERTY);
        }
        System.out.println("lesson 5: enforcement off is today's behaviour, enforcement on refuses anonymous money");
    }

    /**
     * LESSON 6 · scope, or the javadoc was lying.
     *
     * 'read' was carried from the key row into the identity and consulted
     * nowhere. A restriction a merchant was told about, and that no code
     * applies, is worse than no restriction at all.
     */
    @Test
    void aReadKeyMayLookAndMayNotCharge() throws Exception {
        String own = customerId(post("/v1/customers",
                "{\"merchant\":\"npc-shop-b\",\"customer_ref\":\"bob\"}", null, npcBKey));
        String charge = """
                {"amount":"5.00","customer":"%s","merchant":"npc-shop-b"}""".formatted(own);

        assertEquals(200, post("/v1/payment_intents", charge, "r-0", npcBKey).statusCode(),
            "the 'charge' key may, so the refusal below is about scope and nothing else");

        assertEquals(403, post("/v1/payment_intents", charge, "r-1", readOnlyKey).statusCode());
        assertEquals(403, post("/v1/settlements/run", "{}", null, readOnlyKey).statusCode());
        assertEquals(403, post("/v1/clearing/run", "{}", null, readOnlyKey).statusCode());
        assertEquals(403, post("/v1/customers",
                "{\"merchant\":\"npc-shop-b\",\"customer_ref\":\"z\"}", null, readOnlyKey).statusCode());

        // and it may do the thing it is for
        assertEquals(200, get("/v1/balance?merchant=npc-shop-b", readOnlyKey).statusCode());
        assertEquals(200, get("/v1/list", readOnlyKey).statusCode());
        System.out.println("lesson 6: a read key read, and could not charge, settle, clear or register");
    }

    /**
     * LESSON 7 · a namespace made of caller-supplied text can be typed.
     *
     * The stored idempotency key carried the caller's namespace as a prefix,
     * and anonymous callers had no prefix at all. So an anonymous caller could
     * type the prefix: send "key:pk_victim:K" and land inside a real
     * merchant's namespace, where the stored response of a payment that was
     * never theirs is waiting to be replayed back to them.
     */
    @Test
    void anonymousCannotForgeItsWayIntoAKeyedNamespace() throws Exception {
        String own = customerId(post("/v1/customers",
                "{\"merchant\":\"npc-shop-b\",\"customer_ref\":\"bob\"}", null, npcBKey));
        var mine = post("/v1/payment_intents", """
                {"id":"pi_npcb_1","amount":"7.00","customer":"%s","merchant":"npc-shop-b"}""".formatted(own),
                "K", npcBKey);
        assertEquals(200, mine.statusCode(), mine.body());

        // the forgery: the victim's namespace, typed by hand, with a body that
        // is not the victim's request
        var forged = post("/v1/payment_intents", """
                {"amount":"1.00","customer":"cust_anon","merchant":"helix"}""",
                "key:" + npcBKeyId + ":K", null);

        assertNotEquals("true", forged.headers().firstValue("Idempotent-Replayed").orElse("false"),
            "THE FORGERY: an anonymous caller must never be handed a keyed caller's stored response");
        assertFalse(forged.body().contains("pi_npcb_1"), forged.body());

        // the namespace is a value in the row now, not only a prefix in a string
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("SELECT key, caller FROM idempotency_keys ORDER BY key")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("anon", rs.getString(2), "the anonymous world has a name of its own");
                assertEquals("anon:key:" + npcBKeyId + ":K", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("key:" + npcBKeyId, rs.getString(2));
            }
        }
        System.out.println("lesson 7: a hand-typed namespace landed in the anonymous world, where it belongs");
    }

    /**
     * LESSON 8 · a debugging view that publishes live key ids.
     *
     * /v1/keys selects the stored key, and the stored key begins with the
     * namespace of whoever claimed it. Returning the column raw hands every
     * reader a list of live pk_ ids and other people's idempotency keys, which
     * is how a console becomes a directory of who to impersonate.
     */
    @Test
    void theConsoleShowsACallerTheirOwnKeysAndNeverTheNamespace() throws Exception {
        String own = customerId(post("/v1/customers",
                "{\"merchant\":\"npc-shop-b\",\"customer_ref\":\"bob\"}", null, npcBKey));
        assertEquals(200, post("/v1/payment_intents", """
                {"amount":"7.00","customer":"%s","merchant":"npc-shop-b"}""".formatted(own),
                "npcb-secret-order", npcBKey).statusCode());
        assertEquals(200, post("/v1/payment_intents", """
                {"amount":"3.00","customer":"cust_anon","merchant":"helix"}""",
                "anon-order", null).statusCode());

        var anonymousView = get("/v1/keys", null);
        assertEquals(200, anonymousView.statusCode());
        assertFalse(anonymousView.body().contains(npcBKeyId),
            "A LIVE KEY ID IS NOT CONSOLE FURNITURE: " + anonymousView.body());
        assertFalse(anonymousView.body().contains("npcb-secret-order"),
            "nor is somebody else's idempotency key");
        assertTrue(anonymousView.body().contains("anon-order"), anonymousView.body());
        assertFalse(anonymousView.body().contains("\"anon:"), anonymousView.body());

        var keyedView = get("/v1/keys", npcBKey);
        assertEquals(200, keyedView.statusCode());
        assertTrue(keyedView.body().contains("npcb-secret-order"),
            "the caller still sees the half they sent, or the view has stopped being useful");
        assertFalse(keyedView.body().contains(npcBKeyId), keyedView.body());
        assertFalse(keyedView.body().contains("anon-order"), "and only their own rows");
        System.out.println("lesson 8: the console showed each caller their own half, and no namespace at all");
    }

    /**
     * LESSON 9 · listing is where an attacker goes shopping.
     *
     * Lesson 1 closed capture and cancel. This closes the catalogue: a keyed
     * caller sees its own merchant's intents, so the ids of other people's
     * money are not handed out with the credential that cannot use them.
     */
    @Test
    void aKeyedCallerListsItsOwnMerchantOnly() throws Exception {
        assertEquals(200, post("/v1/payment_intents", """
                {"id":"pi_helix_2","amount":"40.00","customer":"cust_helix","merchant":"helix"}""",
                "helix-2", null).statusCode());
        String own = customerId(post("/v1/customers",
                "{\"merchant\":\"npc-shop-b\",\"customer_ref\":\"bob\"}", null, npcBKey));
        assertEquals(200, post("/v1/payment_intents", """
                {"id":"pi_npcb_2","amount":"4.00","customer":"%s","merchant":"npc-shop-b"}""".formatted(own),
                "b-3", npcBKey).statusCode());

        var mine = get("/v1/list", npcBKey);
        assertTrue(mine.body().contains("pi_npcb_2"), mine.body());
        assertFalse(mine.body().contains("pi_helix_2"), "helix's intent ids are not NPC B's shopping list");

        // anonymous keeps today's console: no credential, no scoping, exactly
        // as before identity existed
        var all = get("/v1/list", null);
        assertTrue(all.body().contains("pi_helix_2") && all.body().contains("pi_npcb_2"), all.body());

        var foreignBalance = get("/v1/balance?merchant=helix", npcBKey);
        assertEquals(403, foreignBalance.statusCode(), "nor is helix's balance");
        System.out.println("lesson 9: a keyed caller saw its own merchant, and an anonymous console saw all");
    }

    // ------------------------------------------------------------------ http
    private static String customerId(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), r.body());
        int i = r.body().indexOf("\"id\":\"") + 6;
        return r.body().substring(i, r.body().indexOf('"', i));
    }

    private static HttpResponse<String> post(String path, String body, String idemKey, String apiKey)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        if (apiKey != null) b.header("X-Api-Key", apiKey);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, String apiKey) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(BASE + path)).GET();
        if (apiKey != null) b.header("X-Api-Key", apiKey);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
