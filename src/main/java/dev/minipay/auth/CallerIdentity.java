package dev.minipay.auth;

import java.util.Optional;

/**
 * WHO IS CALLING · and minipay is the one place in the estate where the
 * answer has two shapes, because this service is an acquirer, not a shop.
 *
 * Everywhere else, one question suffices: which customer is calling? Here
 * the caller is never the customer. The caller is a merchant (or a
 * platform, or an NPC's containment shell) acting ON BEHALF of a customer
 * it names. So the question splits:
 *
 *   SSO token  → binds CUSTOMER. A cardholder acting on their own account.
 *                They may touch only what their own pm_ tokens point at.
 *
 *   API key    → binds MERCHANT. The caller acts as itself, on behalf of a
 *                customer it names through a pm_ token attached for THIS
 *                merchant and THIS customer. It may never name another
 *                merchant, and it may never point at a payment method
 *                attached for somebody else's pair.
 *
 * The confused deputy is the bug this type exists to make unwritable:
 * external NPC A presents its own key, sets merchant: B in the body, and
 * funds settle to B's balance — or it names a stranger's customer and
 * charges their card. A valid credential plus a foreign identifier is not
 * a request with extra information; it is an attack with extra steps.
 *
 * PERMISSIVE BY DEFAULT, per the estate rollout: no credential at all
 * means the request behaves exactly as it did before identity existed.
 * The body rules the day, the demos run, and nothing 401s. What this
 * record does TODAY is make the resolution path single and testable, so
 * the day a credential IS present, the precedence is already proven.
 *
 * The money path — Rails, PaymentIntents, Settlements, Clearing — never
 * sees this type. Identity stops at the HTTP boundary. ON_US routing
 * decides from the rail, not from who asked, and it stays that way.
 */
public record CallerIdentity(Optional<String> ssoCustomer, Optional<BoundMerchant> apiKey) {

    /** A merchant proven by an API key, with the key's scope attached. */
    public record BoundMerchant(String merchant, String keyId, String scope) {}

    /** Nobody proved anything. The behaviour of this service today. */
    public static final CallerIdentity ANONYMOUS =
        new CallerIdentity(Optional.empty(), Optional.empty());

    public static CallerIdentity ofSsoCustomer(String ssoSub) {
        return new CallerIdentity(Optional.of(ssoSub), Optional.empty());
    }

    public static CallerIdentity ofApiKey(String merchant, String keyId, String scope) {
        return new CallerIdentity(Optional.empty(),
            Optional.of(new BoundMerchant(merchant, keyId, scope)));
    }

    /**
     * WHICH MERCHANT DOES THIS REQUEST ACT FOR.
     *
     * The one rule that closes the deputy: an API-key caller IS its
     * merchant. The body's merchant field is not a claim to honor; it is
     * a claim to CHECK. Match, and the request proceeds as the caller
     * intended. Mismatch, and the request is not "handled differently" —
     * it is not the caller's request at all.
     *
     * Returns the merchant to act as, or empty if the body's claim
     * contradicts the credential. SSO and anonymous callers get the
     * body's merchant through untouched: a cardholder names the merchant
     * they are paying; an anonymous caller is today's behavior.
     */
    public Optional<String> merchantFor(String bodyMerchant) {
        if (apiKey.isPresent()) {
            String bound = apiKey.get().merchant();
            // null body merchant means "the caller's own" — the natural reading
            if (bodyMerchant == null) return Optional.of(bound);
            return bound.equals(bodyMerchant) ? Optional.of(bound) : Optional.empty();
        }
        return Optional.ofNullable(bodyMerchant);
    }

    /**
     * MAY THIS CALLER USE THIS PAYMENT METHOD.
     *
     * The Stripe shape: you never charge a customer by naming them. You
     * charge a pm_ token that was attached for a (merchant, customer)
     * pair, and the credential scopes which pairs the caller may even
     * point at. An API-key caller may use a pm_ only if that pm_'s owner
     * chain walks back to the caller's own merchant — that check works
     * TODAY, because the key proves the merchant and the pm_ row names it.
     *
     * The SSO customer's own-instruments rule needs the sso_accounts
     * mapping (usr_... → cus_...) which lands with activation; until then
     * the SSO path is permissive here, by the same rule as the rest of
     * the estate: wiring now, teeth later, never a wrong door.
     *
     * pmMerchant / pmCustomer are the owner pair read off the payment
     * method row (through PaymentMethods.find -> customer -> merchant).
     */
    public boolean mayUsePaymentMethod(String pmMerchant, String pmCustomer) {
        if (apiKey.isPresent()) {
            // the pm_ must live under the caller's own merchant
            return apiKey.get().merchant().equals(pmMerchant);
        }
        // SSO customer scoping activates with the sso_accounts mapping;
        // anonymous and SSO both pass today.
        return true;
    }

    /**
     * AMBIGUOUS IDENTITY IS AN ERROR, EVEN NOW.
     *
     * A request carrying BOTH a Bearer token and an API key is not "extra
     * authenticated" — it is two contradictory answers to who is calling,
     * and guessing which one to believe would be worse than asking. This
     * is the one rejection the permissive phase is allowed to make,
     * because it is not enforcement; it is refusing to guess.
     */
    public static boolean isAmbiguous(String authorizationHeader, String apiKeyHeader) {
        boolean hasBearer = authorizationHeader != null && authorizationHeader.startsWith("Bearer ");
        boolean hasKey = apiKeyHeader != null && !apiKeyHeader.isBlank();
        return hasBearer && hasKey;
    }
}
