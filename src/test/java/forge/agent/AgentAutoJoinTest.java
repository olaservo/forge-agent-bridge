package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.net.ServerSocket;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * The sit-down call and auto-join against an in-process host. Both ports are picked free at run time,
 * so this test can run beside the others.
 */
public class AgentAutoJoinTest {

    private static final long INTERVAL_MILLIS = 300;
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Auto-Join Test",
            "[Main]",
            "20 Mountain",
            "20 Goblin Piker");

    private FServerManager server;
    private ServerGameLobby lobby;
    private AgentBridge bridge;
    private BridgeHttp http;

    @BeforeClass
    public void initializeModel() {
        GuiBase.setInterface(new AgentGuiBase());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.PLAYER_NAME, "HostAI");
            return null;
        });
        FModel.getNetPreferences().setPref(FNetPref.UPnP, "NEVER");
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (bridge != null) {
            bridge.stop();
        }
        if (server != null) {
            server.stopServer();
        }
    }

    @Test(timeOut = 120_000)
    public void sitTakesASeatAndReadiesOnlyWithADeck() throws Exception {
        final int hostPort = freePort();
        host(hostPort);
        bridge = new AgentBridge();
        bridge.start(0);
        http = new BridgeHttp(bridge.port());

        final JsonObject err = http.postExpectingError("/sit",
                BridgeHttp.body("port", hostPort, "name", "SitBot", "deck", "no deck has this name"));
        assertTrue(err.get("error").getAsString().contains("no deck named"), "unknown deck is an error: " + err);
        Thread.sleep(500);
        assertEquals(lobby.getSlot(1).getType(), LobbySlotType.OPEN, "a bad deck name takes no seat");

        JsonObject st = http.post("/sit", BridgeHttp.body("port", hostPort, "name", "SitBot"));
        assertEquals(st.getAsJsonObject("lobby").get("mySlot").getAsInt(), 1, "the answer already has the slot: " + st);
        assertEquals(lobby.getSlot(1).getName(), "SitBot");
        Thread.sleep(500);
        assertFalse(lobby.getSlot(1).isReady(), "no deck, so not ready");

        http.post("/disconnect", new JsonObject());
        AgentGameTest.waitFor("the host sees the seat leave", 10_000, () -> lobby.getSlot(1).getType() == LobbySlotType.OPEN);
        st = http.post("/sit", BridgeHttp.body("port", hostPort, "name", "SitBot", "dck", DECK));
        final JsonObject mine = st.getAsJsonObject("lobby").getAsJsonArray("slots").get(1).getAsJsonObject();
        assertTrue(mine.get("ready").getAsBoolean(), "the answer already shows the seat ready: " + st);
        assertTrue(seatedAndReady(), "the host has the deck and the ready flag");

        // The lobby has two seats and SitBot holds the second
        final AgentBridge second = new AgentBridge();
        second.start(0);
        try {
            final JsonObject full = new BridgeHttp(second.port()).postExpectingError("/sit",
                    BridgeHttp.body("port", hostPort, "name", "LateBot"));
            assertTrue(full.get("error").getAsString().contains("gave no seat"), "a full lobby is an error: " + full);
        } finally {
            second.stop();
        }
    }

    @Test(timeOut = 120_000)
    public void autoJoinSitsDownOncePerLobby() throws Exception {
        final int hostPort = freePort();
        bridge = new AgentBridge();
        bridge.start(0);
        http = new BridgeHttp(bridge.port());
        bridge.autoJoin(BridgeHttp.body("host", "127.0.0.1", "port", hostPort, "name", "AutoBot", "dck", DECK), INTERVAL_MILLIS);

        Thread.sleep(3 * INTERVAL_MILLIS);
        assertFalse(http.get("/status").getAsJsonObject("connection").get("connected").getAsBoolean(), "no host yet");

        host(hostPort);
        AgentGameTest.waitFor("the seat is taken with a deck and ready", 20_000, this::seatedAndReady);
        assertEquals(lobby.getSlot(1).getName(), "AutoBot");

        http.post("/disconnect", new JsonObject());
        AgentGameTest.waitFor("the host sees the seat leave", 10_000, () -> lobby.getSlot(1).getType() == LobbySlotType.OPEN);
        Thread.sleep(5 * INTERVAL_MILLIS);
        assertEquals(lobby.getSlot(1).getType(), LobbySlotType.OPEN, "no second try while the same lobby is up");

        server.stopServer();
        Thread.sleep(3 * INTERVAL_MILLIS);
        host(hostPort);
        AgentGameTest.waitFor("the seat is taken at the next lobby", 20_000, this::seatedAndReady);
    }

    private void host(final int port) {
        server = FServerManager.getInstance();
        lobby = new ServerGameLobby();
        server.startServer(port);
        server.setLobby(lobby);
        lobby.setListener(new IUpdateable() {
            @Override
            public void update(final boolean fullUpdate) {
                server.updateLobbyState();
            }

            @Override
            public void update(final int slot, final LobbySlotType type) {
            }
        });
    }

    private boolean seatedAndReady() {
        final LobbySlot slot = lobby.getSlot(1);
        return slot.getType() == LobbySlotType.REMOTE && slot.isReady() && slot.getDeck() != null;
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
