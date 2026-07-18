package dev.minimart.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * THE LEDGER · double entry, for money and for goods alike.
 *
 * A transaction is a set of legs that must sum to zero PER CURRENCY. Money
 * legs are SIMEUR. Stock legs are UNIT:{variantId}. Because both live in the
 * same tables under the same rules, "do not oversell" and "do not overdraw"
 * are the same sentence, enforced once.
 *
 * Two guarantees are structural rather than hoped for:
 *   IDEMPOTENCE · the caller mints the transaction id and it is the primary
 *   key. A retry is an INSERT conflict, so it does nothing.
 *   NO DEADLOCK · every transaction locks its accounts in ascending id order,
 *   so no cycle of waiting can form.
 */
public final class Ledger {

    /** One leg of a transaction: an account, and a signed amount. */
    public record Leg(String accountRef, BigDecimal amount) {}

    /** Raised when a leg would push a non-external account below zero. It
     *  names the account, so the caller can say "out of stock" or "insufficient
     *  funds" precisely instead of guessing from a constraint code. */
    public static class Insufficient extends RuntimeException {
        public final String accountRef;
        public Insufficient(String ref) { super("insufficient balance: " + ref); this.accountRef = ref; }
    }

    private Ledger() {}

    /** Claim the transaction id. False means this work already happened. */
    public static boolean claimTx(Connection c, UUID txId, String kind, Instant businessAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactions(id, kind, business_at) VALUES (?,?,?) ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, txId);
            ps.setString(2, kind);
            ps.setTimestamp(3, java.sql.Timestamp.from(businessAt));
            return ps.executeUpdate() == 1;
        }
    }

    public static void ensureAccount(Connection c, String ref, String kind, String currency) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounts(ref, kind, currency) VALUES (?,?,?) ON CONFLICT (ref) DO NOTHING")) {
            ps.setString(1, ref);
            ps.setString(2, kind);
            ps.setString(3, currency);
            ps.executeUpdate();
        }
    }

    /**
     * Post a transaction. Locks every touched account in ascending id order,
     * checks each resulting balance under that lock, writes the entries and
     * updates the cached balances. All or nothing.
     */
    public static void post(Connection c, UUID txId, Instant businessAt, List<Leg> legs)
            throws SQLException {

        record Target(long id, String ref, String kind, BigDecimal delta) {}
        List<Target> targets = new ArrayList<>();
        for (Leg leg : legs) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, kind FROM accounts WHERE ref = ?")) {
                ps.setString(1, leg.accountRef());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("no such account: " + leg.accountRef());
                    targets.add(new Target(rs.getLong(1), leg.accountRef(), rs.getString(2), leg.amount()));
                }
            }
        }
        // ascending id: the one global order that makes deadlock unconstructible
        targets.sort(Comparator.comparingLong(Target::id));

        for (Target t : targets) {
            BigDecimal balance;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT balance FROM accounts WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, t.id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    balance = rs.getBigDecimal(1);
                }
            }
            BigDecimal after = balance.add(t.delta());
            // the funds/stock check, under the lock that makes it true
            if (!"external".equals(t.kind()) && after.signum() < 0) throw new Insufficient(t.ref());

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE accounts SET balance = ? WHERE id = ?")) {
                ps.setBigDecimal(1, after);
                ps.setLong(2, t.id());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO entries(tx_id, account_id, amount, business_at) VALUES (?,?,?,?)")) {
                ps.setObject(1, txId);
                ps.setLong(2, t.id());
                ps.setBigDecimal(3, t.delta());
                ps.setTimestamp(4, java.sql.Timestamp.from(businessAt));
                ps.executeUpdate();
            }
        }
    }

    public static BigDecimal balance(Connection c, String ref) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE ref = ?")) {
            ps.setString(1, ref);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("no such account: " + ref);
                return rs.getBigDecimal(1);
            }
        }
    }

    /** AUDIT 1 · every transaction sums to zero, per currency. Expect empty. */
    public static List<String> sumZeroViolations(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT e.tx_id, a.currency, SUM(e.amount) AS total
                FROM entries e JOIN accounts a ON a.id = e.account_id
                GROUP BY e.tx_id, a.currency
                HAVING SUM(e.amount) <> 0""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1) + " / " + rs.getString(2) + " = " + rs.getBigDecimal(3));
        }
        return out;
    }

    /** AUDIT 2 · every cached balance equals the sum of its entries. Expect empty. */
    public static List<String> driftedAccounts(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.ref, a.balance, COALESCE(e.s, 0) AS from_entries
                FROM accounts a
                LEFT JOIN (SELECT account_id, SUM(amount) AS s FROM entries GROUP BY account_id) e
                       ON e.account_id = a.id
                WHERE a.balance <> COALESCE(e.s, 0)""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1) + ": cached " + rs.getBigDecimal(2) + " vs " + rs.getBigDecimal(3));
        }
        return out;
    }
}
