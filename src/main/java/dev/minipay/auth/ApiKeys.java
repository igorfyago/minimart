package dev.minipay.auth;

import dev.minipay.PayDb;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * API keys: how external callers — platforms, NPC containment shells, any
 * software that is not an estate user — authenticate to the processor.
 * This is the Stripe role, and the shape is the Stripe shape: a key is
 * bound to ONE merchant at issue time, and everything it can do follows
 * from that binding.
 *
 * A key proves "I am merchant M". It does not prove, and can never prove,
 * "I may act as merchant N". The binding is the security model; there is
 * no scope string powerful enough to widen it.
 *
 * Keys are shown once at issue and stored hashed. A leaked database row
 * is not a leaked key.
 */
public final class ApiKeys {

    private ApiKeys() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A validated key: which merchant it binds, under what scope. */
    public record ValidKey(String keyId, String merchant, String scope) {}

    /**
     * Issue a key pair for a merchant. Returns the secret ONCE — pk for
     * logs and dashboards, sk for the merchant's vault. We keep only the
     * hash of the secret half.
     *
     * scope is a coarse verb set: 'charge' (create intents), 'read'
     * (balances, lists), or 'full'. It narrows what the key may do; it
     * never widens WHO the key is.
     */
    public static IssuedKey issue(String merchant, String ownerName, String scope) throws SQLException {
        String keyId = "pk_" + token(8);
        String secret = "sk_" + token(16);
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO api_keys(key_id, key_hash, merchant, owner_name, scope) VALUES (?,?,?,?,?)")) {
            ps.setString(1, keyId);
            ps.setString(2, hash(secret));
            ps.setString(3, merchant);
            ps.setString(4, ownerName);
            ps.setString(5, scope);
            ps.executeUpdate();
        }
        return new IssuedKey(keyId, secret);
    }

    public record IssuedKey(String keyId, String secret) {}

    /**
     * Validate a presented secret. The secret itself names its key id
     * (sk_... belongs to pk_... issued together — we store the pairing
     * implicitly by hashing; the presented value is the whole secret).
     * To look the key up we need the id, so the caller sends both halves
     * as "pk_...:sk_..." — the standard Stripe-style combined secret.
     *
     * Returns the bound merchant and scope if the key is live; empty for
     * unknown, wrong secret, or revoked — indistinguishable on purpose.
     */
    public static Optional<ValidKey> validate(String presented) throws SQLException {
        if (presented == null) return Optional.empty();
        String[] parts = presented.split(":", 2);
        if (parts.length != 2 || !parts[0].startsWith("pk_") || !parts[1].startsWith("sk_")) {
            return Optional.empty();
        }
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT key_hash, merchant, scope FROM api_keys WHERE key_id = ? AND revoked_at IS NULL")) {
            ps.setString(1, parts[0]);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                if (!hash(parts[1]).equals(rs.getString(1))) return Optional.empty();
                return Optional.of(new ValidKey(parts[0], rs.getString(2), rs.getString(3)));
            }
        }
    }

    /** Revoke a key. It never works again, and validation says nothing. */
    public static void revoke(String keyId) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE api_keys SET revoked_at = NOW() WHERE key_id = ? AND revoked_at IS NULL")) {
            ps.setString(1, keyId);
            ps.executeUpdate();
        }
    }

    /** sha-256 of the secret half. Never store the secret itself. */
    private static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        StringBuilder b = new StringBuilder();
        for (byte x : raw) b.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return b.toString();
    }
}
