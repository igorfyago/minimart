package dev.minipay;

import dev.minipay.auth.CallerIdentity;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE CONFUSED DEPUTY LESSONS · the money bugs that look like auth bugs.
 *
 * minipay is an acquirer: its callers are merchants and platforms acting
 * on behalf of customers they name. That makes the estate's usual rule —
 * "identity beats the request body" — land on a different field per path,
 * and these lessons pin exactly where.
 *
 * The deputy shape, stated once: a valid credential plus a foreign
 * identifier is not a request with extra information. It is an attack
 * with extra steps. Every test below is that sentence made executable.
 */
class DeputyLessonTest {

    private final CallerIdentity npcA = CallerIdentity.ofApiKey("npc-shop-a", "pk_aaa", "charge");
    private final CallerIdentity anonymous = CallerIdentity.ANONYMOUS;

    @Test
    void aKeyCallerIsItsMerchant() {
        // NPC A keys in, names itself: proceeds as itself
        assertEquals(Optional.of("npc-shop-a"), npcA.merchantFor("npc-shop-a"));
    }

    @Test
    void aKeyCallerNamingAnotherMerchantIsNotItsOwnRequest() {
        // THE BUG, CLOSED: NPC A's key plus merchant: B in the body would
        // settle funds to B's balance. merchantFor must refuse, not comply.
        assertTrue(npcA.merchantFor("npc-shop-b").isEmpty(),
            "valid credential + foreign merchant = attack, not request");
    }

    @Test
    void aKeyCallerWithNoMerchantInBodyActsAsItself() {
        // the natural reading of "no merchant named": the caller's own
        assertEquals(Optional.of("npc-shop-a"), npcA.merchantFor(null));
    }

    @Test
    void anonymousKeepsTodaysBehaviour() {
        // permissive phase: no credential means the body's word stands
        assertEquals(Optional.of("anyone"), anonymous.merchantFor("anyone"));
    }

    @Test
    void aKeyMayOnlyTouchItsOwnMerchantsPaymentMethods() {
        // you never charge a customer by naming them — you charge a pm_
        // attached for the pair, and the key scopes which pairs exist
        assertTrue(npcA.mayUsePaymentMethod("npc-shop-a", "cus_anything"),
            "a pm_ under the caller's own merchant is usable");
        assertFalse(npcA.mayUsePaymentMethod("npc-shop-b", "cus_anything"),
            "a pm_ under somebody else's merchant is not the caller's to point at");
    }

    @Test
    void anonymousMayUseAnyPaymentMethodToday() {
        // permissive: until activation, no credential means no scoping
        assertTrue(anonymous.mayUsePaymentMethod("anyone", "cus_anything"));
    }

    @Test
    void bearerPlusKeyIsAnErrorEvenNow() {
        // two contradictory answers to who is calling: guessing is worse
        // than asking. This is the one rejection the permissive phase makes.
        assertTrue(CallerIdentity.isAmbiguous("Bearer some.jwt.here", "pk_x:sk_y"));
        assertFalse(CallerIdentity.isAmbiguous("Bearer some.jwt.here", null));
        assertFalse(CallerIdentity.isAmbiguous(null, "pk_x:sk_y"));
        assertFalse(CallerIdentity.isAmbiguous(null, null));
        assertFalse(CallerIdentity.isAmbiguous("Basic dXNlcg==", "pk_x:sk_y"),
            "a non-Bearer Authorization is not an SSO claim");
    }

    @Test
    void ssoAndKeyAreNeverBothPresentInAnIdentity() {
        // THE MIXED STATE IS UNWRITABLE, and the only way to say that is to
        // try to write it. Asserting that the two factories return what the
        // two factories just set proves nothing about the type: it proves the
        // factories were read correctly, which was never in doubt. The
        // constructor is where the guarantee has to live, because a caller
        // bound to a customer AND a merchant is the confused deputy already
        // assembled, waiting for some later path to pick one.
        assertThrows(IllegalArgumentException.class, () ->
            new CallerIdentity(Optional.of("usr_1"),
                Optional.of(new CallerIdentity.BoundMerchant("npc-shop-a", "pk_aaa", "charge"))),
            "an identity is exactly one of customer-bound, merchant-bound, or nobody");

        // and the two shapes that ARE allowed stay allowed
        var sso = CallerIdentity.ofSsoCustomer("usr_1");
        assertTrue(sso.ssoCustomer().isPresent());
        assertTrue(sso.apiKey().isEmpty());

        assertTrue(npcA.ssoCustomer().isEmpty());
        assertTrue(npcA.apiKey().isPresent());
    }

    @Test
    void ssoCallerNamesTheMerchantTheyArePaying() {
        // a cardholder is not bound to one merchant — they shop around.
        // Their binding is the customer side; the merchant field stays theirs to name.
        var sso = CallerIdentity.ofSsoCustomer("usr_1");
        assertEquals(Optional.of("any-shop"), sso.merchantFor("any-shop"));
    }
}
