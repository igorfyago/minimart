package dev.minimart.commerce;

import dev.minimart.core.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * THE SWEEPER · abandoned carts give their stock back.
 *
 * This is the dangerous one. The reserved account is POOLED per (location,
 * variant): every held reservation's units sit in the same balance. So when
 * this sweeper releases reservation A at the same instant a shipment captures
 * reservation B, deterministic transaction ids cannot referee it. The ids
 * differ ("release:A" and "capture:B"), both claim happily, and the ledger
 * still sums to zero afterwards. The books would balance and the warehouse
 * would be wrong, which is the worst failure this system can have.
 *
 * The referee is the RESERVATION ROW: both paths take it FOR UPDATE and both
 * refuse to act unless its state is still 'held'. Whoever gets the lock first
 * flips the state; the loser finds it already settled and does nothing.
 *
 * sweepOnce() is a single deterministic pass, so a simulation can call it at a
 * tick boundary and get reproducible results. runLoop() is for production.
 */
public final class ReservationSweeper {

    private ReservationSweeper() {}

    /** Expire everything held past its deadline. Returns how many were released. */
    public static int sweepOnce(Instant now, int limit) throws SQLException {
        List<UUID> due = new ArrayList<>();
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT order_id FROM reservations WHERE state = 'held' AND expires_at < ? " +
                     "ORDER BY expires_at LIMIT ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(now));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) due.add((UUID) rs.getObject(1));
            }
        }
        // No lock is held across this loop on purpose. abort() re-reads the
        // reservation under its own lock and refuses anything not still 'held',
        // so a capture that slipped in between simply wins the race.
        int released = 0;
        for (UUID orderId : due) {
            Orders.abort(orderId, now);
            released++;
        }
        return released;
    }

    /**
     * AUDIT 3 · the pooled reserved balance must equal the units still held by
     * live reservations, per location and variant. This is the one audit that
     * catches the race above; sum-zero and drift both pass while it fails.
     */
    public static List<String> reservedMismatches(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.ref, a.balance,
                       COALESCE((SELECT SUM(r.qty) FROM reservations r
                                 WHERE r.state = 'held'
                                   AND a.ref = 'stock:reserved:' || r.location || ':' || r.variant_id), 0) AS held
                FROM accounts a
                WHERE a.ref LIKE 'stock:reserved:%'""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BigDecimal balance = rs.getBigDecimal(2), held = rs.getBigDecimal(3);
                if (balance.compareTo(held) != 0)
                    out.add(rs.getString(1) + ": account " + balance + " vs held reservations " + held);
            }
        }
        return out;
    }
}
