package dev.b4rruf3t.sso.client;

import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Validates JWTs issued by the b4rruf3t SSO service.
 * Public keys come from a JWKS source: over HTTP in production, from a fixed
 * key in tests.
 *
 * Every check below is deliberate, and several exist because porting this
 * class to Python for the desk acted as a differential test and the two
 * clients disagreed. Where they disagreed the stricter answer won, because two
 * services deriving DIFFERENT identities from the SAME signed token is the
 * worst outcome available to an SSO system.
 */
public final class SsoClient {
    /**
     * The only signature algorithm this issuer uses. The header's alg must say
     * so and nothing else. Ignoring alg, or trusting whatever it claims, is the
     * first entry on every JWT vulnerability list: "none" and the RS256 to
     * HS256 confusion attack both start there.
     */
    private static final String REQUIRED_ALG = "RS256";

    private final String issuer;
    private final Function<String, RSAPublicKey> keyResolver;

    /** Production: resolve keys from the SSO service's JWKS endpoint. */
    public SsoClient(String issuer) {
        this(issuer, new Jwks(issuer + "/.well-known/jwks.json")::getPublicKey);
    }

    /** Test/advanced: resolve keys however you like (e.g. a fixed test key). */
    public SsoClient(String issuer, Function<String, RSAPublicKey> keyResolver) {
        this.issuer = issuer;
        this.keyResolver = keyResolver;
    }

    /**
     * Validate a JWT. Returns the user if valid, empty otherwise.
     * Checks: structure, algorithm, signature, expiry, issuer, audience.
     *
     * Empty means "not a token I will act on" and never says why: the caller is
     * on the far side of a trust boundary, and a validator that explains its
     * refusals is an oracle.
     */
    public Optional<SsoUser> validateToken(String jwt, String expectedAudience) {
        try {
            if (jwt == null) return Optional.empty();
            String[] parts = jwt.split("\\.", -1);
            if (parts.length != 3) return Optional.empty();

            Map<String, Object> header = Json.parseObject(decode(parts[0]));

            // DEFECT 1. alg used to be ignored entirely, so this client was
            // protected only incidentally, by hardcoding SHA256withRSA below.
            // A token whose header announced "none" was accepted as long as it
            // happened to carry a genuine RS256 signature.
            if (!REQUIRED_ALG.equals(Json.string(header, "alg"))) return Optional.empty();

            String kid = Json.string(header, "kid");
            if (kid == null) return Optional.empty();

            RSAPublicKey publicKey = keyResolver.apply(kid);
            if (publicKey == null) return Optional.empty();

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update((parts[0] + "." + parts[1]).getBytes("UTF-8"));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return Optional.empty();
            }

            // DEFECT 2, the serious one. Claims used to be pulled out with
            // first-match regular expressions. Python's json.loads keeps the
            // LAST duplicate key, so a payload carrying "sub" twice made this
            // client and the desk's client authenticate two different people
            // from one signature. Json.parseObject refuses duplicates outright
            // rather than picking a winner, so every reader now agrees.
            Map<String, Object> payload = Json.parseObject(decode(parts[1]));

            Long exp = Json.number(payload, "exp");
            if (exp == null || Instant.now().getEpochSecond() > exp) return Optional.empty();

            if (!issuer.equals(Json.string(payload, "iss"))) return Optional.empty();

            if (expectedAudience != null && !hasAudience(payload, expectedAudience)) {
                return Optional.empty();
            }

            String sub = Json.string(payload, "sub");
            if (sub == null || sub.isEmpty()) return Optional.empty();

            return Optional.of(new SsoUser(sub,
                                           Json.string(payload, "name"),
                                           Json.string(payload, "email")));
        } catch (Exception e) {
            // Malformed JSON, a duplicated claim, a bad signature, a wrong
            // type: all of it is simply "no".
            return Optional.empty();
        }
    }

    private static String decode(String segment) {
        return new String(Base64.getUrlDecoder().decode(segment),
                          java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Exact audience match. aud is either a string or an array of strings per
     * RFC 7519; both are accepted, and membership is compared element-wise so
     * that "desk" never matches "desk-admin".
     */
    private boolean hasAudience(Map<String, Object> payload, String expected) {
        Object aud = payload.get("aud");
        if (aud instanceof String s) return s.equals(expected);
        if (aud instanceof List<?> list) {
            for (Object entry : list) {
                if (expected.equals(entry)) return true;
            }
        }
        return false;
    }
}
