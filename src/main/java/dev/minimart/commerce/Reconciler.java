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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE CROSS-SERVICE RECONCILER · the audit that spans the network.
 *
 * minimart's three audits are excellent and they are all local. Sum-zero says
 * this ledger balances. Cache drift says this ledger's cached balances match its
 * own entries. The reserved audit says this warehouse's pooled stock matches
 * this database's live reservations. minipay has its own set, equally good, and
 * equally local.
 *
 * NOT ONE OF THEM CAN SEE THE FAILURE THAT MATTERS MOST HERE. Void the hold,
 * fail, put the goods back anyway: minimart's books balance, because only stock
 * moved and it moved correctly. minipay's books balance, because nothing moved
 * at all. Every audit on both sides is green, and a real customer's credit is
 * held against an order that no longer exists. RaceLessonTest named this exact
 * shape one database over · "the books still balance, which is the trap" · and
 * the lesson was never carried across the seam.
 *
 * So this asks the one question no local audit can even phrase: DO THE TWO
 * SERVICES AGREE ABOUT THIS ORDER?
 *
 * It needs no join table and no lookup to do it, because the id chain is already
 * deterministic: Checkout.intentIdFor(orderId) is "pi_" + orderId, so from an
 * order minimart can always compute the name of the payment it should have.
 * That is worth more than it looks · a reconciler that depends on a mapping
 * table cannot detect anything the mapping table itself got wrong.
 *
 * IT REPORTS. IT DOES NOT HEAL.
 *
 * Every discrepancy below has a plausible automatic fix, and every one of those
 * fixes is a way to move somebody's money on the strength of an inference drawn
 * from two systems that are, by construction, currently disagreeing. A
 * reconciler that silently corrects is worse than one that names the problem,
 * because the correction is unreviewable and the naming is not. The output of
 * this class is a list of sentences a human can act on.
 */
public final class Reconciler {

    /**
     * What kind of disagreement. Named for the MONEY consequence rather than
     * for the state pair that produced it, because the state pair is what an
     * engineer sees and the money is what the customer feels.
     */
    public enum Kind {
        /** Cancelled here, still authorised there. The customer's credit is held
         *  against an order that no longer exists. This is the failed void. */
        ABORTED_HOLD_STANDING,
        /** Cancelled here, CAPTURED there. The customer paid for nothing. */
        ABORTED_BUT_CAPTURED,
        /** Captured there, never fulfilled here. Paid for goods on the shelf.
         *  This is the capture whose local half failed. */
        CAPTURED_NOT_FULFILLED,
        /** Shipped here, only authorised there. The goods left, the money did
         *  not, and an authorisation expires on its own at the issuer. */
        SHIPPED_UNCAPTURED,
        /** Shipped here, voided or never charged there. The goods are gone. */
        SHIPPED_UNPAID,
        /** Live here, voided there. The order is walking around unfunded. */
        RESERVED_BUT_VOIDED,
        /** Live here, declined there. place() should have compensated, and did not. */
        RESERVED_ON_DECLINE,
        /** minimart recorded an intent id that minipay has never heard of. */
        MISSING_INTENT,
        /** minipay is holding a payment for an order minimart does not have.
         *  The timeout case seen from the other end. */
        ORPHAN_INTENT
    }

    public record Discrepancy(Kind kind, UUID orderId, String intentId,
                              String orderState, String intentStatus, String detail) {
        /** One line, in the register an operator reads at three in the morning. */
        @Override public String toString() {
            return kind + " · order " + orderId + " is '" + orderState + "' here, intent " + intentId
                   + " is '" + intentStatus + "' there" + (detail == null ? "" : " · " + detail);
        }
    }

    /**
     * @param unreachable how many orders could not be checked because minipay
     *   did not answer. IT IS NOT A DISCREPANCY, and keeping the two apart is
     *   deliberate: a processor that is down would otherwise be reported as
     *   every order in the shop being broken, which is the fastest way to teach
     *   an operator to ignore this report.
     */
    public record Report(int ordersChecked, int intentsChecked, int unreachable,
                         List<Discrepancy> discrepancies) {
        /** True only when the two services were fully compared AND agreed. */
        public boolean agreed() { return discrepancies.isEmpty() && unreachable == 0; }
    }

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private Reconciler() {}

    /** Compare every recent psp order against the payment it should have. */
    public static Report run(String tenant, int limit) throws SQLException {
        List<Discrepancy> found = new ArrayList<>();
        int unreachable = 0;

        // Only 'psp' orders have a counterpart across the seam. A wallet order
        // is settled entirely inside minimart's own ledger, so asking minipay
        // about one would invent a discrepancy out of a design decision.
        record Order(UUID id, String state, String intentId) {}
        List<Order> orders = new ArrayList<>();
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, state, payment_intent_id FROM orders
                     WHERE tenant = ? AND payment_mode = 'psp'
                     ORDER BY business_at DESC, id LIMIT ?""")) {
            ps.setString(1, tenant);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) orders.add(new Order((UUID) rs.getObject(1), rs.getString(2), rs.getString(3)));
            }
        }

        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Order o : orders) {
            String intentId = Checkout.intentIdFor(o.id());
            seen.put(intentId, true);
            String status = statusOf(intentId);
            if (status == null) { unreachable++; continue; }     // asked, not answered
            Kind kind = classify(o.state(), status, o.intentId() != null);
            if (kind != null) found.add(new Discrepancy(kind, o.id(), intentId,
                    o.state(), status, journalNote(o.id())));
        }

        // THE OTHER DIRECTION, which the loop above structurally cannot see: a
        // payment minipay is holding for an order minimart has no row for. That
        // is what a lost authorisation response looks like from the far side,
        // and it is invisible to any check that starts from minimart's orders.
        int intents = 0;
        List<String> orphans;
        try {
            orphans = intentsAt(tenant, limit);
            intents = orphans.size();
        } catch (Exception e) {
            return new Report(orders.size(), 0, unreachable + 1, found);
        }
        for (String intentId : orphans) {
            if (seen.containsKey(intentId)) continue;
            UUID orderId = orderIdOf(intentId);
            if (orderId == null) continue;              // not an id this shop mints
            if (!orderExists(orderId)) {
                found.add(new Discrepancy(Kind.ORPHAN_INTENT, orderId, intentId,
                        "absent", "present", "minipay holds a payment for an order this shop has no record of"));
            }
        }
        return new Report(orders.size(), intents, unreachable, found);
    }

    /**
     * The truth table of the seam, in one place so it can be read as a whole.
     *
     * null means the pair is legitimate. Everything else is money in a position
     * neither service can notice on its own.
     */
    static Kind classify(String orderState, String intentStatus, boolean intentRecorded) {
        return switch (orderState) {
            case "reserved" -> switch (intentStatus) {
                case "requires_capture" -> null;                       // healthy: authorised, awaiting the outcome
                case "succeeded"        -> Kind.CAPTURED_NOT_FULFILLED;
                case "canceled"         -> Kind.RESERVED_BUT_VOIDED;
                case "declined"         -> Kind.RESERVED_ON_DECLINE;
                // An order still being placed has no intent yet, and that is a
                // moment rather than a fault. One that RECORDED an intent id and
                // then lost it is a fault.
                default -> intentRecorded ? Kind.MISSING_INTENT : null;
            };
            case "fulfilled" -> switch (intentStatus) {
                case "succeeded"        -> null;                       // healthy: shipped and captured
                case "requires_capture" -> Kind.SHIPPED_UNCAPTURED;
                default                 -> Kind.SHIPPED_UNPAID;
            };
            case "aborted" -> switch (intentStatus) {
                // canceled, declined and absent are all correct endings for an
                // order that died: the hold was released, refused, or never made.
                case "requires_capture" -> Kind.ABORTED_HOLD_STANDING;
                case "succeeded"        -> Kind.ABORTED_BUT_CAPTURED;
                default                 -> null;
            };
            default -> null;
        };
    }

    /**
     * What minipay says this intent's status is, or null if it would not say.
     *
     * The null is load-bearing. An unreachable processor must NEVER be read as
     * "the payment is absent", because absent is one of the answers that clears
     * an aborted order, and a reconciler that reports everything healthy when
     * the far side is down is worse than no reconciler at all.
     */
    private static String statusOf(String intentId) {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(Checkout.payBaseUrl + "/v1/payment_intents/" + intentId))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            String status = Json.str(r.body(), "status");
            // minipay answers 200 with an error body for an id it does not know,
            // which is how "no such payment" arrives here.
            return status == null ? "absent" : status;
        } catch (Exception e) {
            return null;
        }
    }

    /** Recent intent ids minipay holds for this merchant. */
    private static List<String> intentsAt(String tenant, int limit) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(Checkout.payBaseUrl + "/v1/list?limit=" + limit))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());

        // THIS ENDPOINT ANSWERS WITH AN ARRAY, AND ANYTHING ELSE IS NOT AN
        // ANSWER. An error body or a wrapped envelope would otherwise be read
        // as one enormous row and the whole page would silently shrink to a
        // single comparison. Throwing sends it to run()'s unreachable count,
        // which is the difference between "not checked" and "checked and fine".
        String body = r.body() == null ? "" : r.body().stripLeading();
        if (!body.startsWith("[")) throw new IllegalStateException("expected a JSON array of payments");

        // A ROW IS READ AS A ROW, NOT AS A POSITION IN TWO LISTS.
        //
        // This used to collect every "id" in the document and every "merchant"
        // in the document and pair them by index, which is a bet that minipay
        // emits exactly one of each key per row, forever. The bet loses quietly:
        // one row carrying a nested object with its own id, or one row missing a
        // key another row has, shifts every later pairing by one, and from then
        // on this compares one payment's merchant against another payment's id.
        //
        // The consequence is the worst available. A tool built to find money
        // stranded between two services starts inventing discrepancies that are
        // not real and, in the other direction, agreeing about pairs it never
        // actually compared. It would also attribute one merchant's payments to
        // another, which is a leak as well as a lie. Nothing about the failure
        // looks like a failure: the report still reads perfectly.
        //
        // So the array is split into its objects first and each row is asked its
        // own questions, using a reader that walks the row's own members rather
        // than searching its text. Splitting alone would not have been enough.
        // Json.str answers with the FIRST occurrence of a key in what it is
        // given, so a row whose nested payment method is written before its own
        // id hands back the card's id as the payment's. That is the same bet as
        // before in a smaller coat: no longer "minipay writes every row alike",
        // merely "minipay writes the keys of a row in the order we expect".
        // Neither is ours to make, and this one is not visible when it breaks.
        List<String> out = new ArrayList<>();
        for (String row : rows(body)) {
            String id = member(row, "id");
            // A row that will not say whose it is cannot be attributed to this
            // merchant. Skipping it is the honest answer: the alternative is to
            // guess, and guessing is what this class exists to stop.
            if (id == null || !tenant.equals(member(row, "merchant"))) continue;
            out.add(id);
        }
        return out;
    }

    /**
     * The string a key holds at the TOP LEVEL of one row, or null.
     *
     * This belongs beside str() in core.Json and sits here because this change
     * is scoped to the reconciler. The duplication is at least the house's own
     * kind: the codec is already deliberately copied per service so that a
     * merchant and its processor share no model object.
     */
    static String member(String row, String key) {
        if (row == null) return null;
        int i = ws(row, 0);
        if (i >= row.length() || row.charAt(i) != '{') return null;
        i = ws(row, i + 1);
        while (i < row.length() && row.charAt(i) == '"') {
            int nameEnd = endOfString(row, i);
            if (nameEnd < 0) return null;
            String name = row.substring(i + 1, nameEnd);
            i = ws(row, nameEnd + 1);
            if (i >= row.length() || row.charAt(i) != ':') return null;
            i = ws(row, i + 1);
            int valueEnd = endOfValue(row, i);
            if (valueEnd < 0) return null;
            if (name.equals(key)) {
                if (row.charAt(i) != '"') return null;          // asked for a name, given a number
                return unescape(row.substring(i + 1, valueEnd - 1));
            }
            // Either another member follows, or the row ended without the key
            // and the answer is that this row does not have one.
            i = ws(row, valueEnd);
            if (i >= row.length() || row.charAt(i) != ',') return null;
            i = ws(row, i + 1);
        }
        return null;
    }

    private static int ws(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** The index of the quote that closes the string starting at i. */
    private static int endOfString(String s, int i) {
        for (int j = i + 1; j < s.length(); j++) {
            char ch = s.charAt(j);
            if (ch == '\\') { j++; continue; }                  // an escaped quote is not the end
            if (ch == '"') return j;
        }
        return -1;
    }

    /** One past the end of the value starting at i, whatever kind it is. */
    private static int endOfValue(String s, int i) {
        if (i >= s.length()) return -1;
        char ch = s.charAt(i);
        if (ch == '"') { int e = endOfString(s, i); return e < 0 ? -1 : e + 1; }
        if (ch == '{' || ch == '[') {
            int depth = 0;
            for (int j = i; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '"') { int e = endOfString(s, j); if (e < 0) return -1; j = e; continue; }
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') { if (--depth == 0) return j + 1; }
            }
            return -1;
        }
        int j = i;
        while (j < s.length() && ",}] \t\r\n".indexOf(s.charAt(j)) < 0) j++;
        return j == i ? -1 : j;
    }

    /** As core.Json's str() does it: drop the backslash and keep what follows. */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) { b.append(s.charAt(++i)); continue; }
            b.append(ch);
        }
        return b.toString();
    }

    /**
     * The objects of a JSON array, each returned as its own document.
     *
     * Brace counting rather than a parser, in keeping with the codec this
     * service already has, and string-aware because a brace inside a quoted
     * value is text and counting it would tear a row in half.
     */
    static List<String> rows(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        int depth = 0, start = -1;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') {                                  // step over the whole string
                for (i++; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (c == '\\') { i++; continue; }          // an escaped quote is not the end
                    if (c == '"') break;
                }
                continue;
            }
            if (ch == '{') { if (depth++ == 0) start = i; }
            else if (ch == '}' && depth > 0 && --depth == 0 && start >= 0) {
                out.add(json.substring(start, i + 1));
                start = -1;
            }
        }
        return out;
    }

    /** The order behind an intent id, reversing intentIdFor, or null if the id
     *  was not minted by this shop's convention. */
    static UUID orderIdOf(String intentId) {
        if (intentId == null || !intentId.startsWith("pi_")) return null;
        try { return UUID.fromString(intentId.substring(3)); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static boolean orderExists(UUID orderId) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM orders WHERE id = ?")) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /**
     * What minimart's own journal remembers about this order's remote steps.
     *
     * CORROBORATION, NOT EVIDENCE. The discrepancy is established by the two
     * services disagreeing, and it would be established with an empty journal
     * too. What the journal adds is the WHY: an operator who is told the hold is
     * still standing wants to know whether the void was refused, never attempted,
     * or attempted with no answer, and those three call for three different
     * responses.
     */
    private static String journalNote(UUID orderId) throws SQLException {
        try (Connection c = Db.open()) {
            List<RemoteSteps.Step> steps = RemoteSteps.forOrder(c, orderId);
            StringBuilder b = new StringBuilder();
            for (RemoteSteps.Step s : steps) {
                if (s.state() == RemoteSteps.State.OK) continue;
                if (!b.isEmpty()) b.append("; ");
                b.append(s.action()).append('=').append(s.state().name().toLowerCase());
                if (s.detail() != null) b.append(" (").append(s.detail()).append(')');
            }
            return b.isEmpty() ? null : "journal: " + b;
        }
    }

    public static String toJson(Report r) {
        StringBuilder b = new StringBuilder("{\"orders_checked\":").append(r.ordersChecked())
                .append(",\"intents_checked\":").append(r.intentsChecked())
                .append(",\"unreachable\":").append(r.unreachable())
                .append(",\"agreed\":").append(r.agreed())
                .append(",\"discrepancies\":[");
        boolean first = true;
        for (Discrepancy d : r.discrepancies()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"kind\":\"").append(d.kind())
             .append("\",\"order\":\"").append(d.orderId())
             .append("\",\"intent\":\"").append(Json.esc(d.intentId()))
             .append("\",\"order_state\":\"").append(Json.esc(d.orderState()))
             .append("\",\"intent_status\":\"").append(Json.esc(d.intentStatus()))
             .append("\",\"detail\":\"").append(Json.esc(d.detail())).append("\"}");
        }
        return b.append("]}").toString();
    }
}
