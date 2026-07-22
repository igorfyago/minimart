package dev.b4rruf3t.sso.client;

import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.function.Function;

/**
 * The doorkeeper pattern, generic over audience. Each app in the estate
 * builds one of these with its own audience — "bank.b4rruf3t.com" for the
 * ledger, "mart.b4rruf3t.com" for the shop — and asks it one question per
 * request: who, if anyone, is calling?
 *
 * This class does NOT map SSO users to local ids. That mapping is each
 * app's own seam (its sso_customers / sso_traders table), because the app
 * owns its data and the SSO service owns identity. What this class returns
 * is the raw SSO user; the app takes it from there.
 *
 * PERMISSIVE BY DEFAULT: every failure — no header, wrong scheme, expired
 * token, wrong audience — returns the same empty. The caller cannot tell
 * them apart and must not act differently on them: a service that answers
 * differently for "no token" and "bad token" is an oracle for probing
 * which tokens exist.
 */
public final class AudienceAuth {
    private final SsoClient sso;
    private final String audience;

    public AudienceAuth(String ssoIssuer, String audience) {
        this.sso = new SsoClient(ssoIssuer);
        this.audience = audience;
    }

    /** Test/advanced: validate against a fixed key resolver instead of live JWKS. */
    public AudienceAuth(String ssoIssuer, String audience,
                        Function<String, RSAPublicKey> keyResolver) {
        this.sso = new SsoClient(ssoIssuer, keyResolver);
        this.audience = audience;
    }

    /**
     * The SSO user this Authorization header proves, if it proves one —
     * for THIS app's audience only. A token minted for another app in the
     * estate is a valid SSO token and still not your user.
     */
    public Optional<SsoUser> authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return sso.validateToken(authorizationHeader.substring(7), audience);
    }
}
