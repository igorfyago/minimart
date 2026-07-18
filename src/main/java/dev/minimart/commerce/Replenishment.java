package dev.minimart.commerce;

import dev.minimart.core.Db;
import dev.minimart.core.Json;
import dev.minimart.core.Ledger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;

/**
 * THE WAREHOUSE, REACTING TO ORDERS IT WAS NEVER TOLD ABOUT DIRECTLY.
 *
 * Checkout does not call this and does not know it exists. It announces that an
 * order was placed, and whoever cares reacts. Adding restocking therefore
 * required no change to the code that sells things, which is the entire
 * argument for the broker: the alternative is a checkout path that grows a new
 * call every time somebody downstream wants to know something.
 *
 * The handler is deliberately a REAL business effect rather than a counter.
 * Idempotency that only protects a metric proves very little, because a
 * double-counted metric is annoying and a double-ordered pallet is money.
 */
public final class Replenishment {

    public static final String CONSUMER = "replenishment";

    /** Below this many units on hand, order more. */
    public static volatile long threshold = 20;
    public static volatile long reorderQty = 100;

    /** Test seam: a supplier that refuses a particular variant, so the poison
     *  event path can be exercised without breaking anything else. */
    public static volatile String refuseVariant = null;

    private Replenishment() {}

    /**
     * React to one order.placed event.
     *
     * Throwing here is how the handler says "not handled", and the consumer
     * runtime turns that into a retry and eventually a dead letter. So the
     * distinction that matters is between a fact this event cannot recover from
     * and a condition that might clear later, and both are expressed by throwing
     * or not throwing rather than by a status code nobody checks.
     */
    public static void onOrderPlaced(String eventKey, String payload, Instant businessAt) throws Exception {
        if (!"order.placed".equals(Json.str(payload, "type"))) return;   // not ours, and not an error

        String tenant = Json.str(payload, "tenant");
        String variant = Json.str(payload, "variant");
        String location = Json.str(payload, "location");
        if (tenant == null || variant == null || location == null) {
            // The contract was not met. This will never succeed however many
            // times it is retried, so it belongs in the dead letter queue where
            // somebody will see it, not in a retry loop that hides it.
            throw new IllegalArgumentException("order.placed is missing tenant, variant or location: " + payload);
        }

        if (variant.equals(refuseVariant)) {
            throw new IllegalStateException("supplier refuses to ship " + variant);
        }

        try (Connection c = Db.open()) {
            BigDecimal onHand;
            try {
                onHand = Ledger.balance(c, Orders.onHand(location, variant));
            } catch (IllegalArgumentException noAccount) {
                // nothing was ever stocked here, so there is nothing to top up
                // and this is not a failure
                return;
            }
            if (onHand.longValue() >= threshold) return;
        }

        // receiveStock derives its transaction id, so a redelivery that somehow
        // got past the handled_events claim STILL cannot order twice. Two
        // independent gates, because this one costs real money.
        Orders.receiveStock(tenant, location, variant, reorderQty, businessAt);
    }
}
