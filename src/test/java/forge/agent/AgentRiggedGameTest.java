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
 * Hosts a Limited match in-process like {@link AgentGameTest}, with a deck built so one situation
 * comes up on the agent's first land drop: 24 Evolving Wilds and 16 basics. The agent sets its
 * avatar in the lobby, plays a Wilds, activates it, and picks a basic from the library search,
 * which the bridge reports as {@code selection} and not as a pending decision. The test stops
 * there and concedes.
 */
public class AgentRiggedGameTest {

    private static final int HOST_PORT = 36797;
    private static final int BASICS = 16;
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Rigged Test",
            "[Main]",
            "24 Evolving Wilds",
            "8 Forest",
            "8 Mountain");

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
            http.saveFixtures("rigged");
        }
        if (bridge != null) {
            bridge.stop();
        }
        if (server != null) {
            server.stopServer();
        }
    }

    @Test(timeOut = 300_000)
    public void agentSetsItsAvatarAndSearchesItsLibrary() throws Exception {
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

        // The bridge and the host share one preferences file, so both seats start on the same avatar.
        final int avatar = lobby.getSlot(1).getAvatarIndex() == 17 ? 18 : 17;
        http.post("/lobby/avatar", BridgeHttp.body("index", avatar));
        AgentGameTest.waitFor("host sees the agent's avatar", 20_000, () -> lobby.getSlot(1).getAvatarIndex() == avatar);
        AgentGameTest.waitFor("the bridge reports the avatar on its own slot", 20_000, () -> slot(http.get("/status"), 1).get("avatar").getAsInt() == avatar);
        assertTrue(slot(http.get("/status"), 0).get("avatar").getAsInt() != avatar, "the host's avatar is unchanged");

        final JsonObject deck = http.post("/lobby/deck", BridgeHttp.body("dck", DECK));
        assertEquals(deck.get("size").getAsInt(), 40, "deck text parsed: " + deck);
        http.post("/lobby/ready", BridgeHttp.body("ready", true));
        AgentGameTest.waitFor("host sees the agent's deck and ready flag", 20_000,
                () -> lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);

        final Runnable start = lobby.startGame();
        assertNotNull(start, "lobby accepted the match setup");
        start.run();
        AgentGameTest.waitFor("agent gets openView", 30_000, () -> http.get("/game").get("inGame").getAsBoolean());

        final Set<String> decisionKinds = new LinkedHashSet<>();
        final Set<String> tried = new HashSet<>();
        int fetched = -1;
        long lastCardSelect = 0;
        long seen = -1;
        int quiet = 0;
        final long deadline = System.currentTimeMillis() + 240_000;
        JsonObject g = http.get("/game");
        while (fetched < 0) {
            assertTrue(System.currentTimeMillis() < deadline, "no library search in time; last state " + g);
            final JsonObject status = http.get("/wait", Map.of("since", String.valueOf(seen), "timeout", "3"));
            final long rev = status.get("revision").getAsLong();
            if (rev == seen && ++quiet < 2) {
                continue;
            }
            quiet = 0;
            seen = rev;
            g = http.get("/game");
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended before the library search: " + g);
            final JsonObject buttons = g.getAsJsonObject("buttons");
            final boolean okEnabled = buttons.get("okEnabled").getAsBoolean();
            final JsonElement pd = g.get("pendingDecision");
            final JsonObject me = me(g);
            final boolean myMain = me != null && me.get("name").getAsString().equals(str(g.get("activePlayer")))
                    && str(g.get("phase")).startsWith("MAIN") && g.getAsJsonArray("stack").isEmpty();
            final boolean settled = System.currentTimeMillis() - lastCardSelect > 2_000;
            final JsonArray offered = librarySearch(g);

            if (pd != null && !pd.isJsonNull()) {
                // Who plays first, if the host asks: the first option.
                final JsonObject d = pd.getAsJsonObject();
                decisionKinds.add(d.get("kind").getAsString());
                final JsonArray choices = new JsonArray();
                choices.add(0);
                http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices));
            } else if (offered != null) {
                if (!settled) {
                    continue;
                }
                http.record("GET /game#selection", g);
                final JsonObject sel = g.getAsJsonObject("selection");
                assertEquals(sel.get("max").getAsInt(), 1, "one card to find: " + sel);
                for (final JsonElement c : offered) {
                    final String name = c.getAsJsonObject().get("name").getAsString();
                    assertTrue("Forest".equals(name) || "Mountain".equals(name), "only basics are offered: " + sel);
                }
                assertEquals(offered.size(), BASICS - basicsOutsideLibrary(me), "every basic in the library is offered: " + g);
                fetched = offered.get(0).getAsJsonObject().get("id").getAsInt();
                http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", fetched));
            } else if (!AgentGameTest.iHavePriority(g) && g.get("prompt").getAsString().startsWith("Waiting for")) {
                // The other player is acting.
            } else if (okEnabled && myMain && settled && wilds(me, "battlefield") != null
                    && tried.add("activate:" + wilds(me, "battlefield").get("id").getAsInt())) {
                lastCardSelect = System.currentTimeMillis();
                http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", wilds(me, "battlefield").get("id").getAsInt()));
            } else if (okEnabled && myMain && settled && wilds(me, "hand") != null && tried.add("land:" + g.get("turn").getAsInt())) {
                lastCardSelect = System.currentTimeMillis();
                http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", wilds(me, "hand").get("id").getAsInt()));
            } else if (okEnabled) {
                http.post("/game/act", BridgeHttp.body("action", "ok"));
            } else if (buttons.get("cancelEnabled").getAsBoolean()) {
                http.post("/game/act", BridgeHttp.body("action", "cancel"));
            }
        }

        final int land = fetched;
        AgentGameTest.waitFor("the chosen basic is on the battlefield", 20_000, () -> card(me(http.get("/game")), "battlefield", land) != null);
        final JsonObject after = http.get("/game");
        assertTrue(card(me(after), "battlefield", land).get("tapped").getAsBoolean(), "it entered tapped: " + after);
        assertFalse(after.has("selection"), "the search is over: " + after);
        System.out.println("[AgentRiggedGameTest] searched on turn " + after.get("turn") + ", decisions " + decisionKinds);

        http.post("/game/act", BridgeHttp.body("action", "concede"));
        AgentGameTest.waitFor("the game ends on the concession", 30_000, () -> http.get("/game").get("gameOver").getAsBoolean());
        http.post("/game/act", BridgeHttp.body("action", "quit"));
        AgentGameTest.waitFor("host ends the match", 30_000, () -> !lobby.isMatchActive());
        http.post("/disconnect", new JsonObject());
    }

    private static String str(final JsonElement e) {
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static JsonObject slot(final JsonObject status, final int index) {
        return status.getAsJsonObject("lobby").getAsJsonArray("slots").get(index).getAsJsonObject();
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

    /** The cards a prompt offers, when they are in the library; other prompts can offer cards too. */
    private static JsonArray librarySearch(final JsonObject g) {
        if (!g.has("selection")) {
            return null;
        }
        final JsonArray cards = g.getAsJsonObject("selection").getAsJsonArray("cards");
        return !cards.isEmpty() && "Library".equals(str(cards.get(0).getAsJsonObject().get("zone"))) ? cards : null;
    }

    private static JsonObject wilds(final JsonObject me, final String zone) {
        if (me == null || !me.has(zone)) {
            return null;
        }
        for (final JsonElement c : me.getAsJsonArray(zone)) {
            final JsonObject card = c.getAsJsonObject();
            if ("Evolving Wilds".equals(card.get("name").getAsString()) && !card.get("tapped").getAsBoolean()) {
                return card;
            }
        }
        return null;
    }

    private static JsonObject card(final JsonObject me, final String zone, final int id) {
        if (me == null || !me.has(zone)) {
            return null;
        }
        for (final JsonElement c : me.getAsJsonArray(zone)) {
            if (c.getAsJsonObject().get("id").getAsInt() == id) {
                return c.getAsJsonObject();
            }
        }
        return null;
    }

    private static int basicsOutsideLibrary(final JsonObject me) {
        int n = 0;
        for (final String zone : new String[] { "hand", "battlefield", "graveyard" }) {
            for (final JsonElement c : me.getAsJsonArray(zone)) {
                if (!"Evolving Wilds".equals(c.getAsJsonObject().get("name").getAsString())) {
                    n++;
                }
            }
        }
        return n;
    }
}
