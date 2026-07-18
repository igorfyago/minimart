package dev.minipay;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A CUSTOMER AND THE WAYS THEY CAN PAY.
 *
 * Shaped after the processor API everybody already knows, because a merchant
 * integrating this should not have to learn a new vocabulary to do the same
 * thing they have done before: a Customer holds PaymentMethods, a PaymentIntent
 * charges one of them, and the merchant never sees an instrument.
 *
 * WHAT THIS SERVICE DELIBERATELY DOES NOT HOLD: a name, an email, a card
 * number. The merchant's own reference for the person is stored so the merchant
 * can find them again, and it is opaque here. A processor that accumulates
 * identity becomes a thing worth stealing, and none of it is needed to move
 * money.
 */
public final class PaymentMethods {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Customer(String id, String merchant, String merchantRef) {}

    public record Method(String id, String customerId, String type, String rail,
                         String instrument, String brandLabel, String last4, String status) {}

    private PaymentMethods() {}

    /** Find or create the processor's customer for a merchant's reference. */
    public static Customer customer(String merchant, String merchantRef) throws SQLException {
        try (Connection c = PayDb.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM customers WHERE merchant = ? AND merchant_ref = ?")) {
                ps.setString(1, merchant); ps.setString(2, merchantRef);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Customer(rs.getString(1), merchant, merchantRef);
                }
            }
            String id = "cus_" + token(10);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO customers(id, merchant, merchant_ref) VALUES (?,?,?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, id); ps.setString(2, merchant); ps.setString(3, merchantRef);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM customers WHERE merchant = ? AND merchant_ref = ?")) {
                ps.setString(1, merchant); ps.setString(2, merchantRef);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Customer(rs.getString(1), merchant, merchantRef);
                }
            }
        }
    }

    /**
     * Attach a way of paying.
     *
     * The RAIL is decided here, once, from where the instrument came from, and
     * never re-derived later. A rail inferred at charge time from the shape of
     * a token would be a guess, and a guess about which bank to ask is the kind
     * of thing that works until somebody's token format changes.
     */
    public static Method attachCard(String customerId, String rail, String instrument,
                                    String brandLabel, String last4) throws SQLException {
        String id = "pm_" + token(10);
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO payment_methods(id, customer_id, type, rail, instrument, brand_label, last4)
                     VALUES (?,?, 'card', ?,?,?,?)""")) {
            ps.setString(1, id); ps.setString(2, customerId); ps.setString(3, rail);
            ps.setString(4, instrument); ps.setString(5, brandLabel); ps.setString(6, last4);
            ps.executeUpdate();
        }
        return new Method(id, customerId, "card", rail, instrument, brandLabel, last4, "active");
    }

    /** A balance held at this processor. No issuer, no network, no card. */
    public static Method attachWallet(String customerId) throws SQLException {
        String id = "pm_" + token(10);
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO payment_methods(id, customer_id, type, rail, brand_label)
                     VALUES (?,?, 'wallet', 'WALLET', 'processor balance')""")) {
            ps.setString(1, id); ps.setString(2, customerId);
            ps.executeUpdate();
        }
        return new Method(id, customerId, "wallet", "WALLET", null, "processor balance", null, "active");
    }

    public static Method find(String paymentMethodId) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, customer_id, type, rail, instrument, brand_label, last4, status
                       FROM payment_methods WHERE id = ?""")) {
            ps.setString(1, paymentMethodId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Method(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8));
            }
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
