package forge.agent;

import static org.testng.Assert.assertNotNull;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * One in-process match for a test that needs a single situation: the host seat is the AI with a
 * random deck, the bridge takes the other seat as "AgentBot" with the deck given. The host listens
 * on a free port, so these tests can run beside each other and beside the ones with fixed ports.
 */
final class RiggedMatch {

    static final String ME = "AgentBot";
    static final String HOST = "HostAI";

    final ServerGameLobby lobby = new ServerGameLobby();
    final BridgeHttp http;
    private final FServerManager server = FServerManager.getInstance();
    private final AgentBridge bridge = new AgentBridge();
    private final Set<String> tried = new HashSet<>();
    private long lastCardSelect;
    private long seen = -1;
    private int quiet;

    static void initializeModel() {
        GuiBase.setInterface(new AgentGuiBase());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.PLAYER_NAME, HOST); // a blank name pops a Swing input dialog at match start
            preferences.setPref(FPref.UI_ENABLE_SOUNDS, false);
            preferences.setPref(FPref.UI_ENABLE_MUSIC, false);
            return null;
        });
        FModel.getNetPreferences().setPref(FNetPref.UPnP, "NEVER");
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Hosts, seats the agent with this deck, starts the game and waits until the agent is in it. */
    RiggedMatch(final String deck) throws Exception {
        final int hostPort = freePort();
        server.startServer(hostPort);
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

        bridge.start(0);
        http = new BridgeHttp(bridge.port());
        http.post("/connect", BridgeHttp.body("host", "127.0.0.1", "port", hostPort, "name", ME));
        AgentGameTest.waitFor("agent takes lobby slot 1", 20_000, () -> http.get("/status").getAsJsonObject("lobby").get("mySlot").getAsInt() == 1);
        http.post("/lobby/deck", BridgeHttp.body("dck", deck));
        http.post("/lobby/ready", BridgeHttp.body("ready", true));
        AgentGameTest.waitFor("host sees the agent's deck and ready flag", 20_000,
                () -> lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);
        final Runnable start = lobby.startGame();
        assertNotNull(start, "lobby accepted the match setup");
        start.run();
        AgentGameTest.waitFor("agent gets openView", 30_000, () -> http.get("/game").get("inGame").getAsBoolean());
    }

    /** Waits until something changed, or a few seconds passed with nothing, and returns the game. */
    JsonObject next() throws Exception {
        while (true) {
            final JsonObject status = http.get("/wait", Map.of("since", String.valueOf(seen), "timeout", "3"));
            final long rev = status.get("revision").getAsLong();
            if (rev == seen && ++quiet < 2) {
                continue;
            }
            quiet = 0;
            seen = rev;
            return http.get("/game");
        }
    }

    /**
     * The moves every one of these tests makes: answer a decision with its first option (a split with the
     * bridge's own), pay with Auto, play this land, cast this spell once enough of the lands are untapped,
     * and otherwise pass. The test handles the prompt it is about before calling this.
     */
    void basics(final JsonObject g, final String land, final String spell, final int landsNeeded) throws Exception {
        final JsonObject buttons = g.getAsJsonObject("buttons");
        final boolean okEnabled = buttons.get("okEnabled").getAsBoolean();
        final JsonElement pd = g.get("pendingDecision");
        final JsonObject me = me(g);
        final boolean myMain = me != null && ME.equals(str(g.get("activePlayer"))) && str(g.get("phase")).startsWith("MAIN")
                && g.getAsJsonArray("stack").isEmpty();
        final boolean settled = System.currentTimeMillis() - lastCardSelect > 2_000;
        final String turn = "t" + str(g.get("turn")) + ":";

        if (pd != null && !pd.isJsonNull()) {
            final JsonObject d = pd.getAsJsonObject();
            final JsonArray choices = new JsonArray();
            choices.add(0);
            final boolean split = "assign".equals(d.get("kind").getAsString());
            http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices, "text", split ? d.get("default").getAsString() : null));
        } else if (!AgentGameTest.iHavePriority(g) && g.get("prompt").getAsString().startsWith("Waiting for")) {
            // The other player is acting.
        } else if ("Auto".equals(buttons.get("ok").getAsString())) {
            act(okEnabled ? "ok" : "cancel", null);
        } else if (okEnabled && myMain && !settled) {
            // Wait: OK here would pass priority and leave the main phase.
        } else if (okEnabled && myMain && card(me, "hand", land) != null && tried.add(turn + "land")) {
            lastCardSelect = System.currentTimeMillis();
            act("selectCard", card(me, "hand", land).get("id").getAsInt());
        } else if (okEnabled && myMain && untapped(me, land) >= landsNeeded && card(me, "hand", spell) != null && tried.add(turn + "cast")) {
            lastCardSelect = System.currentTimeMillis();
            act("selectCard", card(me, "hand", spell).get("id").getAsInt());
        } else if (okEnabled) {
            act("ok", null);
        } else if (buttons.get("cancelEnabled").getAsBoolean()) {
            act("cancel", null);
        }
    }

    JsonObject act(final String action, final Integer id) throws Exception {
        return http.post("/game/act", BridgeHttp.body("action", action, "id", id));
    }

    /** Concedes unless the game is over, ends the match and leaves the lobby. */
    void finish() throws Exception {
        if (!http.get("/game").get("gameOver").getAsBoolean()) {
            act("concede", null);
            AgentGameTest.waitFor("the game ends on the concession", 30_000, () -> http.get("/game").get("gameOver").getAsBoolean());
        }
        act("quit", null);
        AgentGameTest.waitFor("host ends the match", 30_000, () -> !lobby.isMatchActive());
        http.post("/disconnect", new JsonObject());
    }

    void close(final String fixturePrefix) throws Exception {
        if (http != null) {
            http.saveFixtures(fixturePrefix);
        }
        bridge.stop();
        server.stopServer();
    }

    // ---- the host's own game ----

    private Player hostPlayer(final String name) {
        for (final Player p : lobby.getHostedMatch().getGame().getPlayers()) {
            if (name.equals(p.getName())) {
                return p;
            }
        }
        throw new IllegalStateException("no player " + name);
    }

    /** Card ids in a player's zone, read from the host, top of the library first. */
    List<Integer> hostIds(final String player, final ZoneType zone) {
        final List<Integer> out = new ArrayList<>();
        for (final Card c : hostPlayer(player).getCardsIn(zone)) {
            out.add(c.getId());
        }
        return out;
    }

    int hostLife(final String player) {
        return hostPlayer(player).getLife();
    }

    // ---- the bridge's game ----

    static String str(final JsonElement e) {
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    static JsonObject player(final JsonObject g, final boolean mine) {
        final JsonElement players = g.get("players");
        if (players == null || !players.isJsonArray()) {
            return null;
        }
        for (final JsonElement p : players.getAsJsonArray()) {
            if (p.getAsJsonObject().get("isMe").getAsBoolean() == mine) {
                return p.getAsJsonObject();
            }
        }
        return null;
    }

    static JsonObject me(final JsonObject g) {
        return player(g, true);
    }

    /** An untapped card of that name in one of the agent's zones. */
    static JsonObject card(final JsonObject me, final String zone, final String name) {
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

    static int untapped(final JsonObject me, final String name) {
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
}
