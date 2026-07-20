package dev.minifreight;

import dev.minimart.core.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE FULFILMENT SAGA'S ACTOR · minibank's SagaDriver lesson, pointed at a
 * carrier instead of a processor.
 *
 * The shape is the same because the danger is the same: a remote call whose
 * outcome is unknown, and an automated actor deciding what to do about it.
 * The rule carried over unchanged is the one that matters:
 *
 *     AN UNKNOWN IS NEVER RE-FIRED BLIND, AND NEVER ABANDONED BLIND.
 *
 * The request id a carrier sees is minted from (shipment, carrier), so
 * retrying THE SAME carrier is the same request and its idempotency answers
 * for us. But falling to the NEXT carrier is not a retry, it is a second
 * opinion about a parcel that may already have a label, and budget's habit of
 * writing the label before failing the response makes that concrete: fall
 * through on an unknown and one parcel ships twice, on two labels, both paid
 * for. So before the ladder moves, the driver asks the carrier that went
 * quiet what it actually did:
 *
 *     ACCEPTED   · adopt the label. The parcel was never ours to re-route.
 *     NOT_FOUND  · the request never landed, and the SAME request is safe to
 *                  fire again, at the same carrier, under the same id.
 *     UNREACHABLE· ask again next pass, a bounded number of times, and then
 *                  hand the shipment to a person as 'stuck'. Not 'failed',
 *                  because failed is a verdict and nobody here has evidence.
 *
 * The journal row is committed BEFORE the call, every time. A step that reads
 * 'requested' after a crash therefore means "the call may have been made",
 * and is treated exactly like 'unknown' · fresh status first, act second.
 *
 * Steps run each shipment under a lease (claimed_until, compare-and-set in
 * its own committed step), because two drivers reading 'requested' at the
 * same moment would otherwise both dial the carrier, and the journal cannot
 * referee a race it is told about afterwards.
 */
public final class FreightDriver {

    /** The carriers freight is onboarded with, in preference order. Config,
     *  not discovery: adding a carrier is an onboarding event with secrets
     *  and contracts attached, never something a driver infers at runtime. */
    public static final List<String> LADDER = List.of("swift", "budget");

    /** Same bound, same reasoning as minibank's SagaDriver: what survives
     *  five passes is not transient, and retrying a disagreement forever is
     *  an outage with better manners. */
    public static final int MAX_ATTEMPTS = 5;

    static final Duration LEASE = Duration.ofSeconds(30);

    private final String carriersBase;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public FreightDriver(String carriersBase) {
        this.carriersBase = carriersBase;
    }

    // ------------------------------------------------------------- the pass

    /** One deterministic pass over everything needing attention. Returns how
     *  many shipments were acted on, so a test can drive the saga tick by
     *  tick and a compressed run can watch it converge. */
    public int runOnce(Instant at) throws SQLException {
        int acted = 0;
        for (Work w : needingAttention()) {
            if (!claim(w.id())) continue;              // another driver has it
            try {
                switch (w.state()) {
                    case "requested" -> labelPass(w, at);
                    case "labelled", "in_transit" -> pollPass(w, at);
                }
                acted++;
                release(w.id());                       // the lease guards a live
                                                       // attempt, not a cool-down
            } catch (Exception e) {
                // this shipment's pass died mid-flight; the lease is left to
                // EXPIRE rather than be released, because a crash between a
                // remote call and its journal entry is exactly the moment a
                // second driver must not walk in
            }
        }
        return acted;
    }

    private record Work(UUID id, String state, long qty, String destination, int attempts) {}

    private List<Work> needingAttention() throws SQLException {
        List<Work> out = new ArrayList<>();
        try (Connection c = FreightDb.open();
             PreparedStatement ps = c.prepareStatement("""
                SELECT id, state, qty, destination, attempts FROM shipments
                WHERE state IN ('requested', 'labelled', 'in_transit')
                  AND (claimed_until IS NULL OR claimed_until < now())
                ORDER BY created_at LIMIT 50""")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Work((UUID) rs.getObject(1), rs.getString(2),
                        rs.getLong(3), rs.getString(4), rs.getInt(5)));
            }
        }
        return out;
    }

    private void release(UUID shipmentId) throws SQLException {
        try (Connection c = FreightDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE shipments SET claimed_until = NULL WHERE id = ?")) {
            ps.setObject(1, shipmentId);
            ps.executeUpdate();
        }
    }

    /** Compare-and-set on the lease, in its own committed step, BEFORE any
     *  remote call. Losing the race here is normal and costs nothing. */
    private boolean claim(UUID shipmentId) throws SQLException {
        try (Connection c = FreightDb.open();
             PreparedStatement ps = c.prepareStatement("""
                UPDATE shipments SET claimed_until = now() + ?::interval
                WHERE id = ? AND (claimed_until IS NULL OR claimed_until < now())""")) {
            ps.setString(1, LEASE.toSeconds() + " seconds");
            ps.setObject(2, shipmentId);
            return ps.executeUpdate() == 1;
        }
    }

    // -------------------------------------------------------- getting a label

    private void labelPass(Work w, Instant at) throws SQLException {
        Map<String, Step> steps = stepsFor(w.id());

        for (String carrier : LADDER) {
            Step step = steps.get(carrier);

            if (step == null) {
                journal(w.id(), carrier, at);          // committed before the wire
                if (!callAndRecord(w, carrier, at)) return;   // unknown · stop the ladder
                if (labelled(w.id())) return;          // accepted · done
                continue;                              // rejected · next carrier
            }
            switch (step.state()) {
                case "accepted" -> {
                    // The label exists; a crash may have eaten the advance.
                    // advance() dedupes on the event key, so repeating it is free.
                    adopt(w.id(), carrier, step.detail(), at);
                    return;
                }
                case "rejected" -> { /* a real no · the ladder may move */ }
                case "requested", "unknown" -> {
                    // The call may have been made. Fresh answer first.
                    Answer answer = statusOf(carrier, requestId(w.id(), carrier));
                    switch (answer.kind()) {
                        case "accepted" -> { adopt(w.id(), carrier, answer.tracking(), at); return; }
                        case "not_found" -> {
                            // never landed · the same request, same id, is safe
                            if (!callAndRecord(w, carrier, at)) return;
                            if (labelled(w.id())) return;
                        }
                        default -> { bumpOrPark(w, at); return; }   // still unreachable
                    }
                }
            }
        }
        // Every carrier on the ladder said an actual no. That is a verdict,
        // and the one terminal freight is entitled to reach on its own.
        try (Connection c = FreightDb.open()) {
            c.setAutoCommit(false);
            Shipments.fail(c, w.id(), "every onboarded carrier rejected the parcel", at);
            c.commit();
        }
    }

    /** POST the label request. True means the ladder may continue (a definite
     *  answer arrived); false means the outcome is unknown and the ladder
     *  must freeze exactly where it is. */
    private boolean callAndRecord(Work w, String carrier, Instant at) throws SQLException {
        String reqId = requestId(w.id(), carrier);
        Response r = post(carriersBase + "/carriers/" + carrier + "/labels",
                Json.obj("requestId", reqId, "shipmentId", w.id().toString(),
                        "destination", w.destination(), "qty", String.valueOf(w.qty())));

        if (r.code() == 200 && "accepted".equals(Json.str(r.body(), "status"))) {
            adopt(w.id(), carrier, Json.str(r.body(), "tracking"), at);
            return true;
        }
        if (r.code() == 200 && "rejected".equals(Json.str(r.body(), "status"))) {
            mark(reqId, "rejected", Json.str(r.body(), "reason"));
            return true;
        }
        mark(reqId, "unknown", "http " + r.code());
        bumpOrPark(w, at);
        return false;
    }

    /** The label is real: record who has the parcel and move the machine, in
     *  one transaction, through the same door every other report uses. */
    private void adopt(UUID shipmentId, String carrier, String tracking, Instant at) throws SQLException {
        try (Connection c = FreightDb.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE carrier_steps SET state = 'accepted', detail = ?, at = now() WHERE request_id = ?")) {
                ps.setString(1, tracking);
                ps.setString(2, requestId(shipmentId, carrier));
                ps.executeUpdate();
            }
            Shipments.advance(c, shipmentId, carrier, tracking, "labelled", tracking + ":labelled", at);
            c.commit();
        }
    }

    /** One more pass ended in "we do not know". Bounded, and the bound has a
     *  destination: a person, told by event, holding a repair path that was
     *  designed rather than improvised. */
    private void bumpOrPark(Work w, Instant at) throws SQLException {
        try (Connection c = FreightDb.open()) {
            c.setAutoCommit(false);
            int attempts;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE shipments SET attempts = attempts + 1, updated_at = now() WHERE id = ? RETURNING attempts")) {
                ps.setObject(1, w.id());
                try (ResultSet rs = ps.executeQuery()) { rs.next(); attempts = rs.getInt(1); }
            }
            if (attempts >= MAX_ATTEMPTS) {
                Shipments.stuck(c, w.id(),
                        "outcome unknown after " + attempts + " passes; a label may exist at the carrier", at);
            }
            c.commit();
        }
    }

    // ------------------------------------------------- watching the parcel

    /**
     * The webhookless path. A carrier that goes quiet has not stopped moving
     * parcels, it has stopped telling us, and those are different failures.
     * The poll mints the SAME event key a webhook would have, so whichever
     * report arrives second collapses into a duplicate instead of a second
     * announcement.
     */
    private void pollPass(Work w, Instant at) throws SQLException {
        Step accepted = stepsFor(w.id()).values().stream()
                .filter(s -> "accepted".equals(s.state())).findFirst().orElse(null);
        if (accepted == null) return;                      // nothing to ask about

        Answer answer = statusOf(accepted.carrier(), requestId(w.id(), accepted.carrier()));
        if (!"accepted".equals(answer.kind()) || answer.parcel() == null) return;   // quiet is survivable

        if (Shipments.rank(answer.parcel()) > Shipments.rank(w.state())) {
            try (Connection c = FreightDb.open()) {
                c.setAutoCommit(false);
                Shipments.advance(c, w.id(), accepted.carrier(), accepted.detail(),
                        answer.parcel(), accepted.detail() + ":" + answer.parcel(), at);
                c.commit();
            }
        }
    }

    // ------------------------------------------------------------ the journal

    private record Step(String carrier, String state, String detail) {}

    static String requestId(UUID shipmentId, String carrier) {
        return "lbl_" + shipmentId + "_" + carrier;
    }

    private Map<String, Step> stepsFor(UUID shipmentId) throws SQLException {
        Map<String, Step> out = new HashMap<>();
        try (Connection c = FreightDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT carrier, state, detail FROM carrier_steps WHERE shipment_id = ?")) {
            ps.setObject(1, shipmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), new Step(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return out;
    }

    private void journal(UUID shipmentId, String carrier, Instant at) throws SQLException {
        try (Connection c = FreightDb.open();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO carrier_steps(shipment_id, carrier, request_id, state)
                VALUES (?,?,?, 'requested') ON CONFLICT (request_id) DO NOTHING""")) {
            ps.setObject(1, shipmentId);
            ps.setString(2, carrier);
            ps.setString(3, requestId(shipmentId, carrier));
            ps.executeUpdate();
        }
    }

    private void mark(String requestId, String state, String detail) throws SQLException {
        try (Connection c = FreightDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE carrier_steps SET state = ?, detail = ?, at = now() WHERE request_id = ?")) {
            ps.setString(1, state);
            ps.setString(2, detail);
            ps.setString(3, requestId);
            ps.executeUpdate();
        }
    }

    private boolean labelled(UUID shipmentId) throws SQLException {
        try (Connection c = FreightDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT state FROM shipments WHERE id = ?")) {
            ps.setObject(1, shipmentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && Shipments.rank(rs.getString(1)) >= Shipments.rank("labelled");
            }
        }
    }

    /** Production: a virtual thread, one pass at a time, politely. */
    public void runLoop(long everyMillis) {
        Thread.ofVirtual().name("freight-driver").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    runOnce(Instant.now());
                    Thread.sleep(everyMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    try { Thread.sleep(everyMillis); } catch (InterruptedException i) { Thread.currentThread().interrupt(); }
                }
            }
        });
    }

    // ------------------------------------------------------------- the wire

    private record Response(int code, String body) {}
    private record Answer(String kind, String tracking, String parcel) {}

    private Answer statusOf(String carrier, String requestId) {
        Response r = get(carriersBase + "/carriers/" + carrier + "/labels/" + requestId);
        if (r.code() != 200) return new Answer("unreachable", null, null);
        String status = Json.str(r.body(), "status");
        if ("accepted".equals(status)) return new Answer("accepted", Json.str(r.body(), "tracking"), Json.str(r.body(), "parcel"));
        if ("not_found".equals(status)) return new Answer("not_found", null, null);
        if ("rejected".equals(status)) return new Answer("rejected", null, null);
        return new Answer("unreachable", null, null);
    }

    private Response post(String url, String body) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (Exception e) {
            return new Response(0, "");
        }
    }

    private Response get(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (Exception e) {
            return new Response(0, "");
        }
    }
}
