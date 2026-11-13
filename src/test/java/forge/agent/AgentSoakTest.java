package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;

/**
 * Plays games with random decks on both seats and compares what the bridge reports with the
 * host's own game, which this test can read because it hosts in-process. It does not test a
 * scenario. It looks for the three ways the bridge has gone wrong in live games:
 *
 * <ul>
 * <li>the bridge's state disagrees with the host's (zones, life, turn, phase, stack);</li>
 * <li>the host waits on the seat and the bridge shows nothing to answer;</li>
 * <li>a card the bridge offers in {@code selection} is refused.</li>
 * </ul>
 *
 * The first two fail the test. Everything else goes into a report: which host inputs and decision
 * kinds came up, stalls the policy could not get out of, and how often an action's response was
 * out of date a moment later. Off by default; run with {@code -Dagent.soak.games=<n>}.
 */
public class AgentSoakTest {

    private static final int HOST_PORT = 36796;
    /** A game still going after this long is conceded, wherever it is. Lower it to test conceding mid-game. */
    private static final long GAME_MILLIS = Long.getLong("agent.soak.gameSeconds", 360) * 1000;
    private static final long CONCEDE_MILLIS = 30_000;
    private static final long STALL_MILLIS = 20_000;
    private static final int MAX_TURN = 30;

    /**
     * Every second game: cheap creatures, so the seat has blockers and the combat prompts come up. Two of them
     * have trample; a blocked trampler is the common case where the bridge divides combat damage for the seat.
     */
    private static final String CREATURES = String.join("\n",
            "[metadata]",
            "Name=Soak creatures",
            "[Main]",
            "17 Forest",
            "4 Llanowar Elves",
            "4 Charging Badger",
            "4 Garruk's Companion",
            "4 Centaur Courser",
            "4 Giant Spider",
            "3 Rumbling Baloth");

    private final int games = Integer.getInteger("agent.soak.games", 0);
    private final long seed = Long.getLong("agent.soak.seed", System.currentTimeMillis());
    private final Random rnd = new Random(seed);

    private FServerManager server;
    private ServerGameLobby lobby;
    private AgentBridge bridge;
    private BridgeHttp http;

    // the report
    private final Map<String, Integer> hostInputs = new TreeMap<>();
    private final Map<String, Integer> decisionKinds = new TreeMap<>();
    private final Set<String> prompts = new TreeSet<>();
    private final List<String> mismatches = new ArrayList<>();
    private final List<String> deadPrompts = new ArrayList<>();
    private final List<String> stalls = new ArrayList<>();
    private final List<String> refusedSelections = new ArrayList<>();
    private final List<String> uncaught = new ArrayList<>();
    private final List<JsonObject> results = new ArrayList<>();
    private int comparisons;
    private int responses;
    private int supersededResponses;
    private int blocksDeclared;
    private int blocksShownBeforeOk;
    private int playerTargetsTried;
    private int playerTargetsAccepted;
    /** What the bridge told the seat in messages without asking it anything: at least this many. */
    private final Map<String, Integer> doneForTheSeat = new TreeMap<>();
    private final Set<String> messagesSeen = new HashSet<>();

    @BeforeClass
    public void initializeModel() {
        if (games <= 0) {
            throw new SkipException("soak test is off; run with -Dagent.soak.games=<n>");
        }
        GuiBase.setInterface(new AgentGuiBase());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.PLAYER_NAME, "HostAI");
            preferences.setPref(FPref.UI_ENABLE_SOUNDS, false);
            preferences.setPref(FPref.UI_ENABLE_MUSIC, false);
            return null;
        });
        FModel.getNetPreferences().setPref(FNetPref.UPnP, "NEVER");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (bridge != null) {
            bridge.stop();
        }
        if (server != null) {
            server.stopServer();
        }
    }

    @Test
    public void bridgeAgreesWithTheHostOverRandomGames() throws Exception {
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            uncaught.add(t.getName() + ": " + e);
            e.printStackTrace();
        });
        try {
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
            lobby.getSlot(0).setType(LobbySlotType.AI);
            lobby.setLimitedMode(true);

            bridge = new AgentBridge();
            bridge.start(0);
            http = new BridgeHttp(bridge.port());
            http.post("/connect", BridgeHttp.body("host", "127.0.0.1", "port", HOST_PORT, "name", "AgentBot"));
            AgentGameTest.waitFor("agent takes lobby slot 1", 20_000, () -> http.get("/status").getAsJsonObject("lobby").get("mySlot").getAsInt() == 1);

            for (int i = 0; i < games; i++) {
                playOneGame(i);
            }
            http.post("/disconnect", new JsonObject());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
            writeReport();
        }
        assertEquals(mismatches, List.of(), "the bridge's state agrees with the host's");
        assertEquals(deadPrompts, List.of(), "whenever the host waits on the seat, the bridge shows something to answer");
        assertEquals(refusedSelections, List.of(), "every card offered in selection is accepted");
    }

    private void playOneGame(final int index) throws Exception {
        final LobbySlot hostSlot = lobby.getSlot(0);
        hostSlot.setDeck(DeckgenUtil.getRandomColorDeck(true));
        hostSlot.setIsReady(true);
        final String deckName;
        if (index % 2 == 1) {
            deckName = "Soak creatures";
            http.post("/lobby/deck", BridgeHttp.body("dck", CREATURES));
        } else {
            final Deck mine = randomDeck();
            deckName = mine.getName();
            final File tmp = File.createTempFile("agent-soak", ".dck");
            DeckSerializer.writeDeck(mine, tmp);
            http.post("/lobby/deck", BridgeHttp.body("dck", Files.readString(tmp.toPath())));
            tmp.delete();
        }
        http.post("/lobby/ready", BridgeHttp.body("ready", true));
        AgentGameTest.waitFor("host sees the agent's deck and ready flag", 20_000,
                () -> lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);
        final Runnable start = lobby.startGame();
        assertNotNull(start, "lobby accepted the match setup");
        start.run();
        AgentGameTest.waitFor("agent gets openView", 30_000, () -> http.get("/game").get("inGame").getAsBoolean());

        final Set<String> tried = new HashSet<>();
        final long began = System.currentTimeMillis();
        long lastCardSelect = 0;
        long lastProgress = began;
        String lastSig = "";
        int stallsHere = 0;
        long concededAt = 0;
        long seen = -1;
        int quiet = 0;
        String ending = "played out";
        JsonObject g = http.get("/game");
        while (!g.get("gameOver").getAsBoolean()) {
            final JsonObject status = http.get("/wait", Map.of("since", String.valueOf(seen), "timeout", "2"));
            final long rev = status.get("revision").getAsLong();
            if (rev == seen && ++quiet < 2) {
                continue;
            }
            quiet = 0;
            seen = rev;
            g = http.get("/game");
            if (g.get("gameOver").getAsBoolean()) {
                break;
            }
            final long now = System.currentTimeMillis();
            final Input hostInput = hostInput();
            final JsonElement pd = g.get("pendingDecision");
            final boolean deciding = pd != null && !pd.isJsonNull();
            final JsonObject buttons = g.getAsJsonObject("buttons");
            final boolean okEnabled = buttons.get("okEnabled").getAsBoolean();
            final boolean cancelEnabled = buttons.get("cancelEnabled").getAsBoolean();
            final JsonArray offered = g.has("selection") ? g.getAsJsonObject("selection").getAsJsonArray("cards") : new JsonArray();
            final String prompt = g.get("prompt").getAsString();
            final String turnKey = "t" + str(g.get("turn")) + ":";

            if (hostInput != null) {
                hostInputs.merge(name(hostInput), 1, Integer::sum);
                prompts.add(name(hostInput) + ": " + firstLine(prompt).replaceAll("\\(\\d+\\)", "(n)").replaceAll("\\d+", "n"));
            }
            if (hostInput != null || deciding) {
                compare(index);
            }
            for (final JsonElement m : g.getAsJsonArray("messages")) {
                final String text = m.getAsString();
                final String what = text.startsWith("Split refused") ? "split refused" : text.startsWith("Revealed, ") ? "reveal shown" : null;
                if (what != null && messagesSeen.add(index + ":" + text)) {
                    doneForTheSeat.merge(what, 1, Integer::sum);
                }
            }

            if (concededAt > 0) {
                // Conceding ends the game at once unless the host is still waiting on the seat for something.
                if (deciding) {
                    answer(pd.getAsJsonObject());
                } else if (now - concededAt > CONCEDE_MILLIS) {
                    deadPrompts.add("game " + index + " turn " + str(g.get("turn")) + ": conceded and the game did not end, host input "
                            + (hostInput == null ? "none" : name(hostInput)) + ", prompt \"" + firstLine(prompt) + "\"");
                    break;
                }
                continue;
            }

            // Stalls: nothing on either side has changed for a while.
            final String sig = signature(g) + "|" + (hostInput == null ? "" : name(hostInput));
            if (!sig.equals(lastSig)) {
                lastSig = sig;
                lastProgress = now;
            } else if (now - lastProgress > STALL_MILLIS) {
                final boolean answerable = deciding || okEnabled || cancelEnabled || !offered.isEmpty();
                final String what = "game " + index + " turn " + str(g.get("turn")) + " " + str(g.get("phase")) + ", host input "
                        + (hostInput == null ? "none" : name(hostInput)) + ", prompt \"" + firstLine(prompt) + "\", ok "
                        + okEnabled + ", cancel " + cancelEnabled + ", offered " + offered.size();
                if (hostInput != null && !answerable) {
                    deadPrompts.add(what);
                } else {
                    stalls.add(what);
                }
                lastProgress = now;
                if (++stallsHere >= 3) {
                    ending = "conceded after stalls";
                    concededAt = now;
                    act("concede", null);
                    continue;
                }
                act(cancelEnabled ? "cancel" : "ok", null);
                continue;
            }
            if (now - began > GAME_MILLIS || g.get("turn").getAsInt() > MAX_TURN) {
                ending = "conceded at the time or turn limit";
                concededAt = now;
                act("concede", null);
                continue;
            }

            final JsonObject me = me(g);
            final boolean myTurn = me != null && me.get("name").getAsString().equals(str(g.get("activePlayer")));
            final String phase = str(g.get("phase"));
            final boolean settled = now - lastCardSelect > 2_000;
            final boolean stackEmpty = g.getAsJsonArray("stack").isEmpty();

            if (deciding) {
                answer(pd.getAsJsonObject());
            } else if (hostInput != null && "InputSelectTargets".equals(name(hostInput)) && settled && opponentId(g) >= 0
                    && tried.add(turnKey + prompt + ":player")) {
                // The bridge does not say whether a player is a legal target, so try the opponent once per prompt.
                lastCardSelect = System.currentTimeMillis();
                playerTargetsTried++;
                act("selectPlayer", opponentId(g));
                final Input after = hostInput();
                if (after == null || !"InputSelectTargets".equals(name(after)) || !prompt.equals(http.get("/game").get("prompt").getAsString())) {
                    playerTargetsAccepted++;
                }
            } else if (!offered.isEmpty()) {
                // The prompt offers cards. Take one we have not tried for this prompt, else finish or back out.
                if (!settled) {
                    continue;
                }
                JsonObject pick = null;
                for (final JsonElement c : shuffled(offered)) {
                    if (tried.add(turnKey + prompt + ":" + c.getAsJsonObject().get("id").getAsInt())) {
                        pick = c.getAsJsonObject();
                        break;
                    }
                }
                if (pick != null) {
                    lastCardSelect = System.currentTimeMillis();
                    final int wrong = g.get("incorrectActions").getAsInt();
                    final JsonObject r = act("selectCard", pick.get("id").getAsInt());
                    if (r.get("incorrectActions").getAsInt() > wrong) {
                        refusedSelections.add("game " + index + " turn " + str(g.get("turn")) + ": " + pick.get("name").getAsString()
                                + " offered by \"" + firstLine(prompt) + "\"");
                    }
                } else {
                    act(okEnabled ? "ok" : "cancel", null);
                }
            } else if (!AgentGameTest.iHavePriority(g) && prompt.startsWith("Waiting for")) {
                // The other player is acting.
            } else if ("Auto".equals(buttons.get("ok").getAsString())) {
                act(okEnabled ? "ok" : "cancel", null);
            } else if (okEnabled && myTurn && "COMBAT_DECLARE_ATTACKERS".equals(phase) && tried.add(turnKey + "attack")) {
                // Every other turn, so that some turns leave untapped creatures to block with.
                if (rnd.nextBoolean()) {
                    act("alphaStrike", null);
                }
            } else if (okEnabled && !myTurn && "COMBAT_DECLARE_BLOCKERS".equals(phase) && g.has("combat") && settled
                    && untappedCreature(me) != null && !g.getAsJsonArray("combat").isEmpty() && tried.add(turnKey + "block")) {
                final JsonObject blocker = untappedCreature(me);
                final int attacker = g.getAsJsonArray("combat").get(0).getAsJsonObject().get("attackerId").getAsInt();
                act("selectCard", attacker);
                act("selectCard", blocker.get("id").getAsInt());
                lastCardSelect = System.currentTimeMillis();
                final JsonObject mid = http.get("/game");
                final JsonObject mine2 = card(me(mid), blocker.get("id").getAsInt());
                if (mine2 != null && mine2.get("blocking").getAsBoolean()) {
                    blocksDeclared++;
                    if (mid.has("combat") && mid.get("combat").toString().contains("#" + blocker.get("id").getAsInt() + "\"")) {
                        blocksShownBeforeOk++;
                    }
                }
            } else if (okEnabled && myTurn && phase.startsWith("MAIN") && stackEmpty && nextUntried(me, tried, turnKey) != null) {
                // Each hand card and each nonland permanent once a turn: land drops, casts and abilities.
                if (!settled) {
                    continue; // not OK: that would pass priority and leave the main phase
                }
                final JsonObject card = nextUntried(me, tried, turnKey);
                tried.add(turnKey + "play:" + card.get("id").getAsInt());
                lastCardSelect = System.currentTimeMillis();
                lastProgress = lastCardSelect; // the host does not react to a card that cannot be played
                act("selectCard", card.get("id").getAsInt());
            } else if (okEnabled) {
                act("ok", null);
            } else if (cancelEnabled) {
                act("cancel", null);
            } else if (settled && me != null && me.has("hand") && !me.getAsJsonArray("hand").isEmpty()) {
                lastCardSelect = System.currentTimeMillis();
                act("selectCard", me.getAsJsonArray("hand").get(0).getAsJsonObject().get("id").getAsInt());
            }
        }

        final JsonObject end = http.get("/game");
        final JsonObject result = new JsonObject();
        result.addProperty("game", index);
        result.addProperty("deck", deckName);
        result.addProperty("turns", end.get("turn").getAsInt());
        result.addProperty("winner", str(end.get("winner")));
        result.addProperty("ending", ending);
        result.addProperty("seconds", (System.currentTimeMillis() - began) / 1000);
        results.add(result);
        System.out.println("[AgentSoakTest] " + result);

        http.post("/game/act", BridgeHttp.body("action", "quit"));
        AgentGameTest.waitFor("host ends the match", 30_000, () -> !lobby.isMatchActive());
    }

    /** The seat's deck: the AI's simple cards one game in three, otherwise any card or a preconstructed deck. */
    private Deck randomDeck() {
        Deck d = null;
        switch (rnd.nextInt(3)) {
            case 0 -> d = DeckgenUtil.getRandomColorDeck(false);
            case 1 -> d = DeckgenUtil.getRandomPreconDeck();
            default -> d = DeckgenUtil.getRandomColorDeck(true);
        }
        return d == null ? DeckgenUtil.getRandomColorDeck(true) : d;
    }

    /** Anonymous inputs have no simple name. */
    private static String name(final Input in) {
        final String n = in.getClass().getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }

    // ---- acting ----

    /** Sends one action and notes whether its response still described the seat a moment later. */
    private JsonObject act(final String action, final Integer id) throws Exception {
        final JsonObject r = http.post("/game/act", BridgeHttp.body("action", action, "id", id));
        noteResponse(r);
        return r;
    }

    private void answer(final JsonObject d) throws Exception {
        final String kind = d.get("kind").getAsString();
        decisionKinds.merge(kind, 1, Integer::sum);
        final JsonArray choices = new JsonArray();
        String text = null;
        if ("assign".equals(kind)) {
            // Half the time the bridge's own split, half the time everything on the first option, which the
            // bridge refuses when the rule forbids it.
            final int n = d.getAsJsonArray("options").size();
            final StringBuilder all = new StringBuilder(d.get("amount").getAsString());
            for (int i = 1; i < n; i++) {
                all.append(",0");
            }
            text = rnd.nextBoolean() ? d.get("default").getAsString() : all.toString();
        } else if ("text".equals(kind) || "number".equals(kind)) {
            text = d.get("default").isJsonNull() || d.get("default").getAsString().isEmpty() ? "1" : d.get("default").getAsString();
        } else {
            final int n = d.getAsJsonArray("options").size();
            final int min = Math.max(0, d.get("min").getAsInt());
            final int max = Math.min(n, Math.max(min, d.get("max").getAsInt()));
            final int count = Math.min(n, min + (max > min ? rnd.nextInt(Math.min(2, max - min) + 1) : 0));
            final List<Integer> all = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                all.add(i);
            }
            java.util.Collections.shuffle(all, rnd);
            for (final int i : all.subList(0, count)) {
                choices.add(i);
            }
        }
        noteResponse(http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices, "text", text)));
    }

    private void noteResponse(final JsonObject r) throws Exception {
        responses++;
        final String told = summary(r);
        if (!told.equals(summary(http.get("/game")))) {
            supersededResponses++;
        }
    }

    // ---- comparing with the host ----

    private Input hostInput() {
        final HostedMatch match = lobby.getHostedMatch();
        if (match == null || match.getHumanControllers() == null) {
            return null;
        }
        for (final PlayerControllerHuman c : match.getHumanControllers()) {
            final Input in = c.getInputQueue().getInput();
            if (in != null) {
                return in;
            }
        }
        return null;
    }

    /**
     * The host's game thread is parked on our seat, so its state is not moving. The bridge's view
     * arrives over the network, so allow it a moment to catch up before calling a difference real.
     */
    private void compare(final int index) throws Exception {
        String diff = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            final String host;
            try {
                host = hostState();
            } catch (final RuntimeException e) {
                return; // the host moved on while we were reading it
            }
            final JsonObject g = http.get("/game");
            if (host == null || g.get("gameOver").getAsBoolean()) {
                return;
            }
            final String mine = bridgeState(g);
            if (host.equals(mine)) {
                comparisons++;
                return;
            }
            diff = "game " + index + "\n  host   " + host + "\n  bridge " + mine;
            Thread.sleep(500);
        }
        comparisons++;
        if (mismatches.size() < 20) {
            mismatches.add(diff);
        }
    }

    private String hostState() {
        final HostedMatch match = lobby.getHostedMatch();
        final Game game = match == null ? null : match.getGame();
        if (game == null || game.isGameOver()) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("turn ").append(game.getPhaseHandler().getTurn()).append(' ')
                .append(game.getPhaseHandler().getPhase() == null ? "" : game.getPhaseHandler().getPhase().name())
                .append(" active ").append(game.getPhaseHandler().getPlayerTurn() == null ? null : game.getPhaseHandler().getPlayerTurn().getName())
                .append(" stack ").append(game.getStack().size());
        final Map<String, String> players = new TreeMap<>();
        for (final Player p : game.getPlayers()) {
            final Set<String> field = new TreeSet<>();
            for (final Card c : p.getCardsIn(ZoneType.Battlefield, false)) { // false: phased-out cards too, as the bridge lists them
                field.add(c.getId() + (c.isTapped() ? "T" : "") + (c.isPhasedOut() ? "P" : ""));
            }
            players.put(p.getName(), "life " + p.getLife() + " hand " + p.getCardsIn(ZoneType.Hand).size() + " library "
                    + p.getCardsIn(ZoneType.Library).size() + " graveyard " + p.getCardsIn(ZoneType.Graveyard).size() + " field " + field);
        }
        return sb.append(' ').append(players).toString();
    }

    private static String bridgeState(final JsonObject g) {
        final StringBuilder sb = new StringBuilder();
        sb.append("turn ").append(str(g.get("turn"))).append(' ').append(str(g.get("phase")))
                .append(" active ").append(g.get("activePlayer").isJsonNull() ? null : g.get("activePlayer").getAsString())
                .append(" stack ").append(g.getAsJsonArray("stack").size());
        final Map<String, String> players = new TreeMap<>();
        for (final JsonElement e : g.getAsJsonArray("players")) {
            final JsonObject p = e.getAsJsonObject();
            final Set<String> field = new TreeSet<>();
            for (final JsonElement c : p.getAsJsonArray("battlefield")) {
                field.add(c.getAsJsonObject().get("id").getAsInt() + (c.getAsJsonObject().get("tapped").getAsBoolean() ? "T" : "")
                        + (c.getAsJsonObject().has("phasedOut") ? "P" : ""));
            }
            players.put(p.get("name").getAsString(), "life " + p.get("life").getAsInt() + " hand " + p.get("handSize").getAsInt() + " library "
                    + p.get("librarySize").getAsInt() + " graveyard " + p.get("graveyardSize").getAsInt() + " field " + field);
        }
        return sb.append(' ').append(players).toString();
    }

    // ---- reading the bridge's game ----

    private static String str(final JsonElement e) {
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static String firstLine(final String s) {
        final int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** What an action's response tells the agent to do next. */
    private static String summary(final JsonObject g) {
        final JsonElement pd = g.get("pendingDecision");
        return str(g.get("prompt")) + "|" + g.get("buttons") + "|" + (pd == null || pd.isJsonNull() ? "" : pd.getAsJsonObject().get("id"));
    }

    private static String signature(final JsonObject g) {
        final StringBuilder sb = new StringBuilder(summary(g));
        sb.append('|').append(str(g.get("turn"))).append(str(g.get("phase"))).append(g.getAsJsonArray("stack").size());
        for (final JsonElement e : g.getAsJsonArray("players")) {
            final JsonObject p = e.getAsJsonObject();
            sb.append('|').append(p.get("life")).append(p.get("handSize")).append(p.get("hasPriority")).append(p.getAsJsonArray("battlefield").size());
        }
        return sb.toString();
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

    private static int opponentId(final JsonObject g) {
        for (final JsonElement p : g.getAsJsonArray("players")) {
            if (!p.getAsJsonObject().get("isMe").getAsBoolean()) {
                return p.getAsJsonObject().get("id").getAsInt();
            }
        }
        return -1;
    }

    private static JsonObject card(final JsonObject me, final int id) {
        if (me == null) {
            return null;
        }
        for (final JsonElement c : me.getAsJsonArray("battlefield")) {
            if (c.getAsJsonObject().get("id").getAsInt() == id) {
                return c.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonObject untappedCreature(final JsonObject me) {
        if (me == null) {
            return null;
        }
        for (final JsonElement c : me.getAsJsonArray("battlefield")) {
            final JsonObject card = c.getAsJsonObject();
            if (str(card.get("type")).contains("Creature") && !card.get("tapped").getAsBoolean()) {
                return card;
            }
        }
        return null;
    }

    private static JsonObject nextUntried(final JsonObject me, final Set<String> tried, final String turnKey) {
        if (me == null) {
            return null;
        }
        // Lands first: a spell tried before the land drop fails for mana and is not tried again that turn.
        final List<JsonElement> cards = new ArrayList<>();
        if (me.has("hand")) {
            for (final JsonElement c : me.getAsJsonArray("hand")) {
                if (str(c.getAsJsonObject().get("type")).contains("Land")) {
                    cards.add(c);
                }
            }
            for (final JsonElement c : me.getAsJsonArray("hand")) {
                if (!str(c.getAsJsonObject().get("type")).contains("Land")) {
                    cards.add(c);
                }
            }
        }
        for (final JsonElement c : me.getAsJsonArray("battlefield")) {
            if (!str(c.getAsJsonObject().get("type")).contains("Land")) {
                cards.add(c);
            }
        }
        for (final JsonElement c : cards) {
            if (!tried.contains(turnKey + "play:" + c.getAsJsonObject().get("id").getAsInt())) {
                return c.getAsJsonObject();
            }
        }
        return null;
    }

    private List<JsonElement> shuffled(final JsonArray cards) {
        final List<JsonElement> list = new ArrayList<>();
        cards.forEach(list::add);
        java.util.Collections.shuffle(list, rnd);
        return list;
    }

    // ---- report ----

    private void writeReport() throws Exception {
        final JsonObject report = new JsonObject();
        final com.google.gson.Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        report.addProperty("seed", seed);
        report.add("games", gson.toJsonTree(results));
        report.addProperty("comparisonsWithHost", comparisons);
        report.add("mismatches", gson.toJsonTree(mismatches));
        report.add("deadPrompts", gson.toJsonTree(deadPrompts));
        report.add("refusedSelections", gson.toJsonTree(refusedSelections));
        report.add("stalls", gson.toJsonTree(stalls));
        report.add("uncaughtExceptions", gson.toJsonTree(uncaught));
        report.addProperty("responses", responses);
        report.addProperty("responsesOutOfDateAtOnce", supersededResponses);
        report.addProperty("blocksDeclared", blocksDeclared);
        report.addProperty("blocksShownInCombatBeforeOk", blocksShownBeforeOk);
        report.addProperty("playerTargetsTried", playerTargetsTried);
        report.addProperty("playerTargetsAccepted", playerTargetsAccepted);
        report.add("bridgeMessages", gson.toJsonTree(doneForTheSeat));
        report.add("decisionKinds", gson.toJsonTree(decisionKinds));
        report.add("hostInputs", gson.toJsonTree(hostInputs));
        report.add("prompts", gson.toJsonTree(prompts));
        final Path dir = Paths.get(System.getProperty("agent.soak.dir", "target/agent-soak"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.json"), gson.toJson(report) + "\n");
        System.out.println("[AgentSoakTest] report at " + dir.resolve("report.json").toAbsolutePath());
        System.out.println("[AgentSoakTest] " + results.size() + " games, " + comparisons + " comparisons, " + mismatches.size() + " mismatches, "
                + deadPrompts.size() + " dead prompts, " + stalls.size() + " stalls, " + refusedSelections.size() + " refused selections, "
                + supersededResponses + "/" + responses + " responses out of date at once, decisions " + decisionKinds + ", host inputs " + hostInputs);
    }
}
