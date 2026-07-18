package dev.minipay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.Executors;

import dev.minimart.core.Ledger;
import dev.minipay.auth.ApiKeys;
import dev.minipay.auth.CallerIdentity;

/**
 * minipay's HTTP surface, deliberately Stripe-shaped.
 *
 *   POST /v1/payment_intents              (Idempotency-Key: ...)
 *   POST /v1/payment_intents/{id}/capture
 *   POST /v1/payment_intents/{id}/cancel
 *   GET  /v1/payment_intents/{id}
 *   GET  /v1/balance?merchant=...
 *
 * The network between this and the merchant is the point. Idempotency keys and
 * retries only mean anything when a response can be lost in flight, and it is
 * that lost response this service is built to survive.
 */
public final class PayApi {

    private PayApi() {}

    public static HttpServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/v1/payment_intents", PayApi::intents);
        // The processor's customer and payment-method surface, shaped after the
        // API a merchant has almost certainly integrated before: a Customer
        // holds PaymentMethods, and a PaymentIntent charges one of them. A
        // familiar shape is not decoration, it is the difference between an
        // integration that takes an afternoon and one that takes a week.
        s.createContext("/v1/customers", PayApi::customers);
        s.createContext("/v1/payment_methods", PayApi::paymentMethods);
        // The two batch jobs, exposed so they can be run on a schedule or by
        // hand. Both are idempotent per business day, so an operator running one
        // twice is not a way to pay somebody twice.
        s.createContext("/v1/clearing/run", PayApi::clearingRun);
        s.createContext("/v1/settlements/run", PayApi::settlementRun);
        s.createContext("/v1/balance", PayApi::balance);
        s.createContext("/v1/list", PayApi::list);
        s.createContext("/v1/keys", PayApi::keys);
        s.createContext("/", PayApi::staticFile);
        s.start();
        return s;
    }

    private static void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.isEmpty()) path = "/pay.html";
        try (var in = PayApi.class.getResourceAsStream("/web" + path)) {
            if (in == null) { send(ex, 404, err("not found")); return; }
            byte[] b = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type",
                    path.endsWith(".css") ? "text/css" : "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        }
    }

    /** Recent payment intents, for the console. */
    private static void list(HttpExchange ex) throws IOException {
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("""
                     SELECT id, amount, currency, customer_ref, merchant_ref, status, business_at
                     FROM payment_intents ORDER BY business_at DESC, id LIMIT 25""");
             var rs = ps.executeQuery()) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":\"").append(Json.esc(rs.getString(1)))
                 .append("\",\"amount\":\"").append(PaymentIntents.money(rs.getBigDecimal(2)))
                 .append("\",\"currency\":\"").append(Json.esc(rs.getString(3)))
                 .append("\",\"customer\":\"").append(Json.esc(rs.getString(4)))
                 .append("\",\"merchant\":\"").append(Json.esc(rs.getString(5)))
                 .append("\",\"status\":\"").append(rs.getString(6))
                 .append("\",\"at\":\"").append(rs.getTimestamp(7).toInstant()).append("\"}");
            }
            send(ex, 200, b.append(']').toString());
        } catch (Exception e) {
            send(ex, 500, err(String.valueOf(e.getMessage())));
        }
    }

    /** The idempotency ledger itself: which keys were seen, and what they returned. */
    private static void keys(HttpExchange ex) throws IOException {
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("""
                     SELECT key, state, status_code, substr(fingerprint, 1, 12), created_at
                     FROM idempotency_keys ORDER BY created_at DESC LIMIT 20""");
             var rs = ps.executeQuery()) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"key\":\"").append(Json.esc(rs.getString(1)))
                 .append("\",\"state\":\"").append(rs.getString(2))
                 .append("\",\"status\":").append(rs.getInt(3))
                 .append(",\"fingerprint\":\"").append(Json.esc(rs.getString(4)))
                 .append("\",\"at\":\"").append(rs.getTimestamp(5).toInstant()).append("\"}");
            }
            send(ex, 200, b.append(']').toString());
        } catch (Exception e) {
            send(ex, 500, err(String.valueOf(e.getMessage())));
        }
    }

    private static void intents(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();       // /v1/payment_intents[/{id}[/action]]
            String rest = path.substring("/v1/payment_intents".length());
            if (rest.startsWith("/")) rest = rest.substring(1);

            if (rest.isEmpty()) {
                if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, err("POST only")); return; }
                create(ex);
                return;
            }
            String[] parts = rest.split("/");
            String id = parts[0];
            if (parts.length == 1) {
                Instant now = Instant.now();
                if ("GET".equals(ex.getRequestMethod())) { send(ex, 200, render(PaymentIntents.get(id))); return; }
                send(ex, 405, err("GET only"));
                return;
            }
            String action = parts[1];
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, err("POST only")); return; }
            String body = read(ex);
            Instant at = businessAt(body);
            PaymentIntents.Result r = switch (action) {
                case "capture" -> PaymentIntents.capture(id, at);
                case "cancel"  -> PaymentIntents.cancel(id, at);
                default -> null;
            };
            if (r == null) { send(ex, 404, err("no such action: " + action)); return; }
            send(ex, r instanceof PaymentIntents.Ok ? 200 : 409, render(r));
        } catch (Exception e) {
            try { send(ex, 500, err(String.valueOf(e.getMessage()))); } catch (IOException ignored) {}
        }
    }

    /** Create + authorise, behind the idempotency layer. */
    private static void create(HttpExchange ex) throws Exception {
        String body = read(ex);
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        String fp = Idempotency.fingerprint(body);

        // WHO IS CALLING — the acquirer's two-path question. An API key in
        // X-Api-Key binds a merchant; a Bearer token will bind an estate
        // customer once the SSO adapter lands. Both at once is not "extra
        // authenticated" — it is two contradictory answers, and guessing
        // which to believe would be worse than asking. That 400 is the one
        // rejection the permissive phase is allowed to make.
        String authorization = ex.getRequestHeaders().getFirst("Authorization");
        String apiKeyHeader = ex.getRequestHeaders().getFirst("X-Api-Key");
        if (CallerIdentity.isAmbiguous(authorization, apiKeyHeader)) {
            send(ex, 400, err("send an Authorization token OR an X-Api-Key, not both — minipay will not guess who is calling"));
            return;
        }
        CallerIdentity identity = resolveIdentity(apiKeyHeader);

        // A keyed caller's idempotency namespace is its own: two NPCs sending
        // the same key string must never replay each other's payments.
        String caller = identity.apiKey().map(k -> "key:" + k.keyId()).orElse(null);

        if (key != null && !key.isBlank()) {
            Idempotency.Claim claim = Idempotency.claim(scoped(key, caller), fp);
            if (claim instanceof Idempotency.Replay r) {
                // the whole point: the caller gets the identical answer twice
                ex.getResponseHeaders().set("Idempotent-Replayed", "true");
                send(ex, r.stored().status(), r.stored().body());
                return;
            }
            if (claim instanceof Idempotency.Conflict) {
                send(ex, 422, err("this Idempotency-Key was already used with a different request"));
                return;
            }
            if (claim instanceof Idempotency.InFlight) {
                send(ex, 409, err("a request with this Idempotency-Key is still in flight"));
                return;
            }
        }

        try {
            String amount = Json.str(body, "amount");
            String customer = Json.str(body, "customer");
            String merchant = Json.str(body, "merchant");
            String currency = Json.str(body, "currency");
            if (amount == null || customer == null || merchant == null) {
                String e = err("need amount, customer, merchant");
                if (key != null) Idempotency.complete(scoped(key, caller), 400, e);
                send(ex, 400, e);
                return;
            }

            // THE DEPUTY CHECK. An API-key caller IS its merchant: a body
            // naming another merchant is not a request with extra
            // information, it is an attack with extra steps. merchantFor
            // returns empty on contradiction — and contradiction is the one
            // thing even the permissive phase refuses.
            var acting = identity.merchantFor(merchant);
            if (acting.isEmpty()) {
                String e = err("the API key is bound to merchant '"
                    + identity.apiKey().get().merchant()
                    + "' — a body naming another merchant is not this caller's request");
                if (key != null) Idempotency.complete(scoped(key, caller), 403, e);
                send(ex, 403, e);
                return;
            }
            merchant = acting.get();

            // THE SECOND DEPUTY CHECK. You never charge a customer by naming
            // them; you charge a pm_ attached for the pair. If the body names
            // a payment method, it must live under the acting merchant.
            String pmId = Json.str(body, "payment_method");
            if (pmId != null) {
                PaymentMethods.Method pm = PaymentMethods.find(pmId);
                if (pm != null && !identity.mayUsePaymentMethod(merchantOf(pm.customerId()), pm.customerId())) {
                    String e = err("this payment method is not attached under merchant '" + merchant + "'");
                    if (key != null) Idempotency.complete(scoped(key, caller), 403, e);
                    send(ex, 403, e);
                    return;
                }
            }

            String id = Json.str(body, "id");
            if (id == null || id.isBlank()) id = "pi_" + Long.toHexString(System.nanoTime());

            // A payment method is OPTIONAL, and its absence is the older
            // behaviour rather than an error: a caller that has not adopted
            // payment methods yet still gets a wallet payment settled here.
            // Requiring it would have broken every existing integration on the
            // day the rails arrived, which is not how a processor may treat the
            // merchants already using it.
            PaymentIntents.Result r = PaymentIntents.authorize(
                    id, new BigDecimal(amount), currency == null ? "SIMEUR" : currency,
                    customer, merchant, pmId, businessAt(body));
            int status = r instanceof PaymentIntents.Ok ? 200 : (r instanceof PaymentIntents.Declined ? 402 : 409);
            String out = render(r);
            if (key != null) Idempotency.complete(scoped(key, caller), status, out);
            send(ex, status, out);
        } catch (Exception e) {
            if (key != null) Idempotency.release(scoped(key, caller));   // genuine failure: let them retry for real
            throw e;
        }
    }

    /**
     * Resolve the caller. Today only the API-key path is live (it needs no
     * external dependency: the key table is ours). The Bearer path lands
     * with the SSO adapter — AudienceAuth from sso-client, the day that
     * artifact resolves here — and will bind the estate customer through
     * sso_accounts. Anonymous stays the default until activation.
     */
    private static CallerIdentity resolveIdentity(String apiKeyHeader) throws SQLException {
        if (apiKeyHeader == null || apiKeyHeader.isBlank()) return CallerIdentity.ANONYMOUS;
        return ApiKeys.validate(apiKeyHeader)
                .map(k -> CallerIdentity.ofApiKey(k.merchant(), k.keyId(), k.scope()))
                .orElse(CallerIdentity.ANONYMOUS); // a bad key is no key, permissively — activation changes this
    }

    /** Idempotency keys live in the caller's own namespace. */
    private static String scoped(String key, String caller) {
        return caller == null ? key : caller + ":" + key;
    }

    /** Which merchant owns a cus_... record. Small lookup, big boundary. */
    private static String merchantOf(String customerId) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT merchant FROM customers WHERE id = ?")) {
            ps.setString(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void balance(HttpExchange ex) throws IOException {
        try {
            String q = ex.getRequestURI().getQuery();
            String merchant = null;
            if (q != null) for (String p : q.split("&")) if (p.startsWith("merchant=")) merchant = p.substring(9);
            if (merchant == null) { send(ex, 400, err("need ?merchant=")); return; }
            try (Connection c = PayDb.open()) {
                BigDecimal held = safe(c, PaymentIntents.holds(merchant));
                BigDecimal avail = safe(c, PaymentIntents.balance(merchant));
                send(ex, 200, "{\"merchant\":\"" + Json.esc(merchant) + "\",\"pending\":\"" +
                        PaymentIntents.money(held) + "\",\"available\":\"" + PaymentIntents.money(avail) + "\"}");
            }
        } catch (Exception e) {
            send(ex, 500, err(String.valueOf(e.getMessage())));
        }
    }

    private static BigDecimal safe(Connection c, String ref) {
        try { return Ledger.balance(c, ref); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static Instant businessAt(String body) {
        String at = Json.str(body, "business_at");
        try { return at == null ? Instant.now() : Instant.parse(at); }
        catch (Exception e) { return Instant.now(); }
    }

    private static String render(PaymentIntents.Result r) {
        if (r instanceof PaymentIntents.Ok ok) return PaymentIntents.toJson(ok);
        if (r instanceof PaymentIntents.NotFound nf) return err("no such payment_intent: " + nf.id());
        if (r instanceof PaymentIntents.WrongState ws) return err("payment_intent is " + ws.status());
        if (r instanceof PaymentIntents.Declined d) return err("declined: " + d.reason());
        return err("unknown");
    }

    private static String err(String m) { return "{\"error\":\"" + Json.esc(m) + "\"}"; }

    private static String read(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }


    /** Build the day's clearing batch and send it to the issuer. */
    private static void clearingRun(HttpExchange ex) throws IOException {
        try {
            String body = read(ex);
            java.time.LocalDate day = java.time.LocalDate.parse(
                    orElse(Json.str(body, "business_date"), java.time.LocalDate.now().toString()));
            String currency = orElse(Json.str(body, "currency"), "EUR");
            java.time.Instant at = businessAt(body);

            Clearing.Batch built = Clearing.build(orElse(Json.str(body, "issuer"), "minibank"), currency, day, at);
            if (built == null) { send(ex, 200, "{\"cleared\":false,\"reason\":\"nothing to clear\"}"); return; }
            Clearing.Batch acked = Clearing.submit(built.id(), at);
            send(ex, 200, "{\"batch\":\"" + acked.id() + "\",\"state\":\"" + acked.state()
                    + "\",\"items\":" + acked.items()
                    + ",\"gross\":\"" + acked.gross().toPlainString()
                    + "\",\"interchange\":\"" + acked.interchange().toPlainString()
                    + "\",\"net\":\"" + acked.net().toPlainString()
                    + "\",\"issuer_net\":" + (acked.issuerNet() == null ? "null"
                        : "\"" + acked.issuerNet().toPlainString() + "\"")
                    + ",\"agreed\":" + acked.agreed() + "}");
        } catch (Exception e) { send(ex, 500, err(String.valueOf(e.getMessage()))); }
    }

    /** Pay a merchant for a business day, net of this processor's fee. */
    private static void settlementRun(HttpExchange ex) throws IOException {
        try {
            String body = read(ex);
            java.time.LocalDate day = java.time.LocalDate.parse(
                    orElse(Json.str(body, "business_date"), java.time.LocalDate.now().toString()));
            Settlements.Batch b = Settlements.run(orElse(Json.str(body, "merchant"), "helix"),
                    orElse(Json.str(body, "currency"), "EUR"), day, businessAt(body));
            if (b == null) { send(ex, 200, "{\"settled\":false,\"reason\":\"nothing to settle\"}"); return; }
            // MONEY LEAVES THIS API AT TWO DECIMAL PLACES, ALWAYS. The ledger
            // stores more precision than a currency has, which is right for
            // arithmetic and wrong for an answer: "150.00000000" in a payout
            // tells a merchant their integration is talking to something that
            // does not know what a euro is.
            send(ex, 200, "{\"settlement\":\"" + b.id() + "\",\"items\":" + b.items()
                    + ",\"gross\":\"" + money(b.gross())
                    + "\",\"fee\":\"" + money(b.fee())
                    + "\",\"net\":\"" + money(b.net()) + "\"}");
        } catch (Exception e) { send(ex, 500, err(String.valueOf(e.getMessage()))); }
    }

    /** Find or create the processor's customer for a merchant's own reference. */
    private static void customers(HttpExchange ex) throws IOException {
        try {
            String body = read(ex);
            String merchant = Json.str(body, "merchant"), ref = Json.str(body, "customer_ref");
            if (merchant == null || ref == null) { send(ex, 400, "{\"error\":\"need merchant, customer_ref\"}"); return; }
            PaymentMethods.Customer c = PaymentMethods.customer(merchant, ref);
            send(ex, 200, "{\"id\":\"" + c.id() + "\",\"merchant_ref\":\"" + Json.esc(c.merchantRef()) + "\"}");
        } catch (Exception e) { send(ex, 500, err(String.valueOf(e.getMessage()))); }
    }

    /**
     * Attach a way of paying.
     *
     * The RAIL is fixed here, once, from where the instrument came from. A rail
     * inferred later from the shape of a token would be a guess, and a guess
     * about which bank to ask works until somebody changes a token format.
     */
    private static void paymentMethods(HttpExchange ex) throws IOException {
        try {
            String body = read(ex);
            String customer = Json.str(body, "customer");
            String type = Json.str(body, "type");
            if (customer == null) { send(ex, 400, "{\"error\":\"need customer\"}"); return; }

            PaymentMethods.Method m;
            if ("wallet".equals(type)) {
                m = PaymentMethods.attachWallet(customer);
            } else {
                String rail = Json.str(body, "rail");
                String instrument = Json.str(body, "instrument");
                if (rail == null || instrument == null) {
                    send(ex, 400, "{\"error\":\"a card needs rail and instrument\"}");
                    return;
                }
                m = PaymentMethods.attachCard(customer, rail, instrument,
                        orElse(Json.str(body, "brand_label"), "card"),
                        orElse(Json.str(body, "last4"), "0000"));
            }
            send(ex, 200, "{\"id\":\"" + m.id() + "\",\"type\":\"" + m.type()
                    + "\",\"rail\":\"" + m.rail()
                    + "\",\"brand_label\":\"" + Json.esc(String.valueOf(m.brandLabel()))
                    + "\",\"last4\":\"" + Json.esc(String.valueOf(m.last4())) + "\"}");
        } catch (Exception e) { send(ex, 500, err(String.valueOf(e.getMessage()))); }
    }

    /** A currency has two decimal places. The ledger keeps more because
     *  arithmetic needs it; an answer must not. */
    private static String money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String orElse(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }

}
