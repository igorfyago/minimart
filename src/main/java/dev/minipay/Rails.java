package dev.minipay;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * ONE API IN FRONT OF SEVERAL RAILS.
 *
 * This is what separates a processor from a merchant's wallet. A merchant
 * integrates once, sends a payment method, and never learns whether the money
 * came from a card issued by an affiliated bank, a card issued by a stranger,
 * or a balance held here. Each of those reaches a completely different place.
 *
 * ON_US is a real term and a real optimisation. When the acquirer and the
 * issuer belong to the same group, the authorisation goes straight to the
 * issuer and never touches a card network, which is cheaper and faster. The
 * discipline that keeps it honest: ON-US MEANS SKIPPING THE NETWORK, NOT
 * SKIPPING THE BOUNDARY. minipay calls minibank over HTTP exactly as it would
 * call a stranger, holds no credentials for its database, and gets back
 * approved or declined and nothing else. The moment on-us becomes a database
 * read, this stops being two services and becomes one service in a costume.
 *
 * EXTERNAL is the case that makes an ecosystem an ecosystem. A customer whose
 * bank is nothing to do with us can still buy, because the processor knows how
 * to reach an issuer it does not own. Here that issuer is simulated, and the
 * simulation follows the industry's own convention: specific test instruments
 * always approve, and specific ones always decline for a stated reason, which
 * is how every processor's sandbox behaves because it is the only way to test
 * a decline you do not control.
 */
public final class Rails {

    /** What any rail answers. Never more than this: a merchant learns whether
     *  it worked and, if not, something it can act on. */
    public record Outcome(boolean approved, String authorizationRef, String declineReason) {
        public static Outcome approved(String ref) { return new Outcome(true, ref, null); }
        public static Outcome declined(String why) { return new Outcome(false, null, why); }
    }

    /** Where minibank's issuer lives. Configuration, never a compiled-in host. */
    public static volatile String issuerBaseUrl =
            System.getenv().getOrDefault("MINIBANK_ISSUER_URL", "http://localhost:8080");

    /** Test seam: the affiliated issuer being unreachable, which is a case a
     *  processor must handle rather than hope about. */
    public static volatile boolean issuerUnreachable = false;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private Rails() {}

    /**
     * Authorise on whichever rail this instrument belongs to.
     *
     * The authorisation reference is DERIVED from the payment intent, so a
     * processor that retries, or a merchant that retries through it, produces
     * the same authorisation at the issuer rather than a second hold against
     * the customer's limit. The idempotency of the whole chain rests on this
     * one line being derived rather than random.
     */
    public static Outcome authorize(String rail, String instrument, String paymentIntentId,
                                    BigDecimal amount, String currency, Instant businessAt) {
        UUID authRef = UUID.nameUUIDFromBytes(("auth:" + paymentIntentId).getBytes(StandardCharsets.UTF_8));
        return switch (rail) {
            case "ON_US" -> onUs(authRef, instrument, amount, currency, businessAt);
            case "EXTERNAL" -> external(authRef, instrument, amount);
            case "WALLET" -> Outcome.approved(null);   // settled in this processor's own ledger
            default -> Outcome.declined("unsupported rail: " + rail);
        };
    }

    public static boolean capture(String rail, String authorizationRef, Instant businessAt) {
        if (!"ON_US".equals(rail) || authorizationRef == null) return true;
        return issuerCall("/issuer/v1/authorizations/" + authorizationRef + "/capture", "{}", businessAt) != null;
    }

    public static boolean release(String rail, String authorizationRef, Instant businessAt) {
        if (!"ON_US".equals(rail) || authorizationRef == null) return true;
        return issuerCall("/issuer/v1/authorizations/" + authorizationRef + "/void", "{}", businessAt) != null;
    }

    // ------------------------------------------------------------------ on us

    private static Outcome onUs(UUID authRef, String instrument, BigDecimal amount,
                                String currency, Instant businessAt) {
        String body = "{\"instrument\":\"" + Json.esc(instrument)
                + "\",\"authorization_id\":\"" + authRef
                + "\",\"amount\":\"" + amount.toPlainString()
                + "\",\"currency\":\"" + Json.esc(currency)
                + "\",\"business_at\":\"" + businessAt + "\"}";
        String response = issuerCall("/issuer/v1/authorizations", body, businessAt);
        if (response == null) {
            // THE AFFILIATED ISSUER IS UNREACHABLE, AND THAT IS A DECLINE.
            //
            // It is tempting to approve, because the bank is ours and the money
            // is probably there. That is exactly the temptation to resist: an
            // approval nobody authorised is an amount the issuer never agreed
            // to and may not honour. Standing in for an issuer is a real
            // practice with real rules, and it needs a reconciler for the holds
            // it creates. Until that exists, unreachable means declined.
            return Outcome.declined("issuer unavailable");
        }
        // AN APPROVAL IS A FIELD THE ISSUER SET, NEVER A BYTE SEQUENCE FOUND
        // SOMEWHERE IN ITS ANSWER.
        //
        // This was a contains() on the raw body, which is the whole money
        // decision taken by grep. Any response merely CONTAINING those bytes
        // read as an approval: an echoed request, a nested object, or a decline
        // whose diagnostic quotes back the field it is refusing. An issuer
        // improving its error messages could have bought the merchant's goods.
        //
        // Absent, malformed or non-boolean all mean not approved, because the
        // only answer that may move money is the issuer plainly saying yes.
        boolean approved = Boolean.TRUE.equals(Json.bool(response, "approved"));
        return approved
                ? Outcome.approved(authRef.toString())
                : Outcome.declined(orElse(Json.text(response, "reason"), "declined by issuer"));
    }

    private static String issuerCall(String path, String body, Instant businessAt) {
        if (issuerUnreachable) return null;
        try {
            HttpResponse<String> r = HTTP.send(
                    HttpRequest.newBuilder(URI.create(issuerBaseUrl + path))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            // A DECLINE IS A 200. Only a transport or server fault is a null,
            // because a processor that treats a decline as a fault retries it,
            // and retrying a decline is how a customer is declined five times
            // for one purchase.
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    // --------------------------------------------------------------- external

    /**
     * An issuer this system does not own.
     *
     * Simulated, and simulated the way every processor's sandbox does it,
     * because it is the only way to test a decline you do not control: the
     * instrument itself decides the answer. A processor's own tests cannot
     * depend on a stranger's bank being in a particular mood.
     */
    private static Outcome external(UUID authRef, String instrument, BigDecimal amount) {
        if (instrument == null) return Outcome.declined("no instrument");
        if (instrument.contains("_declined")) return Outcome.declined("card declined");
        if (instrument.contains("_insufficient")) return Outcome.declined("insufficient funds");
        if (instrument.contains("_expired")) return Outcome.declined("expired card");
        // a foreign issuer's own limit, which we neither see nor control
        if (amount.compareTo(new BigDecimal("2000.00")) > 0) return Outcome.declined("exceeds issuer limit");
        return Outcome.approved(authRef.toString());
    }

    private static String orElse(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }
}
