package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.gamemodes.net.client.FGameClient;

/**
 * What {@code /status} says once Forge's client has given up reconnecting. The seat sits in a lobby
 * and the host stops, so the client starts its retries. They take about two minutes, so the test
 * calls the client's own {@code transitionToFailed}, the method its last retry ends in.
 *
 * Before the change {@code connected} stayed true and the lobby kept its slots.
 */
public class AgentLostConnectionTest {

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("lost");
        }
    }

    @Test(timeOut = 120_000)
    public void aSeatWhoseClientGaveUpIsNotConnected() throws Exception {
        match = RiggedMatch.inLobby();
        match.killHost();
        AgentGameTest.waitFor("Forge's client is reconnecting", 20_000,
                () -> "RECONNECTING".equals(connection().get("reconnectState").getAsString()));
        JsonObject status = match.http.get("/status");
        assertTrue(status.getAsJsonObject("connection").get("connected").getAsBoolean(), "connected while it retries: " + status);
        assertEquals(status.getAsJsonObject("lobby").get("mySlot").getAsInt(), 1, "the lobby is kept while it retries: " + status);

        final Method giveUp = FGameClient.class.getDeclaredMethod("transitionToFailed");
        giveUp.setAccessible(true);
        giveUp.invoke(client());

        status = match.http.get("/status");
        final JsonObject connection = status.getAsJsonObject("connection");
        assertEquals(connection.get("reconnectState").getAsString(), "FAILED", status.toString());
        assertFalse(connection.get("connected").getAsBoolean(), "not connected: " + status);
        assertNotNull(connection.get("closeReason").getAsString(), "a reason: " + status);
        final JsonObject lobby = status.getAsJsonObject("lobby");
        assertEquals(lobby.get("mySlot").getAsInt(), -1, "no slot: " + status);
        assertFalse(lobby.has("slots"), "no lobby: " + status);
        assertFalse(status.getAsJsonObject("game").get("inGame").getAsBoolean(), "no game: " + status);
    }

    private JsonObject connection() throws Exception {
        return match.http.get("/status").getAsJsonObject("connection");
    }

    /** The bridge's Forge client. */
    private FGameClient client() throws Exception {
        final Field session = AgentBridge.class.getDeclaredField("session");
        session.setAccessible(true);
        final Field client = AgentSession.class.getDeclaredField("client");
        client.setAccessible(true);
        return (FGameClient) client.get(session.get(match.bridge));
    }
}
