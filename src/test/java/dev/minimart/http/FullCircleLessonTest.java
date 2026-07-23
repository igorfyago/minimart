package dev.minimart.http;

import com.sun.net.httpserver.HttpServer;
import dev.b4rruf3t.sso.client.AudienceAuth;
import dev.minimart.commerce.Checkout;
import dev.minimart.core.Db;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE WHOLE CIRCLE, ONE TOKEN'S JOURNEY · end to end at the seams.
 *
 * Not a stub in sight on the crypto: a REAL RS256 key pair is generated, a
 * REAL token is signed the way auth.b4rruf3t.com signs it, and the mart's
 * own AudienceAuth validates it against that key. From there the journey is
 * the one a shopper's browser drives:
 *
 *   signed-in as "Igor"  →  mart validates the token (audience mart)
 *                        →  bank's /api/whois names customer 10
 *                        →  /api/whoami answers 10
 *                        →  checkout rides the bank_card rail
 *                        →  the bank's /api/card/charge is asked for the money
 *                        →  the shop's own books carry NOT ONE money leg
 *
 * The bank is an HTTP double of its two routes — the lesson is what travels
 * BETWEEN the services, which is exactly what broke in production: the
 * token never arrived, and the sale went through anonymous on the psp rail.
 */
class FullCircleLessonTest {

    static final String ISSUER = "https://auth.b4rruf3t.com";
    static final String AUD = "mart.b4rruf3t.com";
    static final String KID = "test-key";
    static final KeyPair PAIR = generateKeyPair();

    static HttpServer bank, mart;
    static int martPort;
    static volatile String lastChargeBody;
    static final java.util.concurrent.atomic.AtomicInteger charges = new java.util.concurrent.atomic.AtomicInteger();
    static final HttpClient http = HttpClient.newHttpClient();

    static final String TENANT = "helix", LOC = "MAD", VARIANT = "v-mots-10mg";
    static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");

    @BeforeAll
    static void boot() throws Exception {
        Migrate.run();
        // the bank double: its phonebook and its till, over real HTTP
        bank = HttpServer.create(new InetSocketAddress(0), 0);
        bank.createContext("/api/whois", ex -> {
            String q = ex.getRequestURI().getQuery();
            String name = q != null && q.startsWith("name=") ? q.substring(5) : "";
            answer(ex, 200, "igor".equalsIgnoreCase(name) ? "{\"customer\":10}" : "{\"customer\":null}");
        });
        bank.createContext("/api/card/charge", ex -> {
            charges.incrementAndGet();
            lastChargeBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            answer(ex, 200, "{\"charged\":true,\"authorization\":\"a1\",\"last4\":\"4242\"}");
        });
        bank.start();
        String bankUrl = "http://localhost:" + bank.getAddress().getPort();
        EstateIdentity.bankBaseUrl = bankUrl;
        Checkout.bankBaseUrl = bankUrl;

        // the mart's REAL identity adapter, validating against our key
        AudienceAuth auth = new AudienceAuth(ISSUER, AUD, kid -> (RSAPublicKey) PAIR.getPublic());
        MartApi.identity(header -> auth.authenticate(header).flatMap(EstateIdentity::customerForTest));
        mart = MartApi.start(0);
        martPort = mart.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (bank != null) bank.stop(0);
        if (mart != null) mart.stop(0);
        MartApi.identity(CallerIdentity.ANONYMOUS);
    }

    @BeforeEach
    void reset() throws Exception {
        charges.set(0);
        lastChargeBody = null;
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE reservations, orders, entries, transactions, accounts, variants, tenants, remote_steps RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','MOTS-c 10mg', 40.00)");
        }
        dev.minimart.commerce.Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
    }

    @Test
    void aSignedInShopperPaysOnTheirRealCard() throws Exception {
        // THE CIRCLE CLOSES. One token in, a card charge out — and the person
        // it lands on is the bank's customer 10, not a minted number.
        String jwt = token("usr_igor", "Igor");

        // 1. the mart's whoami names the bank customer
        var who = get("/api/whoami", jwt);
        assertEquals(200, who.statusCode());
        assertTrue(who.body().contains("\"customer\":10"),
                "the token's person IS bank customer 10 · got " + who.body());

        // 2. checkout with that token — and a body that LIES about who we are,
        //    because the token must win (the IDOR rule) — rides the card rail
        UUID orderId = UUID.randomUUID();
        var bought = post("/api/checkout", jwt,
                "{\"orderId\":\"" + orderId + "\",\"tenant\":\"" + TENANT + "\",\"customer\":\"9999\","
                        + "\"variant\":\"" + VARIANT + "\",\"location\":\"" + LOC + "\",\"qty\":\"2\"}");
        assertEquals(200, bought.statusCode(), "the sale went through · " + bought.body());

        assertEquals(1, charges.get(), "exactly one charge reached the bank");
        assertTrue(lastChargeBody.contains("\"customer\":10"),
                "the bank charged the TOKEN's customer, not the body's 9999 · " + lastChargeBody);
        assertTrue(lastChargeBody.contains("\"amount\":\"80\""), "the full order amount moved");

        // 3. the shop's books: stock held, and NOT ONE money leg
        try (Connection c = Db.open()) {
            assertEquals(2, Ledger.balance(c, dev.minimart.commerce.Orders.reserved(LOC, VARIANT)).intValueExact());
            assertEquals(0, count(c, "SELECT COUNT(*) FROM accounts WHERE ref = 'holds:" + TENANT + "'"),
                    "card money never touches the shop's own ledger");
        }
        System.out.println("circle: token → whoami(10) → checkout(bank_card) → charged(10, €80) · the bank sees it");
    }

    @Test
    void anonymousStillPaysThroughTheProcessor() throws Exception {
        // THE DEMO IS UNTOUCHED. No token: the sale rides psp, the bank is
        // never asked, and the shop behaves exactly as it always has.
        UUID orderId = UUID.randomUUID();
        // no psp is running in this test, so an unreachable processor is the
        // honest answer — what matters is that the BANK was not the one asked
        var r = post("/api/checkout", null,
                "{\"orderId\":\"" + orderId + "\",\"tenant\":\"" + TENANT + "\",\"customer\":\"7001\","
                        + "\"variant\":\"" + VARIANT + "\",\"qty\":\"1\"}");
        assertEquals(0, charges.get(), "anonymous never reaches the card rail");
        System.out.println("circle: anonymous → psp, bank untouched · " + r.body());
    }

    @Test
    void aTokenForNobodyShopsAnonymously() throws Exception {
        // A signed-in estate user the bank has never heard of: the whois
        // answers null, the identity resolves to empty, the sale is anonymous.
        String jwt = token("usr_stranger", "Nobody");
        var who = get("/api/whoami", jwt);
        assertEquals(200, who.statusCode());
        assertTrue(who.body().contains("\"customer\":null"),
                "a stranger is null, not an error and not somebody else · " + who.body());
    }

    // ---------------------------------------------------------------- helpers

    private static HttpResponse<String> get(String path, String jwt) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + martPort + path)).GET();
        if (jwt != null) b.header("Authorization", "Bearer " + jwt);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String jwt, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + martPort + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (jwt != null) b.header("Authorization", "Bearer " + jwt);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A token signed the way the estate's issuer signs it: RS256, the real
     *  claims the mart reads (sub + name), the mart's audience. */
    private static String token(String sub, String name) {
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KID + "\"}");
        String payload = b64("{\"sub\":\"" + sub + "\",\"iss\":\"" + ISSUER + "\","
                + "\"exp\":" + (Instant.now().getEpochSecond() + 3600)
                + ",\"aud\":[\"" + AUD + "\"],\"name\":\"" + name + "\"}");
        try {
            String input = header + "." + payload;
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(PAIR.getPrivate());
            s.update(input.getBytes(StandardCharsets.UTF_8));
            return input + "." + b64(s.sign());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String b64(String s) { return b64(s.getBytes(StandardCharsets.UTF_8)); }
    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void answer(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, body.length());
        try (OutputStream os = ex.getResponseBody()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
    }

    private static int count(Connection c, String sql) throws Exception {
        try (var rs = c.createStatement().executeQuery(sql)) { rs.next(); return rs.getInt(1); }
    }
}
