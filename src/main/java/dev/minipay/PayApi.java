package dev.minipay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.concurrent.Executors;

import dev.minimart.core.Ledger;

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
        s.createContext("/v1/balance", PayApi::balance);
        s.start();
        return s;
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

        if (key != null && !key.isBlank()) {
            Idempotency.Claim claim = Idempotency.claim(key, fp);
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
                if (key != null) Idempotency.complete(key, 400, e);
                send(ex, 400, e);
                return;
            }
            String id = Json.str(body, "id");
            if (id == null || id.isBlank()) id = "pi_" + Long.toHexString(System.nanoTime());

            PaymentIntents.Result r = PaymentIntents.authorize(
                    id, new BigDecimal(amount), currency == null ? "SIMEUR" : currency,
                    customer, merchant, businessAt(body));
            int status = r instanceof PaymentIntents.Ok ? 200 : (r instanceof PaymentIntents.Declined ? 402 : 409);
            String out = render(r);
            if (key != null) Idempotency.complete(key, status, out);
            send(ex, status, out);
        } catch (Exception e) {
            if (key != null) Idempotency.release(key);   // genuine failure: let them retry for real
            throw e;
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
}
