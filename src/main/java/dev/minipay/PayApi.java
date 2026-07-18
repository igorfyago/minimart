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
import dev.minipay.auth.Enforcement;

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

    /**
     * Recent payment intents, for the console.
     *
     * A keyed caller sees its own merchant and no further. This list is where
     * an attacker would go shopping for intent ids to capture, and a console
     * that answers "here is everyone's" is a directory of other people's
     * money whatever the endpoints behind it check.
     */
    private static void list(HttpExchange ex) throws IOException {
        try {
            CallerIdentity identity = identityOf(ex, Act.READ);
            if (identity == null) return;
            String mine = identity.apiKey().map(CallerIdentity.BoundMerchant::merchant).orElse(null);
            listRows(ex, mine);
        } catch (Exception e) {
            send(ex, 500, err(String.valueOf(e.getMessage())));
        }
    }

    private static void listRows(HttpExchange ex, String merchant) throws IOException {
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("""
                     SELECT id, amount, currency, customer_ref, merchant_ref, status, business_at
                     FROM payment_intents WHERE (? IS NULL OR merchant_ref = ?)
                     ORDER BY business_at DESC, id LIMIT 25""")) {
            ps.setString(1, merchant);
            ps.setString(2, merchant);
            var rs = ps.executeQuery();
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

    /**
     * The idempotency ledger itself: which keys were seen, and what they
     * returned. A debugging view of the caller's OWN recent activity.
     *
     * THE NAMESPACE PREFIX NEVER LEAVES THIS SERVICE. The stored key begins
     * with the namespace of whoever claimed it, so returning the column raw
     * publishes live pk_ key ids to anybody who can reach the console, which
     * is how a debugging view turns into a directory of who to impersonate.
     * The caller already knows the half they sent, and that is the half they
     * get back.
     */
    private static void keys(HttpExchange ex) throws IOException {
        try {
            CallerIdentity identity = identityOf(ex, Act.READ);
            if (identity == null) return;
            String namespace = identity.idempotencyNamespace();
            try (Connection c = PayDb.open();
                 var ps = c.prepareStatement("""
                         SELECT key, caller, state, status_code, substr(fingerprint, 1, 12), created_at
                         FROM idempotency_keys WHERE caller = ?
                         ORDER BY created_at DESC LIMIT 20""")) {
                ps.setString(1, namespace);
                var rs = ps.executeQuery();
                StringBuilder b = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"key\":\"").append(Json.esc(visibleKey(rs.getString(1), rs.getString(2))))
                     .append("\",\"state\":\"").append(rs.getString(3))
                     .append("\",\"status\":").append(rs.getInt(4))
                     .append(",\"fingerprint\":\"").append(Json.esc(rs.getString(5)))
                     .append("\",\"at\":\"").append(rs.getTimestamp(6).toInstant()).append("\"}");
                }
                send(ex, 200, b.append(']').toString());
            }
        } catch (Exception e) {
            send(ex, 500, err(String.valueOf(e.getMessage())));
        }
    }

    /** The caller-facing half of a stored key: their own string, never the
     *  namespace in front of it. The second branch covers a row written
     *  before the namespace had a column of its own. */
    static String visibleKey(String key, String caller) {
        if (key == null) return null;
        if (caller != null && key.startsWith(caller + ":")) return key.substring(caller.length() + 1);
        if (key.startsWith("key:")) {
            int afterKeyId = key.indexOf(':', "key:".length());
            if (afterKeyId > 0) return key.substring(afterKeyId + 1);
        }
        return key;
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
                if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, err("GET only")); return; }
                CallerIdentity reader = identityOf(ex, Act.READ);
                if (reader == null) return;
                if (!owns(reader, merchantOfIntent(id))) {
                    send(ex, 403, err("this payment_intent belongs to another merchant"));
                    return;
                }
                send(ex, 200, render(PaymentIntents.get(id)));
                return;
            }
            String action = parts[1];
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, err("POST only")); return; }

            // CAPTURE AND CANCEL TAKE AN ID AND NOTHING ELSE, which is why
            // ownership has to be looked up rather than read off the request:
            // there is nothing in the request to read it off. An intent id is
            // a name, not a capability, and /v1/list hands names out.
            CallerIdentity identity = identityOf(ex, Act.WRITE);
            if (identity == null) return;
            if (!owns(identity, merchantOfIntent(id))) {
                send(ex, 403, err("this payment_intent belongs to another merchant"));
                return;
            }

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

        // WHO IS CALLING · the acquirer's two-path question. An API key in
        // X-Api-Key binds a merchant; a Bearer token will bind an estate
        // customer once the SSO adapter lands.
        CallerIdentity identity = identityOf(ex, Act.WRITE);
        if (identity == null) return;

        // Every caller's idempotency namespace is its own, the anonymous one
        // included: two callers sending the same key string must never replay
        // each other's payments, and a namespace nobody can type by hand is
        // the only kind that survives an attacker who read the schema.
        String caller = identity.idempotencyNamespace();

        if (key != null && !key.isBlank()) {
            Idempotency.Claim claim = Idempotency.claim(scoped(key, identity), caller, fp);
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
                if (key != null) Idempotency.complete(scoped(key, identity), 400, e);
                send(ex, 400, e);
                return;
            }

            // THE DEPUTY CHECK. An API-key caller IS its merchant: a body
            // naming another merchant is not a request with extra
            // information, it is an attack with extra steps. merchantFor
            // returns empty on contradiction, and contradiction is the one
            // thing even the permissive phase refuses.
            var acting = identity.merchantFor(merchant);
            if (acting.isEmpty()) {
                String e = foreignMerchant(identity);
                if (key != null) Idempotency.complete(scoped(key, identity), 403, e);
                send(ex, 403, e);
                return;
            }
            merchant = acting.get();

            // THE SECOND DEPUTY CHECK. You never charge a customer by naming
            // them; you charge a pm_ attached for the pair. If the body names
            // a payment method, it must live under the acting merchant.
            //
            // AND IF IT NAMES NONE, THE QUESTION IS STILL OWED. Gating this on
            // the presence of a payment_method made the check optional to the
            // attacker: omit the field, and the wallet path charges whatever
            // customer the body names, which is the entire attack the first
            // check exists to stop, reached by leaving something out. A keyed
            // caller may charge only customers registered under its own
            // merchant, and a customer this processor has never heard of
            // belongs to nobody, so it is not theirs to charge either.
            String pmId = Json.str(body, "payment_method");
            String pmOwner, pmCustomer;
            if (pmId != null) {
                PaymentMethods.Method pm = PaymentMethods.find(pmId);
                if (pm == null) { pmOwner = null; pmCustomer = null; }
                else { pmCustomer = pm.customerId(); pmOwner = merchantOf(pmCustomer); }
            } else {
                pmCustomer = customer;
                pmOwner = merchantOf(customer);
            }
            boolean checkable = pmId == null || pmCustomer != null;
            if (checkable && !identity.mayUsePaymentMethod(pmOwner, pmCustomer)) {
                String e = pmId != null
                        ? err("this payment method is not attached under merchant '" + merchant + "'")
                        : err("customer '" + customer + "' is not registered under merchant '" + merchant + "'");
                if (key != null) Idempotency.complete(scoped(key, identity), 403, e);
                send(ex, 403, e);
                return;
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
            if (key != null) Idempotency.complete(scoped(key, identity), status, out);
            send(ex, status, out);
        } catch (Exception e) {
            if (key != null) Idempotency.release(scoped(key, identity));   // genuine failure: let them retry for real
            throw e;
        }
    }

    /** What a request is asking to do. READ looks, WRITE moves money. */
    private enum Act { READ, WRITE }

    /** The outcome of asking who is calling: an identity, or the refusal to serve. */
    private sealed interface Auth permits Allowed, Refused {}
    private record Allowed(CallerIdentity identity) implements Auth {}
    private record Refused(int status, String message) implements Auth {}

    /**
     * WHO IS CALLING · asked in exactly one place.
     *
     * Every handler that takes a merchant, a customer or an intent id comes
     * through here, because nine contexts each deciding for themselves is how
     * a service ends up with one endpoint that checks and eight that do not.
     * The HTTP server has no Filter doing this for us, so the discipline is
     * that the first line of a handler is this call.
     *
     * Today only the API-key path is live (it needs no external dependency:
     * the key table is ours). The Bearer path lands with the SSO adapter,
     * AudienceAuth from sso-client, and will bind the estate customer through
     * sso_accounts.
     *
     * THREE CASES, AND ONLY THE MIDDLE ONE DEPENDS ON ACTIVATION:
     *
     *   a key that validates   the caller is its merchant, in both phases,
     *                          and its scope decides whether it may write.
     *   no credential at all   anonymous while permissive, 401 on a
     *                          money-moving endpoint once enforced.
     *   a credential that does
     *   not validate           401 in both phases. Downgrading a bad key to
     *                          anonymous would mean a revoked key keeps
     *                          working, quietly, with fewer restrictions
     *                          than it had before it was revoked.
     */
    private static Auth authenticate(HttpExchange ex, Act act) throws SQLException {
        String authorization = ex.getRequestHeaders().getFirst("Authorization");
        String apiKeyHeader = ex.getRequestHeaders().getFirst("X-Api-Key");

        // Two credentials are two contradictory answers, and guessing which to
        // believe would be worse than asking. The one rejection the permissive
        // phase was always allowed to make.
        if (CallerIdentity.isAmbiguous(authorization, apiKeyHeader)) {
            return new Refused(400, "send an Authorization token OR an X-Api-Key, not both: "
                    + "minipay will not guess who is calling");
        }

        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            var valid = ApiKeys.validate(apiKeyHeader);
            if (valid.isEmpty()) {
                // UNKNOWN, WRONG SECRET AND REVOKED ARE ONE ANSWER. Telling a
                // caller which of the three they hit turns this endpoint into
                // an oracle for enumerating live key ids.
                return new Refused(401, "invalid API key");
            }
            CallerIdentity identity = CallerIdentity.ofApiKey(
                    valid.get().merchant(), valid.get().keyId(), valid.get().scope());
            if (act == Act.WRITE && !identity.mayWrite()) {
                return new Refused(403, "this API key's scope is '" + valid.get().scope()
                        + "', which may read but may not move money");
            }
            return new Allowed(identity);
        }

        if (act == Act.WRITE && Enforcement.on()) {
            return new Refused(401, "this endpoint needs an API key");
        }
        return new Allowed(CallerIdentity.ANONYMOUS);
    }

    /** Resolve, or answer the refusal and return null. Null means answered. */
    private static CallerIdentity identityOf(HttpExchange ex, Act act) throws SQLException, IOException {
        Auth a = authenticate(ex, act);
        if (a instanceof Refused r) { send(ex, r.status(), err(r.message())); return null; }
        return ((Allowed) a).identity();
    }

    /**
     * The merchant this request acts for, or null when the body's claim
     * contradicts the credential. fallback covers the batch endpoints, which
     * have a default merchant and a caller who may name none.
     */
    private static String actingMerchant(CallerIdentity identity, String named, String fallback) {
        if (named == null || named.isBlank()) {
            return identity.apiKey().map(CallerIdentity.BoundMerchant::merchant).orElse(fallback);
        }
        return identity.merchantFor(named).orElse(null);
    }

    private static String foreignMerchant(CallerIdentity identity) {
        return err("the API key is bound to merchant '" + identity.apiKey().get().merchant()
                + "', and a request naming another merchant is not this caller's request");
    }

    /** Idempotency keys live in the caller's own namespace, always. */
    private static String scoped(String key, CallerIdentity identity) {
        return identity.scopedIdempotencyKey(key);
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

    /**
     * Which merchant owns a pi_... record.
     *
     * Capture and cancel take an intent id and nothing else, so this lookup is
     * the only thing standing between a caller and somebody else's authorised
     * money. /v1/list publishes intent ids by design, which is fine for a
     * console and fatal if the id is also the authorisation.
     */
    private static String merchantOfIntent(String intentId) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT merchant_ref FROM payment_intents WHERE id = ?")) {
            ps.setString(1, intentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * May this caller act on this intent. An unknown id is not refused here:
     * it belongs to nobody, so the money path answers 404 and this check does
     * not become a way to probe which ids exist.
     */
    private static boolean owns(CallerIdentity identity, String intentOwner) {
        return intentOwner == null || identity.merchantFor(intentOwner).isPresent();
    }

    private static void balance(HttpExchange ex) throws IOException {
        try {
            String q = ex.getRequestURI().getQuery();
            String merchant = null;
            if (q != null) for (String p : q.split("&")) if (p.startsWith("merchant=")) merchant = p.substring(9);
            if (merchant == null) { send(ex, 400, err("need ?merchant=")); return; }
            CallerIdentity identity = identityOf(ex, Act.READ);
            if (identity == null) return;
            // A balance is the merchant's own business, and a key names its
            // merchant. 'read' scope is allowed here: this is what it is for.
            if (identity.merchantFor(merchant).isEmpty()) {
                send(ex, 403, foreignMerchant(identity));
                return;
            }
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


    /**
     * Build the day's clearing batch and send it to the issuer.
     *
     * There is no ownership check here because there is nothing to own: a
     * clearing batch is per issuer and per currency, across every merchant,
     * and an operator job is what it is. What this endpoint does owe is the
     * money-moving gate, so a 'read' key cannot run one and, once enforced,
     * neither can a caller with no credential at all.
     */
    private static void clearingRun(HttpExchange ex) throws IOException {
        try {
            CallerIdentity identity = identityOf(ex, Act.WRITE);
            if (identity == null) return;
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
            CallerIdentity identity = identityOf(ex, Act.WRITE);
            if (identity == null) return;
            String body = read(ex);
            java.time.LocalDate day = java.time.LocalDate.parse(
                    orElse(Json.str(body, "business_date"), java.time.LocalDate.now().toString()));
            // A payout goes to a merchant's own account, so naming the merchant
            // is naming where the money lands.
            String merchant = actingMerchant(identity, Json.str(body, "merchant"), "helix");
            if (merchant == null) { send(ex, 403, foreignMerchant(identity)); return; }
            Settlements.Batch b = Settlements.run(merchant,
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
            CallerIdentity identity = identityOf(ex, Act.WRITE);
            if (identity == null) return;
            // Registering a customer UNDER a merchant is the moment ownership
            // is decided, and every later check reads it back. A caller who
            // may name any merchant here has defeated all of them at once.
            var acting = identity.merchantFor(merchant);
            if (acting.isEmpty()) { send(ex, 403, foreignMerchant(identity)); return; }
            merchant = acting.get();
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
            CallerIdentity identity = identityOf(ex, Act.WRITE);
            if (identity == null) return;
            // Attaching an instrument to a stranger's customer is how you make
            // a pm_ that later passes the deputy check on the charge path.
            if (!identity.mayUsePaymentMethod(merchantOf(customer), customer)) {
                send(ex, 403, err("customer '" + customer + "' is not registered under this caller's merchant"));
                return;
            }

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
