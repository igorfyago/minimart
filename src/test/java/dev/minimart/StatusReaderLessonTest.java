package dev.minimart;

import dev.minimart.core.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WHICH "status" SPEAKS FOR THE INTENT.
 *
 * The saga driver decides whether to release a customer's hold on one word read
 * out of a body minipay composes. str() is a scanner: it answers with the FIRST
 * occurrence of a key anywhere it appears, inside nested objects and inside
 * quoted text alike. That is an acceptable cost when reading a flat message this
 * system minted itself, and it stops being acceptable the instant the shape
 * belongs to another service, because the failure is silent and reads as a
 * perfectly good answer.
 *
 * The reconciler had the same weakness and the consequence was a wrong sentence
 * in a report. Here the same misread releases a hold on a live payment, so this
 * is pinned separately and at the CALL SITE. The helpers were always correct in
 * isolation; what shipped bugs was which one got called.
 */
class StatusReaderLessonTest {

    /** The shape minipay is free to grow into: an intent that carries the
     *  instrument it charged, and the instrument has a status of its own. */
    private static final String NESTED =
            "{\"payment_method\":{\"id\":\"pm_1\",\"status\":\"canceled\"},"
          + "\"id\":\"pi_x\",\"status\":\"succeeded\"}";

    @Test
    void theScannerAnswersWithTheInstrumentAndTheReaderWithTheIntent() {
        assertEquals("canceled", Json.str(NESTED, "status"),
                "the scanner reaches the nested instrument first · this is the trap");
        assertEquals("succeeded", Json.text(NESTED, "status"),
                "the top-level reader answers for the intent itself");
    }

    @Test
    void aStatusQuotedInsideAnotherValueCannotSpeakEither() {
        String withProse = "{\"id\":\"pi_x\",\"note\":\"the \\\"status\\\":\\\"canceled\\\" was stale\","
                         + "\"status\":\"succeeded\"}";
        assertEquals("succeeded", Json.text(withProse, "status"),
                "a key name appearing inside a string value is text, not a member");
    }

    @Test
    void theDriverAsksTheTopLevelQuestion() {
        String src = readSource("src/main/java/dev/minimart/commerce/SagaDriver.java");
        assertTrue(src.contains("Json.text(r.body(), \"status\")"),
                "the intent status must be read with the top-level reader");
        assertFalse(src.contains("Json.str(r.body(), \"status\")"),
                "the scanner must not be what decides whether a hold is released");
    }

    @Test
    void theReconcilerAsksItToo() {
        // Same document, same authority, same reason. Kept here so the two
        // readers of minipay's shape cannot drift apart again.
        String src = readSource("src/main/java/dev/minimart/commerce/Reconciler.java");
        assertFalse(src.contains("Json.str(body"),
                "the reconciler reads another service's document and must not scan it");
    }

    private static String readSource(String rel) {
        try {
            return Files.readString(Path.of(rel));
        } catch (Exception e) {
            throw new AssertionError("could not read " + rel + ": " + e.getMessage(), e);
        }
    }
}
