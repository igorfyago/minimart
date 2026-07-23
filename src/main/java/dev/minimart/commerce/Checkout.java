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
 *
 * AND STEP 3 IS NOT ENOUGH ON ITS OWN.
 *
 * Compensating locally answers "what happens to the goods". It does not answer
 * "what is standing at the processor", and for a long time nothing did: the
 * result of every call over this seam was a boolean in a local variable, thrown
 * away as often as it was read. A void that failed, a capture whose local half
 * then failed, a timeout on an authorisation that in fact landed · each of them
 * strands money at one service while BOTH services' own audits pass, because
 * each ledger balances internally. That is exactly the trap the reserved-stock
 * audit was built for, one database over, and it was never applied across the
 * network.
 *
 * So every remote step now writes to RemoteSteps before it goes out and again
 * when it comes back, and Reconciler compares the two services afterwards. The
 * journal is not a retry loop and the reconciler does not heal: they make the
 * disagreement SAYABLE, which is the thing that was missing.
 */
public final class Checkout {

    public sealed interface Result permits Placed, Rejected {}
    public record Placed(UUID orderId, String paymentIntentId, BigDecimal amount) implements Result {}
    public record Rejected(String reason) implements Result {}

    /** Overridable so a test can point at a processor that is not there. */
    public static volatile String payBaseUrl =
            System.getenv().getOrDefault("MINIPAY_URL", "http://localhost:8082");

    /** Customers whose payment instrument always declines. A simulated economy
     *  needs failing cards as much as working ones: dunning, involuntary churn
     *  and recovery cannot be exercised by a population that always pays. */
    public static final java.util.Set<Long> declineCustomerIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Fault injection: make the CAPTURE fail while authorisation still works.
     *  A simulated economy needs the partial failures too, and this one matters
     *  because "authorised" and "captured" are different claims about money. */
    public static volatile boolean captureSabotage = false;

    /** Fault injection: make the VOID fail while the local abort still runs.
     *  This is the shape of the defect cancel() used to have · the goods go back
     *  on the shelf and a real authorisation stays standing at the issuer. */
    public static volatile boolean voidSabotage = false;

    /**
     * Fault injection AFTER the network, which the two above cannot reach.
     *
     * captureSabotage and voidSabotage short-circuit before the request leaves,
     * so they can only ever produce "the remote step did not happen", which is
     * the SAFE failure. The dangerous one is the opposite: the remote step
     * happened, the customer's money moved, and the local half then failed. That
     * case is unreachable without a seam here, and a failure mode no test can
     * construct is a failure mode nobody believes in.
     */
    public static volatile boolean fulfilSabotage = false;

    static String customerRef(long customerId) {
        return declineCustomerIds.contains(customerId) ? "cust_decline_" + customerId : "cust_" + customerId;
    }

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private Checkout() {}

    public static String intentIdFor(UUID orderId) { return "pi_" + orderId; }

    /** Reserve the goods, then charge. Compensate if the charge does not land. */
    public static Result place(UUID orderId, String tenant, long customerId,
                               String variantId, String location, long qty, Instant businessAt) throws SQLException {
        return placeMode(orderId, tenant, customerId, variantId, location, qty, businessAt, "psp");
    }

    /** As place, choosing the rail the money moves on. "bank_card" charges
     *  the customer's real card at the bank, "bank_main" debits their main
     *  EUR account there; every other value is the psp path the shop has
     *  always run. */
    public static Result placeMode(UUID orderId, String tenant, long customerId,
                               String variantId, String location, long qty, Instant businessAt,
                               String mode) throws SQLException {
        if ("bank_card".equals(mode) || "bank_main".equals(mode))
            return placeAtBank(orderId, tenant, customerId, variantId, location, qty, businessAt, mode);
        Orders.Result reserved = Orders.submit(orderId, tenant, customerId, variantId, location, qty, businessAt, false);
        if (reserved instanceof Orders.OutOfStock) return new Rejected("out of stock");
        if (reserved instanceof Orders.AlreadyProcessed) return new Rejected("already processed");
        if (!(reserved instanceof Orders.Ok ok)) return new Rejected("could not reserve");

        String pi = intentIdFor(orderId);
        // Written and committed BEFORE the request goes out, so that a process
        // that dies mid-call still leaves evidence that a call was made.
        RemoteSteps.begin(orderId, RemoteSteps.AUTHORIZE, pi, businessAt);
        try {
            // The idempotency key is derived from the order, so a retry of this
            // whole checkout authorises once, no matter how many times it runs.
            String body = "{\"id\":\"" + pi + "\",\"amount\":\"" + ok.amount().toPlainString() +
                          "\",\"customer\":\"" + customerRef(customerId) + "\",\"merchant\":\"" + tenant +
                          "\",\"business_at\":\"" + businessAt + "\"}";
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(payBaseUrl + "/v1/payment_intents"))
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "order:" + orderId)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                // An ANSWER, and it was no. Nothing is standing over there.
                RemoteSteps.finish(orderId, RemoteSteps.AUTHORIZE, RemoteSteps.State.FAILED,
                        "HTTP " + r.statusCode());
                Orders.abort(orderId, businessAt);       // give the goods back
                return new Rejected("payment failed: HTTP " + r.statusCode());
            }
            RemoteSteps.finish(orderId, RemoteSteps.AUTHORIZE, RemoteSteps.State.OK, null);
        } catch (Exception e) {
            // A TIMEOUT IS NOT A DECLINE.
            //
            // This catch used to treat every exception the same way, which meant
            // a read timeout on a request that DID land was filed as "no
            // authorisation exists". It does exist: minipay is holding a real
            // customer's money, minimart has just put the goods back, and
            // nothing anywhere will ever revisit it. Both ledgers balance.
            //
            // We still give the goods back, because stock cannot be held hostage
            // to a maybe. What changes is that the maybe is now WRITTEN DOWN,
            // and the reconciler is what settles it.
            boolean neverLanded = e instanceof java.net.ConnectException
                    || e instanceof java.net.http.HttpConnectTimeoutException;
            RemoteSteps.finish(orderId, RemoteSteps.AUTHORIZE,
                    neverLanded ? RemoteSteps.State.FAILED : RemoteSteps.State.UNKNOWN,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
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

    /** Where the bank answers /api/card/charge. MINIBANK_URL first; the
     *  compose's MINIBANK_ISSUER_URL names the same host and is honoured so
     *  an existing deployment needs no new variable. */
    public static volatile String bankBaseUrl =
            System.getenv().getOrDefault("MINIBANK_URL",
                    System.getenv().getOrDefault("MINIBANK_ISSUER_URL", "http://localhost:8080"));

    /**
     * THE FULL CIRCLE · reserve the goods, then charge the customer's REAL
     * card at the bank. The money never touches this shop's own ledger:
     * payment_mode "bank_card" carries stock-only legs, exactly like the psp
     * path, because the charge lives where the customer can see it — their
     * bank statement, next to everything else they bought.
     *
     * The discipline is the one this class already keeps: the call is
     * journalled BEFORE it goes out, the authorization reference is derived
     * from the order id (a retried checkout re-asks the same money), a
     * refused charge gives the goods back, and a maybe is written down as a
     * maybe — never filed as "no charge exists".
     */
    private static Result placeOnBankCard(UUID orderId, String tenant, long customerId,
                                          String variantId, String location, long qty,
                                          Instant businessAt) throws SQLException {
        return placeAtBank(orderId, tenant, customerId, variantId, location, qty, businessAt, "bank_card");
    }

    /** The two bank rails share everything but the ask: the card ride holds
     *  and captures at /api/card/charge, the main account debits the EUR
     *  statement at /api/main/charge. Same journal, same derived reference,
     *  same honesty about a maybe. */
    private static Result placeAtBank(UUID orderId, String tenant, long customerId,
                                      String variantId, String location, long qty,
                                      Instant businessAt, String mode) throws SQLException {
        Orders.Result reserved = Orders.submitMode(orderId, tenant, customerId, variantId,
                location, qty, businessAt, mode);
        if (reserved instanceof Orders.OutOfStock) return new Rejected("out of stock");
        if (reserved instanceof Orders.AlreadyProcessed) return new Rejected("already processed");
        if (!(reserved instanceof Orders.Ok ok)) return new Rejected("could not reserve");

        String ref = "mart:" + orderId;    // the bank's idempotency key, from the order
        RemoteSteps.begin(orderId, RemoteSteps.AUTHORIZE, ref, businessAt);
        String amount = ok.amount().stripTrailingZeros().toPlainString();
        String path, body;
        if ("bank_main".equals(mode)) {
            path = "/api/main/charge";
            body = "{\"customer\":" + customerId
                    + ",\"amount\":\"" + amount
                    + "\",\"reference\":\"" + UUID.nameUUIDFromBytes(ref.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + "\",\"merchant\":\"minimart\"}";
        } else {
            path = "/api/card/charge";
            body = "{\"customer\":" + customerId
                    + ",\"amount\":\"" + amount
                    + "\",\"authorization_id\":\"" + UUID.nameUUIDFromBytes(ref.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + "\",\"merchant\":\"minimart\"}";
        }
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(bankBaseUrl + path))
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200 && r.body().contains("\"charged\":true")) {
                RemoteSteps.finish(orderId, RemoteSteps.AUTHORIZE, RemoteSteps.State.OK, null);
                return new Placed(orderId, ref, ok.amount());
            }
            // an ANSWER, and it was no — declined, or the bank said 4xx
            String reason = r.body().contains("\"reason\"")
                    ? r.body().replaceAll(".*\"reason\":\"([^\"]*)\".*", "$1") : "HTTP " + r.statusCode();
            RemoteSteps.finish(orderId, RemoteSteps.AUTHORIZE, RemoteSteps.State.FAILED, reason);
            Orders.abort(orderId, businessAt);
            return new Rejected(("bank_main".equals(mode) ? "account declined: " : "card declined: ") + reason);
        } catch (Exception e) {
            // SAME RULE AS THE PSP PATH: a timeout is not a decline. The bank
            // may be holding a real hold on a real card; that is written down,
            // the goods still go back, and the reconciler settles the maybe.
            boolean neverLanded = e instanceof java.net.ConnectException
                    || e instanceof java.net.http.HttpConnectTimeoutException;
            RemoteSteps.finish(orderId, RemoteSteps.AUTHORIZE,
                    neverLanded ? RemoteSteps.State.FAILED : RemoteSteps.State.UNKNOWN,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            try { Orders.abort(orderId, businessAt); } catch (SQLException ignored) {}
            return new Rejected("bank unreachable: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Ship it: capture the money, then mark the goods sold. Both idempotent.
     *
     * THE TWO HALVES CANNOT BE ONE TRANSACTION, so the order between them is
     * chosen for which failure is survivable. Capturing first means the bad case
     * is "charged and not shipped", which is recoverable: the goods are still on
     * the shelf and the fulfil can be retried. Fulfilling first would mean
     * "shipped and not charged", which is not recoverable at all, because the
     * goods have left.
     *
     * What was missing is that the survivable case was not being SURVIVED. The
     * fulfil ran in its own transaction with nothing watching it, so a failure
     * after a successful capture left the customer paid up, the order still
     * reserved, and no record anywhere that the two disagreed.
     */
    public static boolean ship(UUID orderId, Instant businessAt) throws SQLException {
        if (!settle(orderId, RemoteSteps.CAPTURE, businessAt)) return false;
        RemoteSteps.begin(orderId, RemoteSteps.FULFIL, intentIdFor(orderId), businessAt);
        try {
            if (fulfilSabotage) throw new IllegalStateException("fulfil sabotaged after a successful capture");
            Orders.fulfil(orderId, businessAt);
        } catch (SQLException | RuntimeException e) {
            // THE MONEY IS ALREADY GONE and the goods are still here. Say so,
            // durably, and let the failure keep travelling: a caller told
            // "shipped" would be the second lie on top of the first.
            // finish() cannot throw, so the original failure is what the caller
            // sees. It is the one that says WHY the money is gone and the goods
            // are not, and it used to be replaceable by a second database error.
            RemoteSteps.finish(orderId, RemoteSteps.FULFIL, RemoteSteps.State.FAILED,
                    "captured, then the local fulfil failed: " + e.getMessage());
            throw e;
        }
        RemoteSteps.finish(orderId, RemoteSteps.FULFIL, RemoteSteps.State.OK, null);
        return true;
    }

    /**
     * Call it off: void the authorisation and put the goods back.
     *
     * THE RETURN VALUE MEANS THE VOID, not the abort. It used to be a hardcoded
     * true, with settle() called as a bare statement and its answer dropped on
     * the floor, so a void that failed was indistinguishable from one that
     * worked: the goods went back on the shelf while a real authorisation stayed
     * standing at the issuer, holding a customer's credit against an order that
     * no longer exists. Nothing swept it and nothing retried it.
     *
     * The goods still go back unconditionally, and that is deliberate · a
     * cancelled order must not keep stock hostage to the processor's
     * availability. The change is that the failure is now a fact on the record
     * rather than a value nobody looked at.
     */
    public static boolean cancel(UUID orderId, Instant businessAt) throws SQLException {
        boolean voided = settle(orderId, RemoteSteps.CANCEL, businessAt);
        Orders.abort(orderId, businessAt);
        return voided;
    }

    /**
     * Release the hold and touch nothing local. What SagaDriver calls.
     *
     * cancel() is the wrong entry point for a repair even though it looks like
     * the right one, because it also aborts the order. The driver only ever
     * voids holds for orders that are ALREADY aborted, so calling cancel() would
     * mean a sweeper re-running a state transition on rows it has no business
     * transitioning, and the day that assumption slips it would abort a live one.
     *
     * It is the same remote step, journalled by the same code, on purpose. A
     * second implementation of "void a hold at minipay" would be a second place
     * to get the timeout distinction wrong, and that distinction · a refused
     * connection is a no, anything else is a question · is the one thing this
     * seam has learned that it cannot afford to learn twice.
     */
    static boolean voidHold(UUID orderId, Instant businessAt) throws SQLException {
        return settle(orderId, RemoteSteps.CANCEL, businessAt);
    }

    private static boolean settle(UUID orderId, String action, Instant businessAt) throws SQLException {
        RemoteSteps.begin(orderId, action, intentIdFor(orderId), businessAt);
        // The sabotage seams sit AFTER the journal entry on purpose: a fault
        // that is invisible to the record is not a rehearsal of anything, since
        // the record is the thing under test.
        if ((captureSabotage && RemoteSteps.CAPTURE.equals(action))
                || (voidSabotage && RemoteSteps.CANCEL.equals(action))) {
            RemoteSteps.finish(orderId, action, RemoteSteps.State.FAILED, "sabotaged before the request left");
            return false;
        }
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(
                                    payBaseUrl + "/v1/payment_intents/" + intentIdFor(orderId) + "/" + action))
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"business_at\":\"" + businessAt + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = r.statusCode() == 200;
            RemoteSteps.finish(orderId, action, ok ? RemoteSteps.State.OK : RemoteSteps.State.FAILED,
                    ok ? null : "HTTP " + r.statusCode() + ": " + r.body());
            return ok;
        } catch (Exception e) {
            // Same distinction as the authorisation path: a connection that was
            // never established is a definite no, and anything else is a
            // question, because the request may well have been served.
            boolean neverLanded = e instanceof java.net.ConnectException
                    || e instanceof java.net.http.HttpConnectTimeoutException;
            RemoteSteps.finish(orderId, action,
                    neverLanded ? RemoteSteps.State.FAILED : RemoteSteps.State.UNKNOWN,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }
}
