package dev.minimart.commerce;

import dev.minimart.core.Db;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * CHECKOUT · where the store stops being a bank.
 *
 * Two systems must agree: stock lives here, money lives in minipay, and there
 * is a network in between. No transaction can span them, so this is a small
 * saga:
 *
 *   1. reserve the goods locally (one ACID commit)
 *   2. authorise the money at the processor (one HTTP call, idempotency key)
 *   3. if step 2 fails for ANY reason, including the processor being
 *      unreachable, release the goods
 *
 * Step 3 is the whole lesson. A store that reserves stock and then loses the
 * network has to give the stock back, or it slowly starves its own warehouse
 * with phantom holds.
 */
public final class Checkout {

    public sealed interface Result permits Placed, Rejected {}
    public record Placed(UUID orderId, String paymentIntentId, BigDecimal amount) implements Result {}
    public record Rejected(String reason) implements Result {}

    /** Overridable so a test can point at a processor that is not there. */
    public static volatile String payBaseUrl =
            System.getenv().getOrDefault("MINIPAY_URL", "http://localhost:8082");

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private Checkout() {}

    public static String intentIdFor(UUID orderId) { return "pi_" + orderId; }

    /** Reserve the goods, then charge. Compensate if the charge does not land. */
    public static Result place(UUID orderId, String tenant, long customerId,
                               String variantId, String location, long qty, Instant businessAt) throws SQLException {
        Orders.Result reserved = Orders.submit(orderId, tenant, customerId, variantId, location, qty, businessAt, false);
        if (reserved instanceof Orders.OutOfStock) return new Rejected("out of stock");
        if (reserved instanceof Orders.AlreadyProcessed) return new Rejected("already processed");
        if (!(reserved instanceof Orders.Ok ok)) return new Rejected("could not reserve");

        String pi = intentIdFor(orderId);
        try {
            // The idempotency key is derived from the order, so a retry of this
            // whole checkout authorises once, no matter how many times it runs.
            String body = "{\"id\":\"" + pi + "\",\"amount\":\"" + ok.amount().toPlainString() +
                          "\",\"customer\":\"cust_" + customerId + "\",\"merchant\":\"" + tenant +
                          "\",\"business_at\":\"" + businessAt + "\"}";
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(payBaseUrl + "/v1/payment_intents"))
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "order:" + orderId)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                Orders.abort(orderId, businessAt);       // give the goods back
                return new Rejected("payment failed: HTTP " + r.statusCode());
            }
        } catch (Exception e) {
            // the processor is unreachable, or the response was lost. Either way
            // the customer is not getting these units held hostage.
            try { Orders.abort(orderId, businessAt); } catch (SQLException ignored) {}
            return new Rejected("payment unreachable: " + e.getClass().getSimpleName());
        }

        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE orders SET payment_intent_id = ? WHERE id = ?")) {
            ps.setString(1, pi);
            ps.setObject(2, orderId);
            ps.executeUpdate();
        }
        return new Placed(orderId, pi, ok.amount());
    }

    /** Ship it: capture the money, then mark the goods sold. Both idempotent. */
    public static boolean ship(UUID orderId, Instant businessAt) throws SQLException {
        if (!settle(orderId, "capture", businessAt)) return false;
        Orders.fulfil(orderId, businessAt);
        return true;
    }

    /** Call it off: void the authorisation and put the goods back. */
    public static boolean cancel(UUID orderId, Instant businessAt) throws SQLException {
        settle(orderId, "cancel", businessAt);
        Orders.abort(orderId, businessAt);
        return true;
    }

    private static boolean settle(UUID orderId, String action, Instant businessAt) {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(
                                    payBaseUrl + "/v1/payment_intents/" + intentIdFor(orderId) + "/" + action))
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"business_at\":\"" + businessAt + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
