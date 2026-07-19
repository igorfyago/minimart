package dev.minimart.commerce;

import dev.minimart.core.Db;
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
import java.util.List;
import java.util.UUID;

/**
 * THE SAGA DRIVER · the part that finally does something about it.
 *
 * minibank runs a real saga inside itself and has for a long time: a transfer
 * departs into a clearing account with an outbox event in one commit, Kafka
 * relays at least once, the applier arrives idempotently behind a txId, and a
 * missing destination refunds deterministically. Three parts · a durable record,
 * a relay, an actor · and the third is what makes it a saga rather than a
 * logbook.
 *
 * This seam had two of the three. RemoteSteps records that a call was made and
 * survives the call failing. Reconciler asks both services about one order and
 * names the disagreement in a sentence a person can act on. Then it stopped.
 * RemoteSteps.unsettled() was written, was correct, and was called by NOBODY, so
 * every abandoned hold this estate could detect sat detected until a human read
 * a report. That is a better place to be than not knowing. It is not a saga.
 *
 * WHY THIS IS THE DANGEROUS PART, STATED PLAINLY.
 *
 * A reporter that is wrong prints a wrong sentence, and the next thing that
 * happens is a person reads it. An actor that is wrong moves somebody's money on
 * the strength of an inference drawn from two systems that are, by construction,
 * currently disagreeing with each other. The temptation is a re-void: the step
 * says unknown, an unknown authorisation may be standing, minipay's void is
 * idempotent, so void it again and the estate self-heals. It is provably safe
 * right up until you ask WHOSE hold it is:
 *
 *     an unknown on an authorize means the request MAY HAVE LANDED, and the
 *     intent id is derived rather than minted · "pi_" + orderId · so the hold
 *     you are about to release is the same hold a retried checkout for that
 *     order would legitimately be using. Idempotence does not save you, because
 *     the second void is not a duplicate of the first. It is a different, later,
 *     wrong decision that the processor has no way to recognise as wrong.
 *
 * So the driver does not ask the journal what to do. The journal says only that
 * we do not know, and "we do not know" is never a mandate to move money. It asks
 * the two parties who actually know, at the moment it acts:
 *
 *     THE ORDER ROW says what minimart still wants. It is the authority on that
 *     and nothing else is.
 *     MINIPAY, ASKED FRESH, says what is actually standing over there. Not the
 *     journal's memory of what it once said, and not a status carried over from
 *     a reconciler report computed minutes ago · a stale status is precisely how
 *     you void a hold that came back to life in between.
 *
 * The journal only decides WHICH orders are worth asking about, and bounds how
 * often. It is the work queue. It is not the evidence.
 *
 * TWO ACTIONS ARE TAKEN. EVERYTHING ELSE IS LEFT FOR A PERSON, and the list of
 * refusals below is longer than the list of actions on purpose. An automated
 * actor on a money path that guesses is worse than one that does nothing, which
 * is exactly why the reporter shipped first and why this arrived second.
 */
public final class SagaDriver {

    /**
     * How many times the driver will take one step in hand before it stops.
     *
     * Five, because the failures worth retrying are transient · a processor
     * restarting, a network that dropped one request · and none of those last
     * five passes. What survives five passes is a disagreement about the world,
     * and retrying a disagreement is how a bounded repair becomes an unbounded
     * one that nobody is paged for.
     */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * How long one driver's claim keeps another off the same step.
     *
     * Comfortably longer than the three-second timeout on every call the driver
     * can make, so a live attempt is never overtaken by a second driver deciding
     * it must have died. Short enough that a crash costs one lease.
     */
    public static final Duration LEASE = Duration.ofMinutes(5);

    /** What the driver decided to do about one order. */
    public enum Verdict {
        /** The order is dead here and the hold is standing there: release it. */
        VOID_THE_HOLD,
        /** The money moved there and the goods never moved here: finish the job. */
        RESUME_THE_FULFIL,
        /** Not provably safe. Say so, act on nothing, leave it in the report. */
        LEAVE_IT
    }

    public record Taken(UUID orderId, Verdict verdict, boolean succeeded, String detail) {
        @Override public String toString() {
            return verdict + " · order " + orderId + (succeeded ? " · done" : " · FAILED")
                   + (detail == null ? "" : " · " + detail);
        }
    }

    /**
     * @param leftAlone one sentence per order the driver refused to act on. It is
     *   the most important field here. A driver whose report is only its
     *   successes teaches an operator that everything it did not mention was
     *   fine, and the whole design of this class is that most things are not
     *   its business.
     * @param unreachable orders not examined because minipay did not answer. Not
     *   a refusal and not a repair: an unanswered question, kept separate for
     *   the same reason Reconciler keeps it separate.
     */
    public record Report(int ordersExamined, int unreachable,
                         List<Taken> taken, List<String> leftAlone) {}

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private SagaDriver() {}

    /**
     * One pass. Safe to run on a timer, safe to run twice, safe to run beside
     * another copy of itself.
     */
    public static Report run(String tenant, Instant businessAt, int limit) throws SQLException {
        List<Taken> taken = new ArrayList<>();
        List<String> left = new ArrayList<>();
        int unreachable = 0;

        List<UUID> orders = ordersNeedingAttention(tenant, limit);
        for (UUID orderId : orders) {
            String orderState = stateOf(orderId);
            // Reachable only if an order was deleted under a live journal row.
            // The driver starts from orders BECAUSE acting requires knowing what
            // the shop wants, and a step with no order is the one case where
            // nothing anywhere knows. Reconciler names it as an ORPHAN_INTENT;
            // this cannot do better than say it is not touching it.
            if (orderState == null) {
                left.add("order " + orderId + " · a remote step with no order behind it, "
                         + "so nothing here knows what was wanted");
                continue;
            }

            String status = statusOf(Checkout.intentIdFor(orderId));
            // SILENCE IS NOT AN ANSWER AND IT IS CERTAINLY NOT A MANDATE. If an
            // unanswered GET read as "the payment is absent" the driver would
            // conclude, on the day minipay is down, that every hold in the shop
            // needs releasing. Doing nothing while the processor is dark is the
            // only correct behaviour and it is worth being loud about.
            if (status == null) { unreachable++; continue; }

            Verdict verdict = decide(orderState, status);
            if (verdict == Verdict.LEAVE_IT) {
                left.add("order " + orderId + " is '" + orderState + "' here and '" + status
                         + "' there · no action is provably safe, so this is a person's");
                continue;
            }

            String action = verdict == Verdict.VOID_THE_HOLD ? RemoteSteps.CANCEL : RemoteSteps.FULFIL;
            // THE CLAIM IS WHAT MAKES A SECOND COPY HARMLESS. Everything above
            // is a read and two drivers can and will reach this line together
            // with the same verdict; exactly one of them gets past it.
            if (!RemoteSteps.claim(orderId, action, Checkout.intentIdFor(orderId),
                                   businessAt, MAX_ATTEMPTS, LEASE)) {
                if (RemoteSteps.noteExhaustion(orderId, action, MAX_ATTEMPTS)) {
                    left.add("order " + orderId + " · " + action
                             + " gave up after " + MAX_ATTEMPTS + " attempts, left for a human");
                }
                continue;
            }
            taken.add(act(orderId, verdict, action, businessAt));
        }
        return new Report(orders.size(), unreachable, taken, left);
    }

    /**
     * THE ONLY TWO POSITIONS THIS ESTATE CAN REPAIR WITHOUT GUESSING.
     *
     * Deliberately shaped like Reconciler.classify, and deliberately much
     * smaller than it. classify names nine ways the two services can disagree.
     * Every one of the other seven has a plausible automatic fix and not one of
     * those fixes is provable from here, which is the argument this method is.
     *
     * WHAT IS REFUSED, AND WHY, BECAUSE THE REFUSALS ARE THE DESIGN:
     *
     *   reserved here, requires_capture there · the healthy pair. The journal
     *      may be unsettled because a capture failed, but nothing is stranded:
     *      the shop can simply ship the order again. Not the driver's business.
     *   reserved here, ANY unsettled authorize · THE HAZARD, stated at the top.
     *      The order is live, so the hold may be the one it is about to capture.
     *      The journal saying 'unknown' is not evidence about the order, and the
     *      order is the only thing that could authorise a void.
     *   fulfilled here, requires_capture there · the goods left and the money
     *      did not. Capturing is the obvious fix and it is the driver DECIDING
     *      TO CHARGE A CUSTOMER, which is a new movement of money rather than
     *      the completion of an old one. A person signs that.
     *   aborted here, succeeded there · the customer paid for nothing. The fix
     *      is a REFUND, which is likewise a new movement, and one this seam has
     *      no primitive for. Naming it is the honest limit.
     *   anything, declined · nothing is standing, so there is nothing to release.
     *   an intent minipay has never heard of · absent is a fine ending for a
     *      dead order and a mystery for a live one, and mysteries are not repaired.
     *
     * Both of the two that remain share the property that makes them safe: the
     * ORDER ROW has already reached a state that PROVES what the shop wants, and
     * minipay has just said, in this second, that it is holding the other half.
     */
    static Verdict decide(String orderState, String intentStatus) {
        // AN ABORTED ORDER IS TERMINAL, AND THIS IS THE LOAD-BEARING CLAIM.
        //
        // Three independent facts make it true, and the void is safe only
        // because all three are:
        //
        //   Orders.submit claims the order id as a LEDGER TRANSACTION ID before
        //   it does anything else, so that id is spent forever. A retried
        //   checkout for this order gets AlreadyProcessed and never reaches an
        //   authorisation. The retry that the hazard is about cannot happen to
        //   THIS order, and it is the derived id that makes that argument
        //   possible rather than a hope.
        //
        //   Orders.move only acts while the reservation row is 'held', and
        //   aborting released it. There is no path from aborted back to a state
        //   that wants money.
        //
        //   the intent is named "pi_" + orderId, so no OTHER order can be using
        //   this hold either. The derivation that lets the reconciler work
        //   without a mapping table is the same thing that makes this hold
        //   unambiguously this dead order's.
        //
        // A concurrent ship() could still race a capture against this void, and
        // that is survivable rather than prevented: minipay compare-and-swaps on
        // the status it re-reads after the issuer answers, the issuer refuses a
        // capture on a released hold, and the loser leaves both sides untouched
        // and lands in the reconciler. Capturing an aborted order is a caller
        // bug, and the estate's answer to it does not depend on this class.
        if ("aborted".equals(orderState) && "requires_capture".equals(intentStatus))
            return Verdict.VOID_THE_HOLD;

        // THE MONEY IS ALREADY THE MERCHANT'S AND THE CUSTOMER HAS NOTHING.
        //
        // minipay says succeeded, which is its own row about a payment named
        // after this order, and a succeeded capture cannot become uncaptured:
        // settle() refuses anything that is not requires_capture. So the capture
        // is a fact, not an inference, and the customer is owed these goods.
        //
        // Finishing is safe in the strongest available sense · THE WORST CASE IS
        // A NO-OP. Orders.fulfil takes the reservation row FOR UPDATE and does
        // nothing unless it is still 'held', so if the order was aborted between
        // the read above and the write, the units are already back on the shelf
        // and this declines to move them. The reservation row referees it, which
        // is the job it was given for the pooled-stock race and is the same job
        // here. Nothing is moved on the strength of the read being still true.
        //
        // Note also what this is NOT: it does not touch the processor. The only
        // remote step in this repair already happened. That is why it is the
        // safer of the two and why it is allowed to run on a live order at all.
        if ("reserved".equals(orderState) && "succeeded".equals(intentStatus))
            return Verdict.RESUME_THE_FULFIL;

        return Verdict.LEAVE_IT;
    }

    private static Taken act(UUID orderId, Verdict verdict, String action, Instant businessAt)
            throws SQLException {
        boolean ok;
        String detail;
        try {
            if (verdict == Verdict.VOID_THE_HOLD) {
                ok = Checkout.voidHold(orderId, businessAt);
                detail = ok ? "the hold was released" : "minipay would not release the hold";
                if (ok) {
                    // WE HAVE JUST LEARNED SOMETHING THE JOURNAL DID NOT KNOW.
                    // minipay was holding this, so the authorisation did land,
                    // whatever the row has said since the answer was lost. The
                    // step was never 'unknown' as a matter of fact, only as a
                    // matter of what we could see, and now we can see it. Saying
                    // so is what stops the driver examining this order forever.
                    RemoteSteps.finish(orderId, RemoteSteps.AUTHORIZE, RemoteSteps.State.OK,
                            "the driver found the hold standing at minipay, so the request did land");
                }
            } else {
                Orders.fulfil(orderId, businessAt);
                RemoteSteps.finish(orderId, RemoteSteps.CAPTURE, RemoteSteps.State.OK,
                        "the driver confirmed 'succeeded' at minipay, so the capture did land");
                RemoteSteps.finish(orderId, RemoteSteps.FULFIL, RemoteSteps.State.OK,
                        "resumed by the driver after the capture was confirmed");
                ok = true;
                detail = "the goods were released against a capture minipay confirmed";
            }
        } catch (SQLException | RuntimeException e) {
            // The attempt is already counted, so a repair that keeps throwing
            // walks to the ceiling and stops rather than running every pass
            // forever. Recorded and swallowed: one order the driver could not
            // repair must not end the pass for the others.
            RemoteSteps.finish(orderId, action, RemoteSteps.State.FAILED,
                    "driver attempt failed: " + e.getMessage());
            ok = false;
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            // The attempt is over, so the claim is too. Held any longer it would
            // ration this step to one try per lease instead of one per pass, and
            // the ceiling below would stop meaning what it says.
            RemoteSteps.releaseClaim(orderId, action);
        }
        // The ceiling is announced the moment it is reached rather than on the
        // pass after. An operator should not have to wait for a schedule to
        // learn that nothing is coming.
        if (!ok) RemoteSteps.noteExhaustion(orderId, action, MAX_ATTEMPTS);
        return new Taken(orderId, verdict, ok, detail);
    }

    /**
     * Orders carrying at least one step that did not finish cleanly.
     *
     * The work queue, and only the work queue. Starting from remote_steps is
     * what keeps this cheap · a healthy shop asks minipay nothing · and starting
     * from ORDERS is what keeps it honest, because the order row is the thing
     * every decision below is made on and a step without one cannot be decided.
     */
    private static List<UUID> ordersNeedingAttention(String tenant, int limit) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT o.id FROM orders o
                      WHERE o.tenant = ? AND o.payment_mode = 'psp'
                        AND EXISTS (SELECT 1 FROM remote_steps s
                                     WHERE s.order_id = o.id AND s.state <> 'ok')
                      ORDER BY o.business_at, o.id LIMIT ?""")) {
            ps.setString(1, tenant);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add((UUID) rs.getObject(1));
            }
        }
        return out;
    }

    private static String stateOf(UUID orderId) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT state FROM orders WHERE id = ?")) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    /**
     * What minipay says this intent's status is RIGHT NOW, or null if it would
     * not say.
     *
     * Reconciler asks the same question and the duplication is deliberate. Its
     * answer is a snapshot in a report that a person may read an hour later, and
     * the one property this driver cannot give up is that the status it acts on
     * was true a moment ago. Passing a report in here would make the decision
     * depend on how stale the caller's copy is, which is the difference between
     * releasing an abandoned hold and releasing a hold that came back to life.
     *
     * The null is load-bearing for the same reason it is in the reporter, and
     * more so: there, an unreachable processor produces a bad sentence. Here it
     * would produce a void.
     */
    private static String statusOf(String intentId) {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(Checkout.payBaseUrl + "/v1/payment_intents/" + intentId))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            // A NOTE ON WHICH READER THIS SHOULD BE USING. str() answers with the
            // first occurrence of a key anywhere in the document, which is a bet
            // that minipay never describes the card or the last attempt beside
            // the payment · and any of those may carry a status of its own. A
            // reader that walks the object's own top-level members is the
            // stricter tool and this call wants it, for a worse reason than the
            // reconciler does: there a misread status prints a wrong sentence,
            // here it DECIDES WHETHER TO RELEASE A HOLD. str() answers with the
            // first occurrence of a key anywhere it appears, nested objects and
            // quoted text alike, so a payment_method or an echoed request
            // carrying its own "status" could speak for the intent. This body is
            // minipay's to shape, not ours, which is exactly the case text()
            // exists for.
            String status = Json.text(r.body(), "status");
            // minipay answers 200 with an error body for an id it does not know.
            return status == null ? "absent" : status;
        } catch (Exception e) {
            return null;
        }
    }

    public static String toJson(Report r) {
        StringBuilder b = new StringBuilder("{\"orders_examined\":").append(r.ordersExamined())
                .append(",\"unreachable\":").append(r.unreachable())
                .append(",\"acted\":[");
        boolean first = true;
        for (Taken t : r.taken()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"order\":\"").append(t.orderId())
             .append("\",\"verdict\":\"").append(t.verdict())
             .append("\",\"succeeded\":").append(t.succeeded())
             .append(",\"detail\":\"").append(Json.esc(t.detail())).append("\"}");
        }
        b.append("],\"left_alone\":[");
        first = true;
        for (String s : r.leftAlone()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(Json.esc(s)).append('"');
        }
        return b.append("]}").toString();
    }
}
