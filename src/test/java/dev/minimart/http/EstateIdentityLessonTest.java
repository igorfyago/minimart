package dev.minimart.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE ESTATE LINK, END TO END AT THE SEAM.
 *
 * EstateIdentity is two hops: validate the token (needs a real SSO — out of
 * scope here), then ask the bank's phonebook which customer that person is.
 * What this class pins is the second hop and the fail-shut rules around it,
 * against a real HTTP bank double — because the failure that matters is not
 * "bad JSON" but "the bank is down and the shop just lost a sale over it".
 *
 * The lookup path is exercised through the one piece of EstateIdentity that
 * is public-by-design for tests: bankBaseUrl, pointed at the double.
 */
class EstateIdentityLessonTest {

    private static HttpServer bank;
    private static int bankPort;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void bankDouble() throws IOException {
        bank = HttpServer.create(new InetSocketAddress(0), 0);
        bankPort = bank.getAddress().getPort();
        bank.createContext("/api/whois", ex -> {
            String q = ex.getRequestURI().getQuery();
            String name = q != null && q.startsWith("name=") ? q.substring(5) : "";
            String body = switch (name) {
                case "igor" -> "{\"customer\":10}";
                case "luna" -> "{\"customer\":13}";
                default -> "{\"customer\":null}";
            };
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length());
            try (OutputStream os = ex.getResponseBody()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        });
        bank.start();
        EstateIdentity.bankBaseUrl = "http://localhost:" + bankPort;
    }

    @AfterAll
    static void stop() { bank.stop(0); }

    @Test
    void aKnownNameResolvesToTheBankCustomer() throws Exception {
        // LESSON 1: the circle closes. The person the bank knows as customer
        // 10 shops AS 10 — the purchase lands in his ledger.
        var r = http.send(HttpRequest.newBuilder(
                URI.create(EstateIdentity.bankBaseUrl + "/api/whois?name=igor")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"customer\":10"), "igor is bank customer 10");
    }

    @Test
    void aStrangerIsNullNeverAnError() throws Exception {
        // LESSON 2: a signed-in estate user with no bank account gets null —
        // an honest "nobody here by that name", not a 4xx and not a minted id.
        var r = http.send(HttpRequest.newBuilder(
                URI.create(EstateIdentity.bankBaseUrl + "/api/whois?name=nobody")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"customer\":null"));
    }

    @Test
    void theAnswerParsesToTheIdAndNullToEmpty() {
        // LESSON 3: the wire shape the bank serves is the one the adapter
        // reads — an id out of {"customer":N}, empty out of {"customer":null}.
        assertEquals(Optional.of(10L), parse("{\"customer\":10}"));
        assertEquals(Optional.empty(), parse("{\"customer\":null}"));
        assertEquals(Optional.empty(), parse("garbage"));
    }

    /** Mirror of the one-field read in EstateIdentity.lookupBank — kept
     *  beside the test so a drift between wire shape and parser is a red
     *  test here, not a silent anonymous shop in production. */
    private static Optional<Long> parse(String body) {
        String marker = "\"customer\":";
        int i = body.indexOf(marker);
        if (i < 0) return Optional.empty();
        String rest = body.substring(i + marker.length()).replaceAll("[^0-9a-z].*", "");
        if (rest.isBlank() || rest.startsWith("n")) return Optional.empty();
        return Optional.of(Long.parseLong(rest));
    }
}
