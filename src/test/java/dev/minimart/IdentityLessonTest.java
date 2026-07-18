package dev.minimart;

import dev.minimart.http.CallerIdentity;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WHO THE SHOP SERVES · the identity lessons.
 *
 * The estate rollout directive sets three rules for the permissive phase,
 * and the broker's review added a fourth. These tests pin all four at the
 * seam (CallerIdentity), which is where the rule lives — the endpoints just
 * call resolve().
 *
 * No tokens are minted here: the seam takes an Authorization header and
 * answers Optional<Long>, and the adapter to real RS256 validation lands
 * with the sso-client dependency. What these lessons protect is the part
 * that can leak data if it is wrong — the precedence.
 */
class IdentityLessonTest {

    @Test
    void aValidIdentityAnswerWinsOverTheRequest() {
        // LESSON 1 (directive a): a recognized caller is who the shop serves.
        CallerIdentity sso = header -> Optional.of(42L);

        long served = CallerIdentity.resolve(sso, "Bearer anything", 7L);

        assertEquals(42L, served, "the token's customer is served, not the body's");
    }

    @Test
    void noIdentityKeepsTodaysBehaviourExactly() {
        // LESSON 2 (directive b): ANONYMOUS — today's wiring — passes the
        // request's own claim straight through, untouched.
        long served = CallerIdentity.resolve(CallerIdentity.ANONYMOUS, null, 7L);

        assertEquals(7L, served, "permissive means the shop works exactly as before");
    }

    @Test
    void anUnrecognizedTokenChangesNothing() {
        // LESSON 3 (directive c): a token the shop cannot read — expired,
        // wrong audience, garbage — is the same as no token at all. The
        // request's claim still goes through, because this is the permissive
        // phase and nothing rejects.
        CallerIdentity sso = header -> Optional.empty();

        long served = CallerIdentity.resolve(sso, "Bearer unreadable", 7L);

        assertEquals(7L, served, "unreadable identity is no identity — and no rejection");
    }

    @Test
    void tokenForAPlusBodyNamingBServesANeverB() {
        // LESSON 4 (the broker's addition, adopted estate-wide): the three
        // rules above all pass with the precedence inverted, because none of
        // them sends a token AND a foreign id together. A token for A plus a
        // body saying {"customer": B} is an instruction to act as B — the
        // textbook IDOR — and honouring it is the leak.
        CallerIdentity sso = header -> "Bearer good".equals(header)
                ? Optional.of(42L) : Optional.empty();

        long served = CallerIdentity.resolve(sso, "Bearer good", 7L);

        assertEquals(42L, served,
            "identity beats the body: A's token plus B's number must serve A, never B");
    }

    @Test
    void everyFailureLooksIdentical() {
        // the oracle lesson: no-header, garbage-token and wrong-audience are
        // the same empty, or a caller can probe which tokens exist.
        CallerIdentity sso = header -> "Bearer good".equals(header)
                ? Optional.of(42L) : Optional.empty();

        assertEquals(sso.customerFor(null), sso.customerFor("Bearer garbage"));
        assertEquals(sso.customerFor("Bearer garbage"), sso.customerFor("Basic dXNlcg=="));
    }
}
