package dev.minifreight;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE FULFILMENT SAGA, PROVEN AGAINST A WORLD THAT MISBEHAVES ON PURPOSE.
 *
 * No broker in any lesson: the consumer's apply() is called the way Kafka
 * would call it, repeatedly and out of order, because whether a shipment
 * survives redelivery is a database question, not a transport one. The
 * carriers, though, are REAL in the only sense that matters here: separate
 * HTTP servers freight cannot see into, one of which writes labels before
 * failing to say so, delivers every webhook twice, and can go entirely
 * silent. Every lesson is a claim about what freight does when the outside
 * world does that.
 */
class FreightLessonTest {

    static final String TENANT = "helix";
    static final Instant T0 = Instant.parse("2026-12-01T00:00:00Z");

    static CarrierSim sim;
    static com.sun.net.httpserver.HttpServer api;
    static FreightDriver driver;
    static int apiPort;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void boot() throws Exception {
        FreightDb.bootstrap();
        api = FreightApi.start(0);
        apiPort = api.getAddress().getPort();
        sim = CarrierSim.start(0, "http://localhost:" + apiPort);
        driver = new FreightDriver("http://localhost:" + sim.port());
    }

    @AfterAll
    static void shutdown() {
        if (sim != null) sim.stop();
        if (api != null) api.stop(0);
    }

    @BeforeEach
    void reset() throws Exception {
        sim.silent.clear();
        sim.budgetWroteThenFailed.set(0);
        try (Connection c = FreightDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE shipments, carrier_steps, tracking_events, outbox, handled_events RESTART IDENTITY CASCADE");
        }
    }

    // ---------------------------------------------------------------- lessons

    /**
     * LESSON 1 · ONE ORDER SHIPS ONCE, HOWEVER OFTEN THE TOPIC INSISTS.
     *
     * Two gates, because two different things repeat. The same DELIVERY
     * repeats when a crash replays a batch, and the handled_events claim
     * absorbs it. The same ORDER repeats under a key the consumer has never
     * seen when a backlog is replayed into a new group, and only the unique
     * order_id absorbs that. The second apply with a fresh key returns true ·
     * the claim was genuinely new · and still creates nothing, which is the
     * point.
     */
    @Test
    void lesson1_redelivery_and_replay_create_one_shipment() throws Exception {
        UUID orderId = UUID.randomUUID();
        String payload = orderFulfilled(orderId, 2);

        assertTrue(FreightConsumer.apply("order.fulfilled:" + orderId, payload));
        assertFalse(FreightConsumer.apply("order.fulfilled:" + orderId, payload), "the same delivery must be a no-op");
        assertTrue(FreightConsumer.apply("order.fulfilled:" + orderId + ":replayed", payload),
                "a fresh key is a genuinely new claim, even when the order is not");

        assertEquals(1, count("SELECT COUNT(*) FROM shipments WHERE order_id = '" + orderId + "'"));
    }

    /**
     * LESSON 2 · AN UNKNOWN FREEZES THE LADDER, AND THE LABEL THAT SECRETLY
     * EXISTS IS ADOPTED, NOT DUPLICATED.
     *
     * qty 20 is too big for swift and fine for budget, and budget's label
     * endpoint writes the label and then answers 500. The wrong driver falls
     * to a third carrier or re-fires blind, and one parcel ships twice. This
     * one stops, asks budget what it actually did, and takes the answer.
     * The proof is in the sim's own counter: the ugly path ran exactly once,
     * so exactly one label ever existed.
     */
    @Test
    void lesson2_unknown_is_adopted_fresh_never_refired_blind() throws Exception {
        UUID orderId = UUID.randomUUID();
        FreightConsumer.apply("order.fulfilled:" + orderId, orderFulfilled(orderId, 20));
        UUID shipmentId = shipmentOf(orderId);

        driver.runOnce(T0);
        assertEquals("requested", stateOf(shipmentId), "an unknown outcome must not move the machine");
        assertEquals("rejected", stepState(shipmentId, "swift"));
        assertEquals("unknown", stepState(shipmentId, "budget"));
        assertEquals(1, sim.budgetWroteThenFailed.get());

        driver.runOnce(T0);
        assertEquals("labelled", stateOf(shipmentId));
        assertEquals("accepted", stepState(shipmentId, "budget"));
        assertEquals(1, sim.budgetWroteThenFailed.get(), "adoption must never re-fire the label request");
        assertEquals(1, count("SELECT COUNT(*) FROM carrier_steps WHERE shipment_id = '" + shipmentId
                + "' AND state = 'accepted'"));
    }

    /**
     * LESSON 3 · A CARRIER THAT STUTTERS IS HEARD ONCE, AND YESTERDAY'S NEWS
     * CANNOT UNDELIVER A PARCEL.
     *
     * budget sends every webhook twice; the (carrier, event_key) constraint
     * turns the echo into a duplicate at the door. Then a late in_transit
     * arrives under a key of its own, AFTER delivered, and is answered 200
     * and changes nothing: the machine is monotonic, and an error here would
     * only teach the carrier's retry loop to repeat itself.
     */
    @Test
    void lesson3_duplicate_and_stale_webhooks_change_nothing() throws Exception {
        UUID orderId = UUID.randomUUID();
        FreightConsumer.apply("order.fulfilled:" + orderId, orderFulfilled(orderId, 20));
        UUID shipmentId = shipmentOf(orderId);
        driver.runOnce(T0);
        driver.runOnce(T0);                       // budget adopted, labelled
        String tracking = trackingOf(shipmentId);

        advance("budget", tracking);              // fires the webhook twice
        assertEquals("in_transit", stateOf(shipmentId));
        assertEquals(1, count("SELECT COUNT(*) FROM tracking_events WHERE event_key = '"
                + tracking + ":in_transit' AND carrier = 'budget'"));

        advance("budget", tracking);              // delivered, twice again
        assertEquals("delivered", stateOf(shipmentId));

        // a straggler about the past, under its own key, signed and correct
        String late = "{\"tracking\":\"" + tracking + "\",\"status\":\"in_transit\",\"eventKey\":\""
                + tracking + ":in_transit:straggler\",\"at\":\"" + T0 + "\"}";
        HttpResponse<String> r = webhook("budget", late, FreightApi.hmac("budget-hook-secret-01", late));
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("stale"));
        assertEquals("delivered", stateOf(shipmentId), "history must never overwrite the present");
    }

    /**
     * LESSON 4 · EVERY CARRIER SAYING NO IS A VERDICT, ANNOUNCED EXACTLY ONCE.
     *
     * Two real refusals from two real HTTP servers, and the shipment is
     * failed · the one terminal freight may reach alone, because a parcel
     * nobody labelled is provably nowhere. The announcement is the seam the
     * mart-side compensation will consume; the money is not freight's to
     * move, and saying so on the topic is how the party whose money it is
     * finds out.
     */
    @Test
    void lesson4_every_carrier_rejecting_fails_the_shipment_aloud() throws Exception {
        UUID orderId = UUID.randomUUID();
        FreightConsumer.apply("order.fulfilled:" + orderId, orderFulfilled(orderId, 60));
        UUID shipmentId = shipmentOf(orderId);

        driver.runOnce(T0);
        assertEquals("failed", stateOf(shipmentId));
        assertEquals(1, count("SELECT COUNT(*) FROM outbox WHERE event_key = 'shipment.failed:" + shipmentId + "'"));

        driver.runOnce(T0);                       // a second pass finds nothing to do
        assertEquals(1, count("SELECT COUNT(*) FROM outbox WHERE event_key = 'shipment.failed:" + shipmentId + "'"));
    }

    /**
     * LESSON 5 · A WEBHOOK IS A CLAIM, AND AN UNSIGNED CLAIM IS NOISE.
     *
     * "delivered" arriving over plain HTTP completes an order and quiets
     * every audit that would have asked about it, which is exactly why it
     * must be the hardest word to forge. Wrong signature, right signature
     * over a tampered body, both answered 401, nothing moves.
     */
    @Test
    void lesson5_a_forged_webhook_moves_nothing() throws Exception {
        UUID orderId = UUID.randomUUID();
        FreightConsumer.apply("order.fulfilled:" + orderId, orderFulfilled(orderId, 1));
        UUID shipmentId = shipmentOf(orderId);
        driver.runOnce(T0);                       // swift labels it immediately
        String tracking = trackingOf(shipmentId);

        String forged = "{\"tracking\":\"" + tracking + "\",\"status\":\"delivered\",\"eventKey\":\""
                + tracking + ":delivered\",\"at\":\"" + T0 + "\"}";
        assertEquals(401, webhook("swift", forged, "0000deadbeef").statusCode());

        String signedForOtherBody = FreightApi.hmac("swift-hook-secret-01", "{\"something\":\"else\"}");
        assertEquals(401, webhook("swift", forged, signedForOtherBody).statusCode());

        assertEquals("labelled", stateOf(shipmentId));
        assertEquals(0, count("SELECT COUNT(*) FROM tracking_events WHERE shipment_id = '" + shipmentId
                + "' AND status <> 'labelled'"), "the forgery must leave no trace but the label it failed to move");
        assertEquals(0, count("SELECT COUNT(*) FROM outbox WHERE event_key = 'shipment.delivered:" + shipmentId
                + "'"), "and no half-applied announcement either: the outbox is part of the same refusal");
    }

    /**
     * LESSON 6 · SILENCE IS A CARRIER FAILURE, NOT A PARCEL FAILURE.
     *
     * budget moves the parcel and tells nobody. The driver's poll asks the
     * question the webhook never answered, and mints the SAME event key a
     * webhook would have · so if the carrier's tongue ever loosens and the
     * missing webhooks arrive late, they collapse into duplicates instead of
     * double announcements.
     */
    @Test
    void lesson6_a_silent_carrier_is_polled_not_trusted() throws Exception {
        UUID orderId = UUID.randomUUID();
        FreightConsumer.apply("order.fulfilled:" + orderId, orderFulfilled(orderId, 20));
        UUID shipmentId = shipmentOf(orderId);
        driver.runOnce(T0);
        driver.runOnce(T0);                       // labelled at budget
        String tracking = trackingOf(shipmentId);

        sim.silent.add("budget");
        advance("budget", tracking);
        advance("budget", tracking);              // delivered, and nobody was told
        assertEquals("labelled", stateOf(shipmentId), "no webhook, no movement, yet");

        driver.runOnce(T0);
        assertEquals("delivered", stateOf(shipmentId));

        sim.silent.remove("budget");              // the missing webhook finally arrives
        String late = "{\"tracking\":\"" + tracking + "\",\"status\":\"delivered\",\"eventKey\":\""
                + tracking + ":delivered\",\"at\":\"" + T0 + "\"}";
        HttpResponse<String> r = webhook("budget", late, FreightApi.hmac("budget-hook-secret-01", late));
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("duplicate"), "the poll already minted this exact event");
    }

    /**
     * LESSON 7 · BOUNDED ATTEMPTS END AT A PERSON, THROUGH A DOOR BUILT IN
     * ADVANCE.
     *
     * The carriers are unreachable entirely · a driver pointed at a dead
     * port · and the honest answer is that nobody knows whether a label
     * exists. Five passes, then 'stuck', announced, and only the repair
     * endpoint may move it: not to anywhere, but to what a human verified.
     * Resolving a shipment that is not stuck is refused, because a repair
     * path that works on healthy rows is a second way to break them.
     */
    @Test
    void lesson7_unknowable_shipments_park_for_a_human() throws Exception {
        UUID orderId = UUID.randomUUID();
        FreightConsumer.apply("order.fulfilled:" + orderId, orderFulfilled(orderId, 2));
        UUID shipmentId = shipmentOf(orderId);

        FreightDriver deaf = new FreightDriver("http://localhost:1");
        for (int i = 0; i < FreightDriver.MAX_ATTEMPTS; i++) deaf.runOnce(T0);
        assertEquals("stuck", stateOf(shipmentId));
        assertEquals(1, count("SELECT COUNT(*) FROM outbox WHERE event_key = 'shipment.stuck:" + shipmentId + "'"));

        HttpResponse<String> refused = resolve(UUID.randomUUID(), "failed");
        assertTrue(refused.statusCode() >= 400, "resolving a shipment that does not exist must refuse");

        // While it sits stuck, a carrier report cannot decide it either: the
        // machine refuses through the same door everything else uses. Without
        // the terminal guard in advance(), stuck ranks below every parcel
        // status and a signed webhook would march it to delivered, past the
        // human the state exists to summon.
        try (Connection c = FreightDb.open()) {
            c.setAutoCommit(false);
            assertEquals(Shipments.Advance.STALE,
                    Shipments.advance(c, shipmentId, "swift", "SW-ghost", "delivered", "SW-ghost:delivered", T0),
                    "a decided shipment is nobody's to reopen, not even a carrier's");
            c.commit();
        }
        assertEquals("stuck", stateOf(shipmentId));

        HttpResponse<String> ok = resolve(shipmentId, "failed");
        assertEquals(200, ok.statusCode());
        assertEquals("failed", stateOf(shipmentId));
        assertEquals(409, resolve(shipmentId, "delivered").statusCode(),
                "a resolved shipment is nobody's to move again");
    }

    /**
     * LESSON 8 · A POISON EVENT COSTS ITSELF, NEVER THE PARTITION.
     *
     * An order.fulfilled with no orderId will not improve however often it is
     * redelivered, and the wrong response is the obvious one: throw, abort
     * the batch, replay, forever, with every order behind it never shipping.
     * The consumer claims it, counts it, and steps past, and the count is on
     * the audit endpoint because "we skipped some" must never be a secret.
     */
    @Test
    void lesson8_a_poison_event_is_counted_and_stepped_past() throws Exception {
        long before = FreightConsumer.unactionable.get();
        String poison = "{\"type\":\"order.fulfilled\",\"eventKey\":\"order.fulfilled:poison\",\"tenant\":\""
                + TENANT + "\",\"variant\":\"v-focus-30\",\"qty\":1,\"at\":\"" + T0 + "\"}";

        assertFalse(FreightConsumer.apply("order.fulfilled:poison", poison));
        assertEquals(before + 1, FreightConsumer.unactionable.get());
        assertFalse(FreightConsumer.apply("order.fulfilled:poison", poison),
                "the claim was kept, so the redelivery does not even reach the counter");
        assertEquals(before + 1, FreightConsumer.unactionable.get());
        assertEquals(0, count("SELECT COUNT(*) FROM shipments"));
    }

    // ---------------------------------------------------------------- helpers

    /** Byte-for-byte the shape Orders.java announces, minus nothing: the
     *  consumer is tested against the producer's real contract, not a
     *  convenient paraphrase of it. */
    private static String orderFulfilled(UUID orderId, long qty) {
        return "{\"type\":\"order.fulfilled\",\"eventKey\":\"order.fulfilled:" + orderId
                + "\",\"orderId\":\"" + orderId + "\",\"tenant\":\"" + TENANT
                + "\",\"customer\":7,\"variant\":\"v-focus-30\",\"location\":\"MAD\",\"qty\":" + qty
                + ",\"amount\":\"89.00\",\"at\":\"" + T0 + "\"}";
    }

    private static void advance(String carrier, String tracking) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + sim.port() + "/carriers/" + carrier + "/advance"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"tracking\":\"" + tracking + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> webhook(String carrier, String body, String signature) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + "/webhooks/carrier/" + carrier))
                .header("X-Carrier-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> resolve(UUID shipmentId, String verdict) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + apiPort + "/api/freight/shipments/" + shipmentId + "/resolve"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"verdict\":\"" + verdict + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static UUID shipmentOf(UUID orderId) throws Exception {
        try (Connection c = FreightDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM shipments WHERE order_id = ?")) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a shipment for order " + orderId);
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static String stateOf(UUID shipmentId) throws Exception {
        return scalar("SELECT state FROM shipments WHERE id = '" + shipmentId + "'");
    }

    private static String trackingOf(UUID shipmentId) throws Exception {
        return scalar("SELECT tracking_ref FROM shipments WHERE id = '" + shipmentId + "'");
    }

    private static String stepState(UUID shipmentId, String carrier) throws Exception {
        return scalar("SELECT state FROM carrier_steps WHERE shipment_id = '" + shipmentId
                + "' AND carrier = '" + carrier + "'");
    }

    private static long count(String sql) throws Exception {
        return Long.parseLong(scalar(sql));
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = FreightDb.open(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            return rs.getString(1);
        }
    }
}
