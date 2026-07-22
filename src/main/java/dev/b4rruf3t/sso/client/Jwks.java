package dev.b4rruf3t.sso.client;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fetches and caches public keys from the SSO JWKS endpoint.
 *
 * Keys are cached for five minutes. Past that they are refreshed, and if the
 * refresh fails they stay usable for a bounded grace period and no longer.
 * That grace is the deliberate answer to a real conflict, spelled out at
 * STALE_GRACE_SECONDS below.
 */
public final class Jwks {
    private static final long CACHE_TTL_SECONDS = 300;      // 5 minutes

    /**
     * How long a key may outlive its TTL when the JWKS endpoint cannot be
     * reached. This is an availability-versus-correctness choice, so it is made
     * here rather than fallen into.
     *
     * Refusing the moment the TTL lapses means a single JWKS outage logs every
     * person out of every app on the estate at once. Serving stale keys with no
     * limit, which is what this class used to do by re-reading its cache after
     * a failed refresh without re-checking expiry, means a rotated or revoked
     * key keeps authenticating people for as long as the outage lasts. Forever,
     * if nobody notices the endpoint is down.
     *
     * An hour rides out a restart or a brief partition without anyone noticing,
     * and still bounds how long a withdrawn key can be presented. Key rotation
     * must therefore leave the old key published for at least this long.
     */
    private static final long STALE_GRACE_SECONDS = 3600;   // 1 hour

    /**
     * An unknown kid used to trigger a fetch every single time it was seen, so
     * a stream of junk-kid tokens turned this client into an outbound request
     * amplifier pointed at the auth service. Refresh attempts are now spaced.
     */
    private static final long MIN_REFRESH_INTERVAL_SECONDS = 10;

    private final String jwksUrl;
    // An unbounded client waits forever, so one hanging JWKS host pins the
    // validating thread for the life of the process.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Map<String, CachedKey> cache = new ConcurrentHashMap<>();
    // Epoch 0, NOT Long.MIN_VALUE. refresh() gates on `now - previous`, and
    // now minus Long.MIN_VALUE overflows a long into a negative number that is
    // always below the interval, so the very first refresh was skipped and the
    // cache never filled · every token then failed validation because no key
    // was ever fetched. 0 is a real timestamp in the distant past: `now - 0` is
    // large and positive, so the first fetch always runs, and the rate limit
    // still holds for every call after it.
    private final AtomicLong lastRefreshAttempt = new AtomicLong(0L);

    public Jwks(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }

    /** Get the public key for a key ID. Returns null if not found. */
    public RSAPublicKey getPublicKey(String kid) {
        CachedKey cached = cache.get(kid);
        if (cached != null && !cached.isExpired()) return cached.key;

        boolean refreshed = refresh();

        CachedKey after = cache.get(kid);
        if (after == null) return null;
        if (!after.isExpired()) return after.key;

        // DEFECT 4. The cache used to be re-read here without re-checking
        // expiry, so once the endpoint went down an expired key validated
        // forever. A stale key is now served only while the refresh is failing
        // AND only inside the grace window. A refresh that SUCCEEDED but did
        // not carry this kid means the key is genuinely gone, so it is refused
        // immediately rather than lingering.
        if (!refreshed && after.isWithinGrace()) return after.key;
        return null;
    }

    /** Force refresh of all keys. True when the endpoint answered with keys. */
    public boolean refresh() {
        long now = Instant.now().getEpochSecond();
        long previous = lastRefreshAttempt.get();
        if (now - previous < MIN_REFRESH_INTERVAL_SECONDS) return false;
        if (!lastRefreshAttempt.compareAndSet(previous, now)) return false;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(jwksUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return false;
            return load(res.body());
        } catch (Exception e) {
            return false;                   // validation refuses; see getPublicKey
        }
    }

    /**
     * Read a JWKS document into the cache. Package-visible so the parsing can
     * be exercised without a socket.
     *
     * DEFECT 3. This used to split the raw body on the literal text
     * {"kty":"RSA", which worked only because the issuer happens to emit
     * compact JSON with kty first. Any proxy, CDN or issuer change that
     * pretty-printed the response, or ordered the members differently, would
     * have parsed out zero keys and silently failed every validation on the
     * estate. It is real JSON now.
     */
    boolean load(String body) {
        try {
            Map<String, Object> doc = Json.parseObject(body);
            Object keys = doc.get("keys");
            if (!(keys instanceof List<?> list)) return false;
            int loaded = 0;
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> jwk = (Map<String, Object>) raw;
                if (!"RSA".equals(Json.string(jwk, "kty"))) continue;
                String kid = Json.string(jwk, "kid");
                String n = Json.string(jwk, "n");
                String e = Json.string(jwk, "e");
                if (kid == null || n == null || e == null) continue;
                RSAPublicKey key = buildKey(n, e);
                if (key == null) continue;
                cache.put(kid, new CachedKey(key, Instant.now().plusSeconds(CACHE_TTL_SECONDS)));
                loaded++;
            }
            return loaded > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private RSAPublicKey buildKey(String nBase64, String eBase64) {
        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(nBase64));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(eBase64));
            RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            return null;
        }
    }

    private record CachedKey(RSAPublicKey key, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
        boolean isWithinGrace() {
            return Instant.now().isBefore(expiresAt.plusSeconds(STALE_GRACE_SECONDS));
        }
    }
}
