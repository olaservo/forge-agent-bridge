package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.deck.DeckgenUtil;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
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
 * Surveil of two or more cards asks which cards go to the graveyard, and the seat may choose any
 * number of them. The deck is 20 Island and 20 Curate (surveil 2, then draw). The agent casts one,
 * sends the first card to the graveyard and keeps the second. The test then reads the host's own
 * game: the first card is in the graveyard and the second was drawn.
 */
public class AgentSurveilTest {

    private static final int HOST_PORT = 36793;
    private static final Pattern ID = Pattern.compile("\\((\\d+)\\)\\s*$");
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Surveil Test",
            "[Main]",
            "20 Island",
            "20 Curate");

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
            http.saveFixtures("surveil");
        }
        if (bridge != null) {
            bridge.stop();
        }
        if (server != null) {
            server.stopServer();
        }
    }

    @Test(timeOut = 300_000)
    public void agentChoosesWhatASurveilPutsInTheGraveyard() throws Exception {
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
        final List<Integer> looked = new ArrayList<>();
        long lastCardSelect = 0;
        long seen = -1;
        int quiet = 0;
        final long deadline = System.currentTimeMillis() + 240_000;
        JsonObject g = http.get("/game");
        while (looked.isEmpty()) {
            assertTrue(System.currentTimeMillis() < deadline, "no surveil in time; last state " + g);
            final JsonObject status = http.get("/wait", Map.of("since", String.valueOf(seen), "timeout", "3"));
            final long rev = status.get("revision").getAsLong();
            if (rev == seen && ++quiet < 2) {
                continue;
            }
            quiet = 0;
            seen = rev;
            g = http.get("/game");
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended before the surveil: " + g);
            final JsonObject buttons = g.getAsJsonObject("buttons");
            final boolean okEnabled = buttons.get("okEnabled").getAsBoolean();
            final JsonElement pd = g.get("pendingDecision");
            final JsonObject me = me(g);
            final boolean myMain = me != null && me.get("name").getAsString().equals(str(g.get("activePlayer")))
                    && str(g.get("phase")).startsWith("MAIN") && g.getAsJsonArray("stack").isEmpty();
            final boolean settled = System.currentTimeMillis() - lastCardSelect > 2_000;
            final String turn = "t" + str(g.get("turn")) + ":";

            if (pd != null && !pd.isJsonNull()) {
                final JsonObject d = pd.getAsJsonObject();
                final JsonArray choices = new JsonArray();
                if ("order".equals(d.get("kind").getAsString())) {
                    http.record("GET /game#order", g);
                    final JsonArray options = d.getAsJsonArray("options");
                    assertEquals(options.size(), 2, "surveil 2 shows two cards: " + d);
                    assertTrue(d.get("prompt").getAsString().contains("graveyard"), "it asks what goes to the graveyard: " + d);
                    assertEquals(d.get("min").getAsInt(), 0, "none has to go: " + d);
                    assertEquals(d.get("max").getAsInt(), 2, "both may go: " + d);
                    for (final JsonElement o : options) {
                        final Matcher m = ID.matcher(o.getAsString());
                        assertTrue(m.find(), "an option names its card id: " + o);
                        looked.add(Integer.parseInt(m.group(1)));
                    }
                    choices.add(0); // the first card goes to the graveyard; the second stays on top
                } else {
                    choices.add(0);
                }
                http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices));
            } else if (!AgentGameTest.iHavePriority(g) && g.get("prompt").getAsString().startsWith("Waiting for")) {
                // The other player is acting.
            } else if ("Auto".equals(buttons.get("ok").getAsString())) {
                http.post("/game/act", BridgeHttp.body("action", okEnabled ? "ok" : "cancel"));
            } else if (okEnabled && myMain && settled && card(me, "hand", "Island") != null && tried.add(turn + "land")) {
                lastCardSelect = System.currentTimeMillis();
                http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", card(me, "hand", "Island").get("id").getAsInt()));
            } else if (okEnabled && myMain && settled && untapped(me, "Island") >= 2 && card(me, "hand", "Curate") != null
                    && tried.add(turn + "cast")) {
                lastCardSelect = System.currentTimeMillis();
                http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", card(me, "hand", "Curate").get("id").getAsInt()));
            } else if (okEnabled && myMain && !settled) {
                // Wait: OK here would pass priority and leave the main phase.
            } else if (okEnabled) {
                http.post("/game/act", BridgeHttp.body("action", "ok"));
            } else if (buttons.get("cancelEnabled").getAsBoolean()) {
                http.post("/game/act", BridgeHttp.body("action", "cancel"));
            }
        }

        // What the host did with the answer, read from its own game.
        final int toGraveyard = looked.get(0);
        final int kept = looked.get(1);
        AgentGameTest.waitFor("the kept card is drawn", 20_000, () -> ids(ZoneType.Hand).contains(kept));
        assertTrue(ids(ZoneType.Graveyard).contains(toGraveyard), "the other card is in the graveyard");
        assertFalse(http.get("/game").has("pendingDecision"), "the surveil is over");

        http.post("/game/act", BridgeHttp.body("action", "concede"));
        AgentGameTest.waitFor("the game ends on the concession", 30_000, () -> http.get("/game").get("gameOver").getAsBoolean());
        http.post("/game/act", BridgeHttp.body("action", "quit"));
        AgentGameTest.waitFor("host ends the match", 30_000, () -> !lobby.isMatchActive());
        http.post("/disconnect", new JsonObject());
    }

    /** Card ids in one of the agent's zones, from the host's game, top of the library first. */
    private List<Integer> ids(final ZoneType zone) {
        final List<Integer> out = new ArrayList<>();
        for (final Player p : lobby.getHostedMatch().getGame().getPlayers()) {
            if ("AgentBot".equals(p.getName())) {
                for (final Card c : p.getCardsIn(zone)) {
                    out.add(c.getId());
                }
            }
        }
        return out;
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

    private static int untapped(final JsonObject me, final String name) {
        int n = 0;
        if (me != null) {
            for (final JsonElement c : me.getAsJsonArray("battlefield")) {
                final JsonObject card = c.getAsJsonObject();
                if (name.equals(card.get("name").getAsString()) && !card.get("tapped").getAsBoolean()) {
                    n++;
                }
            }
        }
        return n;
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
