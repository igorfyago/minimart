package dev.minipay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * THE IDEMPOTENCY LAYER.
 *
 * A caller-supplied key makes any POST safe to retry. Three cases, and the
 * difference between them is the whole point:
 *
 *   REPLAY      same key, same request  -> return the stored response, byte for
 *                                          byte. Not "already processed": the
 *                                          identical answer, so the caller
 *                                          cannot tell a retry from the original.
 *   CONFLICT    same key, different request -> refuse loudly. This is a caller
 *                                          bug and silently doing either thing
 *                                          would be worse.
 *   IN FLIGHT   same key, still running -> refuse; the first call owns it.
 *
 * Agents retry far more than humans do, which is exactly why this exists.
 */
public final class Idempotency {

    public record Stored(int status, String body) {}

    /** Outcome of trying to claim a key. */
    public sealed interface Claim permits Fresh, Replay, Conflict, InFlight {}
    public record Fresh() implements Claim {}
    public record Replay(Stored stored) implements Claim {}
    public record Conflict() implements Claim {}
    public record InFlight() implements Claim {}

    private Idempotency() {}

    public static String fingerprint(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Try to claim the key. Runs in its own short transaction so the marker
     * survives independently of the business work that follows.
     *
     * key arrives already namespaced by its caller, because the claim has to
     * stay ONE statement: the INSERT ... ON CONFLICT is what makes two racing
     * retries produce one winner, and a claim assembled from a SELECT plus an
     * INSERT would hand both of them the payment. caller is the same
     * namespace stored in its own right, so the row says who owns it instead
     * of leaving that knowledge in a concatenated string in Java.
     */
    public static Claim claim(String key, String caller, String fingerprint) throws SQLException {
        try (Connection c = PayDb.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO idempotency_keys(key, caller, fingerprint, state) VALUES (?,?,?, 'in_flight') " +
                    "ON CONFLICT (key) DO NOTHING")) {
                ps.setString(1, key);
                ps.setString(2, caller);
                ps.setString(3, fingerprint);
                if (ps.executeUpdate() == 1) { c.commit(); return new Fresh(); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT fingerprint, state, status_code, body FROM idempotency_keys WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    String seen = rs.getString(1), state = rs.getString(2);
                    if (!seen.equals(fingerprint)) { c.commit(); return new Conflict(); }
                    if ("in_flight".equals(state)) { c.commit(); return new InFlight(); }
                    Stored s = new Stored(rs.getInt(3), rs.getString(4));
                    c.commit();
                    return new Replay(s);
                }
            }
        }
    }

    /** Record the response so any future replay returns exactly this. */
    public static void complete(String key, int status, String body) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE idempotency_keys SET state = 'complete', status_code = ?, body = ? WHERE key = ?")) {
            ps.setInt(1, status);
            ps.setString(2, body);
            ps.setString(3, key);
            ps.executeUpdate();
        }
    }

    /** The first call failed: drop the marker so the caller may genuinely retry. */
    public static void release(String key) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM idempotency_keys WHERE key = ? AND state = 'in_flight'")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }
}
