package dev.minipay.auth;

import java.util.Objects;
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
 * The body rules the day, the demos run, and nothing 401s. Enforcement
 * is the switch that ends that phase, and it is off until a deployment
 * sets PAY_IDENTITY_ENFORCE.
 *
 * Permissive is a statement about callers who prove NOTHING. A caller who
 * presents a credential is checked against it in either phase, because a
 * credential honoured without being verified is not a lenient rollout, it
 * is a wrong door.
 *
 * The money path — Rails, PaymentIntents, Settlements, Clearing — never
 * sees this type. Identity stops at the HTTP boundary. ON_US routing
 * decides from the rail, not from who asked, and it stays that way.
 */
public record CallerIdentity(Optional<String> ssoCustomer, Optional<BoundMerchant> apiKey) {

    /**
     * EXACTLY ONE ANSWER, OR NONE · enforced here rather than promised.
     *
     * A caller bound to a customer AND to a merchant is not a caller with
     * more identity, it is the confused deputy already assembled: the code
     * downstream would have to pick one, and every reader would pick a
     * different one. isAmbiguous refuses the mixed state at the door; this
     * constructor refuses it in memory, so the state cannot be reached by
     * some later path that forgets to ask.
     */
    public CallerIdentity {
        Objects.requireNonNull(ssoCustomer, "ssoCustomer");
        Objects.requireNonNull(apiKey, "apiKey");
        if (ssoCustomer.isPresent() && apiKey.isPresent()) {
            throw new IllegalArgumentException(
                "an identity is exactly one of customer-bound, merchant-bound, or nobody");
        }
    }

    /** A merchant proven by an API key, with the key's scope attached. */
    public record BoundMerchant(String merchant, String keyId, String scope) {}

    /** The scopes a key may be issued with. Only 'read' is refused money. */
    public static final String SCOPE_READ = "read";
    public static final String SCOPE_CHARGE = "charge";
    public static final String SCOPE_FULL = "full";

    /** The namespace of a caller who proved nothing. Not the empty string:
     *  see idempotencyNamespace for why the anonymous world needs a name. */
    public static final String ANONYMOUS_NAMESPACE = "anon";

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
     * MAY THIS CALLER MOVE MONEY, OR ONLY LOOK AT IT.
     *
     * A key is issued with a scope, and a scope that is documented but never
     * consulted is worse than no scope at all: it is a promise the merchant
     * made to their own security review on this service's behalf. 'read' is
     * the whole point of the field, so 'read' is what it must actually cost:
     * balances and lists, never a charge, a capture, a cancel, a settlement
     * or a clearing run.
     *
     * A scope string this code does not recognise is not a licence. New
     * verbs get added here deliberately or they do not work, which is the
     * failure direction an acquirer wants.
     *
     * Callers with no key are not governed by scope. Whether they may act at
     * all is Enforcement's question, and a different one.
     */
    public boolean mayWrite() {
        if (apiKey.isEmpty()) return true;
        String scope = apiKey.get().scope();
        return SCOPE_CHARGE.equals(scope) || SCOPE_FULL.equals(scope);
    }

    /**
     * THE IDEMPOTENCY NAMESPACE, AND WHY ANONYMOUS NEEDS ONE.
     *
     * Two callers sending the same key string must not replay each other's
     * payments, so the stored key carries the caller's namespace. The trap
     * is that a namespace made only of caller-supplied text can be typed by
     * hand: leave anonymous callers un-prefixed and one of them sends
     * Idempotency-Key: "key:pk_victim:K" and lands inside a real merchant's
     * namespace, reading back a response that was never theirs.
     *
     * The fix is that EVERY caller has a prefix, including the one who
     * proved nothing. An anonymous caller can still type "key:pk_victim:K",
     * and it stores as "anon:key:pk_victim:K", which is a string no
     * credential can ever produce. Anonymous callers share one namespace
     * with each other, exactly as they shared one before identity existed.
     */
    public String idempotencyNamespace() {
        if (apiKey.isPresent()) return "key:" + apiKey.get().keyId();
        if (ssoCustomer.isPresent()) return "sso:" + ssoCustomer.get();
        return ANONYMOUS_NAMESPACE;
    }

    /** The caller's key as it is stored: namespace first, always. */
    public String scopedIdempotencyKey(String key) {
        return idempotencyNamespace() + ":" + key;
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
