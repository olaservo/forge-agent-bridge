package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashSet;
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
 * Hosts a Limited match in-process with the host seat played by the AI and lets the bridge take
 * the other seat, driven over its HTTP API like the MCP server does. The agent's deck is lands
 * plus Goblin Bushwhacker (kicker: an optional-cost dialog when cast) and Civic Wayfinder (an
 * enters trigger with a yes/no prompt, then a library-search dialog), so the pending-decision
 * path is exercised. Policy: answer a decision with its first option; on our main phase try each
 * hand card once (lands get played, spells get cast with Auto payment); otherwise press OK; when
 * OK is disabled, Cancel if allowed, else select the first card in hand (discard to hand size).
 */
public class AgentGameTest {

    private static final int HOST_PORT = 36798;
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Kicker Test",
            "[Main]",
            "10 Mountain",
            "10 Forest",
            "10 Goblin Bushwhacker",
            "10 Civic Wayfinder");

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
            http.saveFixtures("game");
        }
        if (bridge != null) {
            bridge.stop();
        }
        if (server != null) {
            server.stopServer();
        }
    }

    @Test(timeOut = 600_000)
    public void agentPlaysAMatchAgainstTheHostAi() throws Exception {
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

        JsonObject st = http.post("/connect", BridgeHttp.body("host", "127.0.0.1", "port", HOST_PORT, "name", "AgentBot"));
        assertTrue(st.getAsJsonObject("connection").get("connected").getAsBoolean());
        waitFor("agent takes lobby slot 1", 20_000, () -> http.get("/status").getAsJsonObject("lobby").get("mySlot").getAsInt() == 1);

        final JsonObject deck = http.post("/lobby/deck", BridgeHttp.body("dck", DECK));
        assertEquals(deck.get("size").getAsInt(), 40, "deck text parsed: " + deck);
        final JsonObject lobbyAfterReady = http.post("/lobby/ready", BridgeHttp.body("ready", true));
        assertEquals(lobbyAfterReady.get("mySlot").getAsInt(), 1);
        http.post("/chat", BridgeHttp.body("text", "gl hf"));
        waitFor("host sees the agent's deck and ready flag", 20_000,
                () -> lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);

        final JsonObject err = http.postExpectingError("/game/act", BridgeHttp.body("action", "ok"));
        assertTrue(err.get("error").getAsString().contains("no game"), "acting before the game is an error: " + err);

        final Runnable start = lobby.startGame();
        assertNotNull(start, "lobby accepted the match setup");
        start.run();
        waitFor("agent gets openView", 30_000, () -> http.get("/game").get("inGame").getAsBoolean());

        int actions = 0;
        int decisions = 0;
        int casts = 0;
        long lastCardSelect = 0;
        final Set<String> decisionKinds = new LinkedHashSet<>();
        final Set<String> tried = new HashSet<>();
        long seen = -1;
        int quiet = 0;
        final long deadline = System.currentTimeMillis() + 540_000;
        JsonObject g = http.get("/game");
        while (!g.get("gameOver").getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "game did not end in time; last state " + g);
            final JsonObject status = http.get("/wait", Map.of("since", String.valueOf(seen), "timeout", "3"));
            final long rev = status.get("revision").getAsLong();
            if (rev == seen && ++quiet < 2) {
                continue; // nothing new since we last acted; the host is thinking
            }
            // Something changed, or six seconds passed with no reaction (a rejected card selection
            // produces no event at all), so re-evaluate the current state.
            quiet = 0;
            seen = rev;
            g = http.get("/game");
            if (g.get("gameOver").getAsBoolean()) {
                break;
            }
            final JsonObject buttons = g.getAsJsonObject("buttons");
            final boolean okEnabled = buttons.get("okEnabled").getAsBoolean();
            final boolean cancelEnabled = buttons.get("cancelEnabled").getAsBoolean();
            final String okLabel = buttons.get("ok").getAsString();
            final JsonElement pd = g.get("pendingDecision");
            final boolean myTurn = isMe(g, g.get("activePlayer"));
            final String phase = g.get("phase").isJsonNull() ? "" : g.get("phase").getAsString();

            if (pd != null && !pd.isJsonNull()) {
                final JsonObject d = pd.getAsJsonObject();
                http.record("GET /game#decision", g);
                decisions++;
                decisionKinds.add(d.get("kind").getAsString());
                final JsonArray choices = new JsonArray();
                final String kind = d.get("kind").getAsString();
                String text = null;
                if ("text".equals(kind) || "number".equals(kind)) {
                    text = d.get("default").isJsonNull() ? "0" : d.get("default").getAsString();
                } else {
                    final int min = d.get("min").getAsInt();
                    final int n = d.getAsJsonArray("options").size();
                    for (int i = 0; i < Math.max(min, 1) && i < n; i++) {
                        choices.add(i);
                    }
                }
                http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices, "text", text));
            } else if (!iHavePriority(g) && g.get("prompt").getAsString().startsWith("Waiting for")) {
                // The other player is acting; nothing to do. Both signals are needed: the prompt text lags
                // behind the priority flag, and before the first turn (mulligans) nobody has priority.
            } else if ("Auto".equals(okLabel)) {
                actions++;
                http.post("/game/act", BridgeHttp.body("action", okEnabled ? "ok" : "cancel"));
            } else if (okEnabled && myTurn && "COMBAT_DECLARE_ATTACKERS".equals(phase) && tried.add(g.get("turn").getAsInt() + ":attack")) {
                actions++;
                http.post("/game/act", BridgeHttp.body("action", "alphaStrike"));
            } else if (okEnabled && myTurn && phase.startsWith("MAIN") && nextUntried(g, tried) != null
                    && System.currentTimeMillis() - lastCardSelect > 2_000) {
                // Two selections close together run concurrently on the host and can wedge the game thread.
                final JsonObject card = nextUntried(g, tried);
                tried.add(key(g, card));
                casts++;
                actions++;
                lastCardSelect = System.currentTimeMillis();
                http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", card.get("id").getAsInt()));
            } else if (okEnabled) {
                actions++;
                http.post("/game/act", BridgeHttp.body("action", "ok"));
            } else if (cancelEnabled) {
                actions++;
                http.post("/game/act", BridgeHttp.body("action", "cancel"));
            } else if (System.currentTimeMillis() - lastCardSelect > 2_000) {
                final JsonObject card = firstCardInHand(g);
                if (card != null) {
                    actions++;
                    lastCardSelect = System.currentTimeMillis();
                    http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", card.get("id").getAsInt()));
                }
            }
        }

        final JsonObject full = http.get("/game");
        assertTrue(full.get("gameOver").getAsBoolean());
        assertFalse(full.get("winner").getAsString().isEmpty(), "winner reported: " + full);
        assertTrue(full.get("turn").getAsInt() > 1, "more than one turn: " + full);
        assertTrue(actions > 10, "agent passed priority through the game: " + actions);
        assertTrue(decisions > 0, "at least one decision dialog was answered (kicker or library search)");
        final JsonObject log = http.get("/game/log", Map.of("since", "0"));
        assertTrue(log.getAsJsonArray("log").size() > 10, "game log arrived");
        System.out.println("[AgentGameTest] game over on turn " + full.get("turn") + ", winner " + full.get("winner")
                + ", " + actions + " actions, " + casts + " card selections on our main phase, " + decisions + " decisions " + decisionKinds
                + ", " + full.get("incorrectActions") + " incorrect");

        http.post("/game/act", BridgeHttp.body("action", "quit"));
        waitFor("host ends the match", 30_000, () -> !lobby.isMatchActive());
        assertFalse(lobby.getSlot(1).isReady(), "lobby resets ready flags after the match");
        final JsonObject end = http.get("/status");
        assertFalse(end.getAsJsonObject("game").get("inGame").getAsBoolean());
        http.post("/disconnect", new JsonObject());
    }

    private static boolean isMe(final JsonObject g, final JsonElement name) {
        return name != null && !name.isJsonNull() && !g.get("me").isJsonNull() && name.getAsString().equals(g.get("me").getAsString());
    }

    private static boolean iHavePriority(final JsonObject g) {
        final JsonElement players = g.get("players");
        if (players == null || !players.isJsonArray()) {
            return false;
        }
        for (final JsonElement p : players.getAsJsonArray()) {
            final JsonObject po = p.getAsJsonObject();
            if (po.get("isMe").getAsBoolean()) {
                return po.get("hasPriority").getAsBoolean();
            }
        }
        return false;
    }

    /** One try per card per turn: a rejected selection (no mana, second land) is not worth repeating. */
    private static String key(final JsonObject g, final JsonObject card) {
        return g.get("turn").getAsInt() + ":" + card.get("id").getAsInt();
    }

    private static JsonObject nextUntried(final JsonObject g, final Set<String> tried) {
        final JsonArray hand = myHand(g);
        if (hand == null) {
            return null;
        }
        for (final JsonElement c : hand) {
            if (!tried.contains(key(g, c.getAsJsonObject()))) {
                return c.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonObject firstCardInHand(final JsonObject g) {
        final JsonArray hand = myHand(g);
        return hand == null || hand.isEmpty() ? null : hand.get(0).getAsJsonObject();
    }

    private static JsonArray myHand(final JsonObject g) {
        final JsonElement players = g.get("players");
        if (players == null || !players.isJsonArray()) {
            return null;
        }
        for (final JsonElement p : players.getAsJsonArray()) {
            final JsonObject po = p.getAsJsonObject();
            if (po.get("isMe").getAsBoolean() && po.has("hand")) {
                return po.getAsJsonArray("hand");
            }
        }
        return null;
    }

    interface Check {
        boolean ok() throws Exception;
    }

    private static void waitFor(final String what, final long timeoutMillis, final Check cond) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!cond.ok()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(100);
        }
    }
}
