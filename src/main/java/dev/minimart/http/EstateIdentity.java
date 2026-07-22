package dev.minimart.http;

import dev.b4rruf3t.sso.client.AudienceAuth;
import dev.b4rruf3t.sso.client.SsoUser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * THE ESTATE IDENTITY ADAPTER · the seam CallerIdentity's javadoc promised.
 *
 * A signed-in estate user who arrives at the shop IS somebody: the bank's
 * directory knows which customer that person already is, and the shop then
 * sells to THAT customer — which is the whole point of the circle: he buys
 * something at the mart, and the bank shows the use, because every app
 * agrees it was the same person.
 *
 * Two hops, both server-side (the browser never talks cross-origin):
 *
 *   1. the token validates HERE (RS256 against the estate's JWKS, audience
 *      mart.b4rruf3t.com) and yields a NAME — the SSO carries no bank id;
 *   2. the name is looked up in the bank's phonebook (/api/whois), which
 *      answers a customer id or null.
 *
 * Everything fails SHUT toward anonymous: no token, a bad token, the bank
 * unreachable, a person with no bank account — all the same Optional.empty,
 * and the request behaves exactly as the anonymous shop always has. The
 * simulation's agent customers never carry tokens and never notice this
 * exists.
 *
 * The bank hop is cached (one map, misses included): a phonebook lookup per
 * request would couple the shop's latency to the bank's on every page of
 * "my orders". A person's customer id does not change, so there is nothing
 * to invalidate.
 */
public final class EstateIdentity {

    private static final String AUDIENCE = "mart.b4rruf3t.com";
    private static final java.util.Map<String, Optional<Long>> WHOIS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private EstateIdentity() {}

    /** Where the bank answers /api/whois. Overridable: tests point it at a
     *  bank that is not there. */
    public static volatile String bankBaseUrl =
            System.getenv().getOrDefault("MINIBANK_URL", "http://localhost:8080");

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800)).build();

    /** Production adapter: live JWKS, live bank. */
    public static CallerIdentity create() {
        AudienceAuth auth = new AudienceAuth(
                System.getenv().getOrDefault("SSO_ISSUER", "https://auth.b4rruf3t.com"),
                AUDIENCE);
        return header -> auth.authenticate(header).flatMap(EstateIdentity::customerFor);
    }

    /** SsoUser -> bank customer id, cached, fail-shut to empty. */
    private static Optional<Long> customerFor(SsoUser user) {
        String name = user.name();
        if (name == null || name.isBlank()) return Optional.empty();
        String key = name.trim().toLowerCase();
        Optional<Long> hit = WHOIS_CACHE.get(key);
        if (hit != null) return hit;
        Optional<Long> found = lookupBank(key);
        WHOIS_CACHE.put(key, found);
        return found;
    }

    private static Optional<Long> lookupBank(String name) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(bankBaseUrl + "/api/whois?name=" +
                            java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return Optional.empty();
            String body = r.body();
            // {"customer":10} or {"customer":null} — the one field, no more
            String marker = "\"customer\":";
            int i = body.indexOf(marker);
            if (i < 0) return Optional.empty();
            String rest = body.substring(i + marker.length()).replaceAll("[^0-9a-z].*", "");
            if (rest.isBlank() || rest.startsWith("n")) return Optional.empty();
            return Optional.of(Long.parseLong(rest));
        } catch (Exception e) {
            // The bank being down must not cost the shop its sale. Anonymous
            // is the honest degrade; the caller cannot tell it apart from
            // "this person has no bank account", and must not.
            return Optional.empty();
        }
    }
}
