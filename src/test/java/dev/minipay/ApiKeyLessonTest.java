package dev.minipay;

import dev.minipay.auth.ApiKeys;
import dev.minipay.auth.CallerIdentity;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE CREDENTIAL LESSONS · what a key proves, and what it costs to hold one.
 *
 * A key table is not interesting until you ask it the three questions an
 * attacker asks: can I use a key that was never issued, can I use half of one,
 * and does a revoked key really stop working. This lesson asks them, because a
 * binding nobody tested is a binding nobody has.
 */
class ApiKeyLessonTest {

    @BeforeAll
    static void boot() throws Exception { PayDb.bootstrap(); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = PayDb.open(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE api_keys RESTART IDENTITY CASCADE");
        }
    }

    /** LESSON 1 · a key is a merchant, decided at issue and never after. */
    @Test
    void aValidKeyBindsExactlyTheMerchantItWasIssuedFor() throws Exception {
        ApiKeys.IssuedKey issued = ApiKeys.issue("npc-shop-a", "Agentic Visitors Inc", "charge");

        Optional<ApiKeys.ValidKey> k = ApiKeys.validate(issued.keyId() + ":" + issued.secret());

        assertTrue(k.isPresent(), "the key that was just issued must validate");
        assertEquals("npc-shop-a", k.get().merchant());
        assertEquals("charge", k.get().scope());
        assertEquals(issued.keyId(), k.get().keyId());
    }

    /**
     * LESSON 2 · the three ways a key fails give the same answer.
     *
     * Unknown, wrong secret and revoked are one empty Optional on purpose. A
     * caller who can tell "no such key" from "wrong secret" has been handed an
     * oracle for enumerating which key ids are live, and the enumeration is
     * the expensive half of the attack.
     */
    @Test
    void unknownWrongSecretAndRevokedAreAllRefused() throws Exception {
        ApiKeys.IssuedKey issued = ApiKeys.issue("npc-shop-a", "owner", "full");

        assertTrue(ApiKeys.validate("pk_deadbeef:sk_neverissued").isEmpty(),
            "a key id that was never issued proves nothing");
        assertTrue(ApiKeys.validate(issued.keyId() + ":sk_wrongwrongwrong").isEmpty(),
            "the right key id with the wrong secret is somebody holding half a credential");
        assertTrue(ApiKeys.validate(issued.keyId()).isEmpty(),
            "half a credential is not a credential");
        assertTrue(ApiKeys.validate("garbage").isEmpty());
        assertTrue(ApiKeys.validate(null).isEmpty());

        ApiKeys.revoke(issued.keyId());
        assertTrue(ApiKeys.validate(issued.keyId() + ":" + issued.secret()).isEmpty(),
            "REVOCATION IS THE POINT OF HAVING A KEY TABLE: after it, the same string is a stranger");
    }

    /**
     * LESSON 3 · the secret is shown once and stored as a hash.
     *
     * A leaked database row must not be a leaked key. This is the difference
     * between an incident where keys are rotated and an incident where every
     * merchant's traffic was replayable for as long as the backup existed.
     */
    @Test
    void theSecretIsStoredHashedAndNeverHandedBackAgain() throws Exception {
        ApiKeys.IssuedKey issued = ApiKeys.issue("npc-shop-a", "owner", "read");

        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("SELECT key_hash FROM api_keys WHERE key_id = ?")) {
            ps.setString(1, issued.keyId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String stored = rs.getString(1);
                assertNotEquals(issued.secret(), stored, "the secret itself is never a stored value");
                assertFalse(stored.contains(issued.secret()));
                assertEquals(64, stored.length(), "sha-256, hex");
            }
        }

        // and there is no second way to ask for it: validate returns the
        // binding, never the credential
        ApiKeys.ValidKey k = ApiKeys.validate(issued.keyId() + ":" + issued.secret()).orElseThrow();
        assertFalse(k.toString().contains(issued.secret()),
            "a validated key describes who is calling, not what they sent");
    }

    /**
     * LESSON 4 · scope narrows what a key may do, or it is a lie in a javadoc.
     *
     * 'read' is documented as balances and lists. A documented restriction
     * that no code consults is worse than none: the merchant's own security
     * review already counted it.
     */
    @Test
    void readScopeMayNotMoveMoney() {
        assertFalse(CallerIdentity.ofApiKey("m", "pk_1", "read").mayWrite(),
            "'read' is the whole reason the field exists");
        assertTrue(CallerIdentity.ofApiKey("m", "pk_1", "charge").mayWrite());
        assertTrue(CallerIdentity.ofApiKey("m", "pk_1", "full").mayWrite());
        assertFalse(CallerIdentity.ofApiKey("m", "pk_1", "sudo").mayWrite(),
            "a scope this code does not recognise is not a licence");
        assertTrue(CallerIdentity.ANONYMOUS.mayWrite(),
            "callers with no key are governed by enforcement, not by scope");
    }

    /**
     * LESSON 5 · a namespace you can type is not a namespace.
     *
     * The keyed namespace is a prefix on the stored idempotency key. Leave the
     * anonymous caller un-prefixed and they can simply type the prefix: send
     * "key:pk_victim:K" and land inside a real merchant's namespace, where the
     * stored response of somebody else's payment is waiting.
     */
    @Test
    void anonymousCannotTypeItsWayIntoAKeyedNamespace() {
        CallerIdentity victim = CallerIdentity.ofApiKey("npc-shop-a", "pk_victim", "charge");
        String real = victim.scopedIdempotencyKey("K");

        String forged = CallerIdentity.ANONYMOUS.scopedIdempotencyKey("key:pk_victim:K");

        assertEquals("key:pk_victim:K", real);
        assertNotEquals(real, forged, "THE FORGERY: a hand-typed prefix must not reach a real namespace");
        assertEquals("anon:key:pk_victim:K", forged);

        // and the anonymous world keeps exactly one namespace, as it had
        // before identity existed: two anonymous callers still share
        assertEquals(CallerIdentity.ANONYMOUS.scopedIdempotencyKey("K"),
                     CallerIdentity.ANONYMOUS.scopedIdempotencyKey("K"));
        assertNotEquals(CallerIdentity.ANONYMOUS.scopedIdempotencyKey("K"), real);
    }

    /** LESSON 6 · two keys, two namespaces, one key string. */
    @Test
    void twoKeyedCallersDoNotShareAKeyString() throws Exception {
        var a = CallerIdentity.ofApiKey("npc-shop-a", "pk_aaa", "charge");
        var b = CallerIdentity.ofApiKey("npc-shop-b", "pk_bbb", "charge");
        assertNotEquals(a.scopedIdempotencyKey("order-1"), b.scopedIdempotencyKey("order-1"));
    }

    /** LESSON 7 · what a console may show of a stored key: the caller's half. */
    @Test
    void theNamespacePrefixIsNeverPartOfAnAnswer() {
        assertEquals("order-1", PayApi.visibleKey("key:pk_live_abc:order-1", "key:pk_live_abc"));
        assertEquals("order-1", PayApi.visibleKey("anon:order-1", "anon"));
        // a row written before the namespace had a column of its own
        assertEquals("order-1", PayApi.visibleKey("key:pk_live_abc:order-1", null));
        assertEquals("order-1", PayApi.visibleKey("order-1", null));
    }
}
