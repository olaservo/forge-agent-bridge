package forge.agent;

import static org.testng.Assert.assertNotNull;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.deck.io.DeckSerializer;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.match.Selection;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import io.netty.channel.Channel;

/**
 * One in-process match for a test that needs a single situation: the host seat is the AI with a
 * random deck, the bridge takes the other seat as "AgentBot" with the deck given. The host listens
 * on a free port, so these tests can run beside each other and beside the ones with fixed ports.
 */
final class RiggedMatch {

    static final String ME = "AgentBot";
    static final String HOST = "HostAI";

    ServerGameLobby lobby;
    int hostPort;
    final BridgeHttp http;
    private final FServerManager server = FServerManager.getInstance();
    final AgentBridge bridge = new AgentBridge();
    private final Set<String> tried = new HashSet<>();
    private long lastCardSelect;
    private long seen = -1;
    private int quiet;

    /** Forge's model with the preferences the tests rely on, whatever the Forge profile on this machine holds. */
    static void initializeModel() {
        GuiBase.setInterface(new AgentGuiBase());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.PLAYER_NAME, HOST); // a blank name pops a Swing input dialog at match start
            preferences.setPref(FPref.UI_ENABLE_SOUNDS, false);
            preferences.setPref(FPref.UI_ENABLE_MUSIC, false);
            // The desktop default. With it on, the host sends scry through manipulateCardList and a library
            // search as a selection.
            preferences.setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, true);
            return null;
        });
        FModel.getNetPreferences().setPref(FNetPref.UPnP, "NEVER");
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** The host AI plays a random deck. */
    RiggedMatch(final String deck) throws Exception {
        this(deck, null);
    }

    /** A deck from lines like "20 Forest", for the host AI. */
    static Deck deck(final String name, final String... main) {
        return DeckSerializer.fromSections(Map.of("metadata", List.of("Name=" + name), "main", List.of(main)));
    }

    /** Hosts, seats the agent with this deck, starts the game and waits until the agent is in it. */
    RiggedMatch(final String deck, final Deck hostDeck) throws Exception {
        this(hostDeck);
        start(deck);
    }

    /** Hosts and seats the agent, and leaves it in the lobby without a deck. {@link #start} begins the game. */
    static RiggedMatch inLobby() throws Exception {
        return new RiggedMatch((Deck) null);
    }

    private RiggedMatch(final Deck hostDeck) throws Exception {
        bridge.start(0);
        http = new BridgeHttp(bridge.port());
        host(hostDeck);
    }

    /** Hosts a new lobby on a free port and seats the agent in it. */
    void host(final Deck hostDeck) throws Exception {
        hostPort = freePort();
        lobby = new ServerGameLobby();
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
        hostSlot.setDeck(hostDeck == null ? DeckgenUtil.getRandomColorDeck(true) : hostDeck);
        hostSlot.setIsReady(true);
        lobby.setLimitedMode(true);

        http.post("/connect", BridgeHttp.body("host", "127.0.0.1", "port", hostPort, "name", ME));
        AgentGameTest.waitFor("agent takes lobby slot 1", 20_000, () -> http.get("/status").getAsJsonObject("lobby").get("mySlot").getAsInt() == 1);
    }

    /** Sends the agent's deck, readies, starts the game and waits until the agent is in it. Returns the bridge's answer to the deck. */
    JsonObject start(final String deck) throws Exception {
        final JsonObject sent = http.post("/lobby/deck", BridgeHttp.body("dck", deck));
        http.post("/lobby/ready", BridgeHttp.body("ready", true));
        AgentGameTest.waitFor("host sees the agent's deck and ready flag", 20_000,
                () -> lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);
        final Runnable start = lobby.startGame();
        assertNotNull(start, "lobby accepted the match setup");
        start.run();
        AgentGameTest.waitFor("agent gets openView", 30_000, () -> http.get("/game").get("inGame").getAsBoolean());
        return sent;
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
     * bridge's own), pick the cards a prompt requires, pay with Auto, play this land, cast this spell once
     * enough of the lands are untapped, and otherwise pass. The test handles the prompt it is about before
     * calling this.
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
        } else if (required(g) != null) {
            act("selectCard", required(g));
        } else if (g.get("waiting").getAsBoolean()) {
            // The other player is acting, or the bridge is answering this prompt for the seat.
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

    /** The pending decision when it is of this kind, or null. */
    static JsonObject decision(final JsonObject g, final String kind) {
        final JsonElement pd = g.get("pendingDecision");
        final boolean asked = pd != null && !pd.isJsonNull() && kind.equals(pd.getAsJsonObject().get("kind").getAsString());
        return asked ? pd.getAsJsonObject() : null;
    }

    /** A card to pick next when the prompt requires more cards than are chosen, or null. */
    static Integer required(final JsonObject g) {
        if (!g.has("selection")) {
            return null;
        }
        final JsonObject sel = g.getAsJsonObject("selection");
        final String chosen = sel.getAsJsonArray("chosen").toString();
        if (sel.getAsJsonArray("chosen").size() >= sel.get("min").getAsInt()) {
            return null;
        }
        for (final JsonElement c : sel.getAsJsonArray("cards")) {
            final int id = c.getAsJsonObject().get("id").getAsInt();
            if (!chosen.contains("\"id\":" + id + ",")) {
                return id;
            }
        }
        return null;
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

    /** Stops the host with the game still on, as a killed host stops. The host's game stays as it was, with nobody to play it. */
    void killHost() {
        server.stopServer();
    }

    /** Closes the host's end of the agent's connection, as a network break does. Forge's client then reconnects by itself. */
    void dropConnection() throws Exception {
        final Field channel = RemoteClient.class.getDeclaredField("channel");
        channel.setAccessible(true);
        ((Channel) channel.get(server.findClientByIndex(1))).close();
    }

    /** Has the host send the agent's seat a selection, as an input that offers cards does when it starts and stops. */
    void hostSelects(final Selection selection) {
        server.findClientByIndex(1).getGui().setSelection(selection);
    }

    /** Has the host send the agent's seat a prompt, as the start of its next input does. */
    void hostPrompts(final String message) {
        server.findClientByIndex(1).getGui().showPromptMessage(null, message);
    }

    /** Has the host tell the agent's seat to wait, as it does when one of the seat's inputs ends. The host's own prompt stays up. */
    void hostAwaits() {
        server.findClientByIndex(1).getGui().awaitNextInput();
    }

    /** Has the host send the agent its whole game view again, as it does when the agent reports a checksum mismatch. */
    void resync() {
        server.findClientByIndex(1).getGui().setResyncPending();
    }

    /** True while the host's game thread is parked on the agent's seat, so the host's game is not moving. */
    boolean hostWaitsOnSeat() {
        final HostedMatch hosted = lobby.getHostedMatch();
        if (hosted == null || hosted.getGame() == null || hosted.getGame().isGameOver()) {
            return false;
        }
        for (final PlayerControllerHuman c : hosted.getHumanControllers()) {
            if (c.getInputQueue().getInput() != null) {
                return true;
            }
        }
        return false;
    }

    /** True when the host holds the seat's auto-yields and automatic trigger answers as switched off. */
    boolean hostAsksSeatAboutAutoYields() {
        final HostedMatch hosted = lobby.getHostedMatch();
        if (hosted == null || hosted.getHumanControllers().isEmpty()) {
            return false;
        }
        for (final PlayerControllerHuman c : hosted.getHumanControllers()) {
            if (!c.getYieldController().getDisableAutoYields() || !c.getYieldController().getDisableAutoTriggers()) {
                return false;
            }
        }
        return true;
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

    Card hostCard(final String player, final ZoneType zone) {
        return hostPlayer(player).getCardsIn(zone).getFirst();
    }

    /** Cards of that name in a player's zone, read from the host. */
    int hostCount(final String player, final ZoneType zone, final String name) {
        int n = 0;
        for (final Card c : hostPlayer(player).getCardsIn(zone)) {
            if (name.equals(c.getName())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Moves a card of that name from a player's library, or else its hand, onto the battlefield, in the host's
     * game. Only while {@link #hostWaitsOnSeat} is true: the host's game does not move then.
     */
    void hostPuts(final String player, final String name) {
        final Player p = hostPlayer(player);
        for (final ZoneType zone : List.of(ZoneType.Library, ZoneType.Hand)) {
            for (final Card c : p.getCardsIn(zone)) {
                if (name.equals(c.getName())) {
                    p.getGame().getAction().moveToPlay(c, p, null, AbilityKey.newMap());
                    return;
                }
            }
        }
        throw new IllegalStateException("no " + name + " in the library or hand of " + player);
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
