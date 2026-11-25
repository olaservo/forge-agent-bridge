package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** What the HTTP front refuses. No host is needed. */
public class AgentHttpTest {

    private AgentBridge bridge;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (bridge != null) {
            bridge.stop();
        }
    }

    @Test(timeOut = 60_000)
    public void refusesAWebPageAndAForeignHostName() throws Exception {
        bridge = new AgentBridge();
        bridge.start(0);
        final int port = bridge.port();
        assertStatus(200, request(port, "GET /status", "Host: 127.0.0.1:" + port));
        assertStatus(200, request(port, "GET /status", "Host: localhost:" + port));
        assertStatus(403, request(port, "POST /disconnect", "Host: 127.0.0.1:" + port, "Origin: https://example.com"));
        assertStatus(403, request(port, "GET /status", "Host: rebound.example.com:" + port));
    }

    @Test(timeOut = 60_000)
    public void aMissingFieldIsNamed() throws Exception {
        bridge = new AgentBridge();
        bridge.start(0);
        final int port = bridge.port();
        final String answer = request(port, "POST /lobby/avatar", "Host: 127.0.0.1:" + port);
        assertStatus(400, answer);
        assertTrue(answer.contains("missing 'index'"), answer);
    }

    @Test(timeOut = 60_000)
    public void aSelectWithoutAnIdIsTheCallersMistake() throws Exception {
        bridge = new AgentBridge();
        bridge.start(0);
        final BridgeHttp http = new BridgeHttp(bridge.port());
        for (final String action : new String[] {"selectCard", "selectPlayer", "selectStack"}) {
            final String answer = http.postExpectingError("/game/act", BridgeHttp.body("action", action)).toString();
            assertTrue(answer.contains(action + " needs an id"), answer);
        }
        final String answer = http.postExpectingError("/game/act", BridgeHttp.body("action", "useMana")).toString();
        assertTrue(answer.contains("useMana needs a color"), answer);
    }

    @Test(timeOut = 60_000)
    public void aBodyThatIsNotJsonIsTheCallersMistake() throws Exception {
        bridge = new AgentBridge();
        bridge.start(0);
        final HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.port() + "/chat"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\": ")).build();
        final HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(res.statusCode(), 400, res.body());
    }

    private static void assertStatus(final int status, final String answer) {
        assertTrue(answer.startsWith("HTTP/1.1 " + status + " "), "expected " + status + ": " + answer);
    }

    /** One request with exactly these headers, which Java's HttpClient does not allow for Host. */
    private static String request(final int port, final String line, final String... headers) throws IOException {
        final StringBuilder sb = new StringBuilder(line).append(" HTTP/1.1\r\n");
        for (final String header : headers) {
            sb.append(header).append("\r\n");
        }
        sb.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
