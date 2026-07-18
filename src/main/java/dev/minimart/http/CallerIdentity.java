package dev.minimart.http;

import java.util.Optional;

/**
 * WHO IS CALLING · the shop's one question about identity.
 *
 * The estate's SSO lives at auth.b4rruf3t.com and the Java validator is
 * dev.b4rruf3t.sso.client.AudienceAuth. This service does not depend on it,
 * for the same reason the broker in minibank does not: the build must
 * resolve from a clean clone, and sso-client is not a consumable artifact
 * here yet. The adapter, the day it is, is the whole of it:
 *
 *     CallerIdentity sso(AudienceAuth auth, CustomerDirectory dir) {
 *         return header -> auth.authenticate(header).flatMap(dir::customerForSub);
 *     }
 *
 * PERMISSIVE BY DEFAULT, per the estate rollout directive: no token means
 * no identity, and no identity means the request behaves exactly as it did
 * before SSO existed. The storefront, the catalogue, and the simulation all
 * keep working anonymously — the simulation's agent customers do not log in.
 *
 * THE RULE THAT MATTERS · identity beats the request body. Until now a
 * customer id arrived in the JSON body or the query string, because there
 * was nothing better and nothing to protect. The moment a token can
 * identify someone, a body saying {"customer": <someone else>} becomes an
 * instruction to act as somebody else — the same textbook IDOR the broker
 * learned. Valid token for A plus a body naming B must serve A, never B.
 */
@FunctionalInterface
public interface CallerIdentity {

    /**
     * The customer this Authorization header proves, if it proves one.
     * Empty covers every failure identically — no header, wrong scheme,
     * expired token, a token minted for another app's audience. The caller
     * cannot tell them apart and must not act differently on them.
     */
    Optional<Long> customerFor(String authorizationHeader);

    /** Nobody is ever identified. The behaviour of this service today. */
    CallerIdentity ANONYMOUS = header -> Optional.empty();

    /**
     * Resolve which customer a request acts for: the token's answer if it
     * has one, the request's own claim otherwise. One place, one rule,
     * every endpoint.
     */
    static long resolve(CallerIdentity identity, String authorizationHeader, long requested) {
        return identity.customerFor(authorizationHeader).orElse(requested);
    }
}
