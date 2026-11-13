package forge.agent;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.deck.DeckgenUtil;
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
 * A reveal is shown to the seat without waiting for an answer. The deck is 20 Mountain and 20
 * Goblin Guide: when a Guide attacks, the defending player reveals the top card of their library.
 * The bridge reports it in {@code messages}, raises no pending decision, and the game moves on to
 * the next turn. Before this was fixed the host stayed parked on the reveal.
 */
public class AgentRevealTest {

    private static final int HOST_PORT = 36794;
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Reveal Test",
            "[Main]",
            "20 Mountain",
            "20 Goblin Guide");

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
            preferences.setPref(FPref.PLAYER_NAME, "HostAI"); // a blank name pops a Swing input dialog at match start
            preferences.setPref(FPref.UI_ENABLE_SOUNDS, false);
            preferences.setPref(FPref.UI_ENABLE_MUSIC, false);
            return null;
        });
        FModel.getNetPreferences().setPref(FNetPref.UPnP, "NEVER");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (http != null) {
            http.saveFixtures("reveal");
        }
        if (bridge != null) {
            bridge.stop();
        }
        if (server != null) {
            server.stopServer();
        }
    }

    @Test(timeOut = 300_000)
    public void aRevealIsShownAndTheGameGoesOn() throws Exception {
        server = FServerManager.getInstance();
        lobby = new ServerGameLobby();
        server.startServer(HOST_PORT);
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

        final LobbySlot hostSlot = lobby.getSlot(0);
        hostSlot.setType(LobbySlotType.AI);
        hostSlot.setDeck(DeckgenUtil.getRandomColorDeck(true));
        hostSlot.setIsReady(true);
        lobby.setLimitedMode(true);

        bridge = new AgentBridge();
        bridge.start(0);
        http = new BridgeHttp(bridge.port());

        http.post("/connect", BridgeHttp.body("host", "127.0.0.1", "port", HOST_PORT, "name", "AgentBot"));
        AgentGameTest.waitFor("agent takes lobby slot 1", 20_000, () -> http.get("/status").getAsJsonObject("lobby").get("mySlot").getAsInt() == 1);
        http.post("/lobby/deck", BridgeHttp.body("dck", DECK));
        http.post("/lobby/ready", BridgeHttp.body("ready", true));
        AgentGameTest.waitFor("host sees the agent's deck and ready flag", 20_000,
                () -> lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);
        final Runnable start = lobby.startGame();
        assertNotNull(start, "lobby accepted the match setup");
        start.run();
        AgentGameTest.waitFor("agent gets openView", 30_000, () -> http.get("/game").get("inGame").getAsBoolean());

        final Set<String> tried = new HashSet<>();
        String revealed = null;
        int revealTurn = -1;
        long lastCardSelect = 0;
        long seen = -1;
        int quiet = 0;
        final long deadline = System.currentTimeMillis() + 240_000;
        JsonObject g = http.get("/game");
        while (revealed == null || g.get("turn").getAsInt() == revealTurn) {
            assertTrue(System.currentTimeMillis() < deadline,
                    (revealed == null ? "no reveal in time" : "the game did not go on after the reveal") + "; last state " + g);
            final JsonObject status = http.get("/wait", Map.of("since", String.valueOf(seen), "timeout", "3"));
            final long rev = status.get("revision").getAsLong();
            if (rev == seen && ++quiet < 2) {
                continue;
            }
            quiet = 0;
            seen = rev;
            g = http.get("/game");
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject buttons = g.getAsJsonObject("buttons");
            final boolean okEnabled = buttons.get("okEnabled").getAsBoolean();
            final JsonElement pd = g.get("pendingDecision");
            final JsonObject me = me(g);
            final boolean myTurn = me != null && me.get("name").getAsString().equals(str(g.get("activePlayer")));
            final String phase = str(g.get("phase"));
            final boolean myMain = myTurn && phase.startsWith("MAIN") && g.getAsJsonArray("stack").isEmpty();
            final boolean settled = System.currentTimeMillis() - lastCardSelect > 2_000;
            final String turn = "t" + str(g.get("turn")) + ":";

            if (revealed == null) {
                for (final JsonElement m : g.getAsJsonArray("messages")) {
                    if (m.getAsString().startsWith("Revealed, ")) {
                        revealed = m.getAsString();
                        revealTurn = g.get("turn").getAsInt();
                        http.record("GET /game#revealed", g);
                    }
                }
            }

            if (pd != null && !pd.isJsonNull()) {
                final JsonObject d = pd.getAsJsonObject();
                assertTrue(d.get("min").getAsInt() >= 0, "a reveal must not arrive as a decision: " + d);
                final JsonArray choices = new JsonArray();
                choices.add(0);
                http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices));
            } else if (!AgentGameTest.iHavePriority(g) && g.get("prompt").getAsString().startsWith("Waiting for")) {
                // The other player is acting.
            } else if ("Auto".equals(buttons.get("ok").getAsString())) {
                http.post("/game/act", BridgeHttp.body("action", okEnabled ? "ok" : "cancel"));
            } else if (okEnabled && myMain && settled && card(me, "hand", "Mountain") != null && tried.add(turn + "land")) {
                lastCardSelect = System.currentTimeMillis();
                http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", card(me, "hand", "Mountain").get("id").getAsInt()));
            } else if (okEnabled && myMain && settled && card(me, "battlefield", "Mountain") != null && card(me, "hand", "Goblin Guide") != null
                    && tried.add(turn + "cast")) {
                lastCardSelect = System.currentTimeMillis();
                http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", card(me, "hand", "Goblin Guide").get("id").getAsInt()));
            } else if (okEnabled && myMain && !settled) {
                // Wait: OK here would pass priority and leave the main phase.
            } else if (okEnabled && myTurn && "COMBAT_DECLARE_ATTACKERS".equals(phase) && tried.add(turn + "attack")) {
                http.post("/game/act", BridgeHttp.body("action", "alphaStrike"));
            } else if (okEnabled) {
                http.post("/game/act", BridgeHttp.body("action", "ok"));
            } else if (buttons.get("cancelEnabled").getAsBoolean()) {
                http.post("/game/act", BridgeHttp.body("action", "cancel"));
            }
        }
        assertTrue(revealed.contains("HostAI"), "the message says whose card it was: " + revealed);
        System.out.println("[AgentRevealTest] " + revealed);

        http.post("/game/act", BridgeHttp.body("action", "concede"));
        AgentGameTest.waitFor("the game ends on the concession", 30_000, () -> http.get("/game").get("gameOver").getAsBoolean());
        http.post("/game/act", BridgeHttp.body("action", "quit"));
        AgentGameTest.waitFor("host ends the match", 30_000, () -> !lobby.isMatchActive());
        http.post("/disconnect", new JsonObject());
    }

    private static String str(final JsonElement e) {
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static JsonObject me(final JsonObject g) {
        final JsonElement players = g.get("players");
        if (players == null || !players.isJsonArray()) {
            return null;
        }
        for (final JsonElement p : players.getAsJsonArray()) {
            if (p.getAsJsonObject().get("isMe").getAsBoolean()) {
                return p.getAsJsonObject();
            }
        }
        return null;
    }

    /** An untapped card of that name in one of the agent's zones. */
    private static JsonObject card(final JsonObject me, final String zone, final String name) {
        if (me == null || !me.has(zone)) {
            return null;
        }
        for (final JsonElement c : me.getAsJsonArray(zone)) {
            final JsonObject card = c.getAsJsonObject();
            if (name.equals(card.get("name").getAsString()) && !card.get("tapped").getAsBoolean()) {
                return card;
            }
        }
        return null;
    }
}
