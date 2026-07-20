package dev.minipay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Reconciler;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WHAT THE RECONCILER IS ALLOWED TO ASSUME ABOUT MINIPAY'S ROWS.
 *
 * The reconciler lives in minimart and the shape it reads is minipay's, which
 * is why this lesson sits beside the processor's others: the thing under test is
 * an OUTPUT CONTRACT, and a contract is best examined from the side that has to
 * honour it. minipay is free to add a field to its list endpoint. Nobody would
 * think to ask the merchant's reconciler for permission, and nobody should have
 * to.
 *
 * The reconciler is the one tool in this system that exists to find money
 * stranded between two services, and it was reading minipay's list by collecting
 * every "id" in the document and every "merchant" in the document and pairing
 * them BY POSITION. That is a bet that every row carries exactly one of each key
 * for as long as both services live.
 *
 * The day the bet loses is the day minipay starts describing the card it used.
 * One nested object with its own id shifts every later pairing by one, and from
 * there the reconciler compares one payment's merchant against another payment's
 * id. The audit does not crash and does not look wrong: it simply starts
 * inventing discrepancies that are not real and, in the other direction, quietly
 * agreeing about pairs it never compared. An audit that is confidently wrong is
 * worse than no audit, because somebody acts on it at three in the morning.
 */
class ReconcilerRowsLessonTest {

    static HttpServer pay;
    static final int PAY_PORT = 18215;
    static final Instant T0 = Instant.parse("2027-06-01T00:00:00Z");

    /** A tenant of its own, so this lesson reads whatever is in the shared
     *  orders table without disturbing it. Every payment below is therefore an
     *  orphan, which is exactly the lens needed: the report names precisely the
     *  ids that came back from the list. */
    static final String TENANT = "helix-rows";
    static final String RIVAL = "rival-shop";
    static final String AMBIGUOUS = "helix-ambiguous";

    static final String WALLET_INTENT = "pi_" + UUID.randomUUID();
    static final String CARD_INTENT = "pi_" + UUID.randomUUID();
    static final String RIVAL_INTENT = "pi_" + UUID.randomUUID();

    static final String PLAIN_INTENT = "pi_" + UUID.randomUUID();
    static final String FIRST_ID = "pi_" + UUID.randomUUID();
    static final String SECOND_ID = "pi_" + UUID.randomUUID();

    static String previousPayBaseUrl;

    @BeforeAll
    static void boot() throws Exception {
        Migrate.bootstrap();
        pay = HttpServer.create(new InetSocketAddress(PAY_PORT), 0);
        pay.createContext("/v1/list", ReconcilerRowsLessonTest::list);
        pay.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        pay.start();
        previousPayBaseUrl = Checkout.payBaseUrl;
        Checkout.payBaseUrl = "http://localhost:" + PAY_PORT;
    }

    @AfterAll
    static void stop() {
        Checkout.payBaseUrl = previousPayBaseUrl;
        if (pay != null) pay.stop(0);
    }

    /**
     * LESSON · A ROW IS READ AS A ROW, NOT AS A POSITION IN TWO LISTS.
     *
     * The list below is entirely well formed and entirely reasonable. Three
     * payments: a wallet one, a card one that says which card, and one belonging
     * to a different merchant altogether. The only thing that is new is the
     * nested payment method, and it carries an id because payment methods have
     * ids.
     *
     * That single extra id is enough. Paired by position, the merchant column
     * runs out before the id column does, the last payment is matched against
     * nothing, and a stranger's payment is reported to this merchant as an
     * orphan of theirs. The fix is not a cleverer scanner: it is to stop reading
     * columns and start reading rows.
     */
    @Test
    void lesson_a_nested_id_in_one_row_does_not_shift_every_row_after_it() throws Exception {
        Reconciler.Report r = Reconciler.run(TENANT, 100);

        assertEquals(0, r.ordersChecked(),
                "this tenant has no orders, so everything below is about reading minipay's list");

        List<String> intents = r.discrepancies().stream().map(Reconciler.Discrepancy::intentId).toList();

        assertFalse(intents.contains(RIVAL_INTENT),
                "ANOTHER MERCHANT'S PAYMENT IS NOT THIS MERCHANT'S PROBLEM. It arrived in the report "
                + "only because the columns had drifted apart, and it is both a false discrepancy and a leak.");
        assertTrue(intents.contains(WALLET_INTENT), "the merchant's own wallet payment is still seen");
        assertTrue(intents.contains(CARD_INTENT),
                "AND THE MERCHANT'S OWN CARD PAYMENT DID NOT VANISH. Its row names the card before it names "
                + "the payment, so anything that takes the first id it sees reads back a card as a payment "
                + "and drops it. A payment quietly missing from an audit is the failure that looks like health.");

        assertEquals(2, r.intentsChecked(), "two payments belong to this merchant, and two were read");
        assertEquals(2, r.discrepancies().size(), "so exactly two orphans, not three");
        for (Reconciler.Discrepancy d : r.discrepancies()) {
            assertEquals(Reconciler.Kind.ORPHAN_INTENT, d.kind(),
                    "minipay holds payments this shop has no order for, which is the honest finding");
        }
        System.out.println("lesson: a nested payment-method id left the merchant's own rows correctly attributed");
    }

    /**
     * LESSON · A ROW THAT NAMES ITSELF TWICE HAS NOT NAMED ITSELF.
     *
     * Duplicate keys are legal on the wire and no two JSON readers agree about
     * which one wins. RailsLessonTest teaches this about an issuer's decision,
     * where the cost is obvious: take the first and the payment declines, take
     * the last and it approves. The cost here is quieter and no smaller. Take
     * the first and the report names one payment, take the last and it names
     * another, and an operator is sent at three in the morning to look for
     * money against an id that was never really the row's.
     *
     * So the reader refuses, and the reconciler skips the row. That is a
     * DELIBERATE loss: this audit would rather be one row short and say so than
     * be complete and wrong about which payment it read. The row it dropped is
     * still sitting in minipay for a human to look at, whereas a misattributed
     * id looks exactly like a finding.
     */
    @Test
    void lesson_a_row_that_gives_its_id_twice_is_skipped_rather_than_guessed() throws Exception {
        Reconciler.Report r = Reconciler.run(AMBIGUOUS, 100);

        List<String> intents = r.discrepancies().stream().map(Reconciler.Discrepancy::intentId).toList();

        assertFalse(intents.contains(FIRST_ID),
                "NOT THE FIRST ID. Preferring it is a choice the wire never asked us to make, and the "
                + "reader that makes it is one library upgrade away from preferring the other.");
        assertFalse(intents.contains(SECOND_ID), "and not the last one either, for the same reason");
        assertTrue(intents.contains(PLAIN_INTENT),
                "AND THE ROW THAT WAS CLEAR ABOUT ITSELF SURVIVED. Refusing one ambiguous row must not "
                + "cost the merchant the rest of the page: strictness that swallows good rows gets turned off.");

        assertEquals(1, r.intentsChecked(), "one row answered its own question, and one declined to");
        System.out.println("lesson: a row carrying two ids was skipped, and the rest of the page still read");
    }

    // ------------------------------------------------- the stand-in processor

    /**
     * minipay's list, as it would look the day somebody usefully adds the card
     * to it. The nested payment method is present on the card payment and absent
     * on the wallet ones, because that is what optional means.
     */
    private static void list(HttpExchange ex) throws IOException {
        String body = "["
                + "{\"id\":\"" + WALLET_INTENT + "\",\"amount\":\"40.00\",\"currency\":\"EUR\","
                + "\"customer\":\"ana\",\"merchant\":\"" + TENANT + "\","
                + "\"status\":\"succeeded\",\"at\":\"" + T0 + "\"},"

                // the payment method is written BEFORE the payment's own id,
                // which is a choice minipay is entitled to make and never
                // thought to mention
                + "{\"payment_method\":{\"id\":\"pm_visa_4242\",\"brand\":\"visa\",\"last4\":\"4242\"},"
                + "\"id\":\"" + CARD_INTENT + "\",\"amount\":\"75.00\",\"currency\":\"EUR\","
                + "\"customer\":\"bea\",\"merchant\":\"" + TENANT + "\","
                + "\"status\":\"requires_capture\",\"at\":\"" + T0 + "\"},"

                + "{\"id\":\"" + RIVAL_INTENT + "\",\"amount\":\"12.00\",\"currency\":\"EUR\","
                + "\"customer\":\"cyd\",\"merchant\":\"" + RIVAL + "\","
                + "\"status\":\"succeeded\",\"at\":\"" + T0 + "\"},"

                + "{\"id\":\"" + PLAIN_INTENT + "\",\"amount\":\"20.00\",\"currency\":\"EUR\","
                + "\"customer\":\"dot\",\"merchant\":\"" + AMBIGUOUS + "\","
                + "\"status\":\"succeeded\",\"at\":\"" + T0 + "\"},"

                // one row, two ids, both legal. A merge upstream, a retry
                // written over a retry, a serialiser that learned a field twice:
                // there are many ways to send this and none of them tell the
                // reader which id was meant.
                + "{\"id\":\"" + FIRST_ID + "\",\"amount\":\"33.00\",\"currency\":\"EUR\","
                + "\"customer\":\"eve\",\"merchant\":\"" + AMBIGUOUS + "\","
                + "\"id\":\"" + SECOND_ID + "\",\"status\":\"succeeded\",\"at\":\"" + T0 + "\"}"
                + "]";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
