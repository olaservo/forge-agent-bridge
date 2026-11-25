package forge.agent;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.player.PlayerControllerHuman;

/**
 * Spike for the copilot design note, design A: {@link MatchHost} hosts, two bridge seats join over the
 * network, and a {@link CopilotTap} is installed for the "Human" seat's slot before the game starts.
 * The test prints what the tap saw. Lines start with {@code [spike]} or {@code [tap]}.
 */
public class CopilotTapSpikeTest {

    private static final String HUMAN = "Johnny";
    private static final String OPPONENT = "Opponent";
    private static final String HUMAN_DECK = String.join("\n",
            "[metadata]",
            "Name=Copilot Spike Human",
            "[Main]",
            "17 Mountain",
            "9 Goblin Piker",
            "7 Shock",
            "7 Magma Jet");
    private static final String OPPONENT_DECK = String.join("\n",
            "[metadata]",
            "Name=Copilot Spike Opponent",
            "[Main]",
            "30 Mountain",
            "10 Goblin Guide");

    private static final Set<String> BASELINE_PROMPTS = new LinkedHashSet<>();
    private static final Set<String> TAPPED_PROMPTS = new LinkedHashSet<>();

    private FServerManager server;
    private MatchHost host;
    private ServerGameLobby lobby;
    private int hostPort;
    private final List<AgentBridge> bridges = new ArrayList<>();

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        for (final AgentBridge b : bridges) {
            b.stop();
        }
        bridges.clear();
        if (host != null) {
            host.close();
        }
    }

    /** One seat as a test drives it. */
    private final class Seat {
        final String name;
        final AgentBridge bridge = new AgentBridge();
        final BridgeHttp http;
        final String[] spells;
        final int[] costs;
        final boolean attacks;
        final Set<String> tried = new HashSet<>();
        final Set<String> castOnce = new LinkedHashSet<>();
        final Set<String> promptShapes = new LinkedHashSet<>();
        final Set<String> messages = new LinkedHashSet<>();
        long lastCardSelect;
        String lastActKey = "";
        long lastActAt;
        boolean hold;

        Seat(final String name, final boolean attacks, final String[] spells, final int[] costs) throws Exception {
            this.name = name;
            this.attacks = attacks;
            this.spells = spells;
            this.costs = costs;
            bridge.start(0);
            bridges.add(bridge);
            http = new BridgeHttp(bridge.port());
        }

        JsonObject game() throws Exception {
            final JsonObject g = http.get("/game");
            promptShapes.add(shape(g));
            if (g.has("messages") && g.get("messages").isJsonArray()) {
                for (final JsonElement m : g.getAsJsonArray("messages")) {
                    messages.add(m.getAsString());
                }
            }
            return g;
        }

        /** True when this seat sits on a plain priority prompt with nothing else open. */
        boolean atPriorityPrompt(final JsonObject g) {
            final JsonObject buttons = g.getAsJsonObject("buttons");
            final JsonElement pd = g.get("pendingDecision");
            return (pd == null || pd.isJsonNull()) && !g.has("selection") && !g.get("waiting").getAsBoolean()
                    && AgentGameTest.iHavePriority(g) && buttons.get("okEnabled").getAsBoolean()
                    && !"Auto".equals(buttons.get("ok").getAsString()) && g.getAsJsonArray("stack").isEmpty();
        }

        /** The moves of {@link RiggedMatch#basics}, for a seat with a name of its own and more than one spell. */
        void step(final JsonObject g) throws Exception {
            if (!g.get("inGame").getAsBoolean() || !g.has("buttons") || g.get("gameOver").getAsBoolean()) {
                return;
            }
            final JsonObject buttons = g.getAsJsonObject("buttons");
            final boolean okEnabled = buttons.get("okEnabled").getAsBoolean();
            final JsonElement pd = g.get("pendingDecision");
            final JsonObject me = RiggedMatch.me(g);
            final String phase = RiggedMatch.str(g.get("phase"));
            final boolean myTurn = me != null && RiggedMatch.str(g.get("me")).equals(RiggedMatch.str(g.get("activePlayer")));
            final boolean myMain = myTurn && phase.startsWith("MAIN") && g.getAsJsonArray("stack").isEmpty();
            final boolean settled = System.currentTimeMillis() - lastCardSelect > 2_000;
            final String turn = "t" + RiggedMatch.str(g.get("turn")) + ":";
            final JsonObject sel = g.has("selection") ? g.getAsJsonObject("selection") : null;
            final JsonArray targets = sel == null || !sel.has("players") ? new JsonArray() : sel.getAsJsonArray("players");

            final String key = g.get("promptCount") + "|" + g.get("prompt") + "|" + (pd == null ? "" : pd) + "|" + (sel == null ? "" : sel);
            if (key.equals(lastActKey) && System.currentTimeMillis() - lastActAt < 2_500) {
                return; // the answer to this prompt is on its way
            }
            final long actsBefore = lastActAt;
            if (pd != null && !pd.isJsonNull()) {
                final JsonObject d = pd.getAsJsonObject();
                say(name + " decision " + d);
                final JsonArray choices = new JsonArray();
                choices.add(0);
                final boolean split = "assign".equals(d.get("kind").getAsString());
                http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices, "text", split ? d.get("default").getAsString() : null));
                lastActAt = System.currentTimeMillis();
            } else if (!targets.isEmpty()) {
                say(name + " target selection " + sel);
                act("selectPlayer", RiggedMatch.player(g, false).get("id").getAsInt());
            } else if (RiggedMatch.required(g) != null) {
                act("selectCard", RiggedMatch.required(g));
            } else if (g.get("waiting").getAsBoolean()) {
                // the other player is acting
            } else if (!AgentGameTest.iHavePriority(g) && g.get("prompt").getAsString().startsWith("Waiting for")) {
                // the other player is acting
            } else if ("Auto".equals(buttons.get("ok").getAsString())) {
                act(okEnabled ? "ok" : "cancel", null);
            } else if (attacks && myTurn && "COMBAT_DECLARE_ATTACKERS".equals(phase) && okEnabled && tried.add(turn + "attack")) {
                act("alphaStrike", null);
            } else if (okEnabled && myMain && !settled) {
                // OK here would pass priority and leave the main phase
            } else if (okEnabled && myMain && RiggedMatch.card(me, "hand", "Mountain") != null && tried.add(turn + "land")) {
                lastCardSelect = System.currentTimeMillis();
                act("selectCard", RiggedMatch.card(me, "hand", "Mountain").get("id").getAsInt());
            } else if (okEnabled && myMain && castable(me, turn) != null) {
                final String spell = castable(me, turn);
                tried.add(turn + spell);
                castOnce.add(spell);
                lastCardSelect = System.currentTimeMillis();
                say(name + " casts " + spell + " in turn " + g.get("turn"));
                act("selectCard", RiggedMatch.card(me, "hand", spell).get("id").getAsInt());
            } else if (hold && atPriorityPrompt(g)) {
                // left for the host-side act check
            } else if (okEnabled) {
                act("ok", null);
            } else if (buttons.get("cancelEnabled").getAsBoolean()) {
                act("cancel", null);
            }
            if (lastActAt != actsBefore) {
                lastActKey = key;
            }
        }

        /** A spell in hand this seat can pay for and has not tried this turn, one it has never cast first. */
        private String castable(final JsonObject me, final String turn) {
            String found = null;
            for (int i = 0; i < spells.length; i++) {
                final boolean can = RiggedMatch.untapped(me, "Mountain") >= costs[i] && RiggedMatch.card(me, "hand", spells[i]) != null
                        && !tried.contains(turn + spells[i]);
                if (can && !castOnce.contains(spells[i])) {
                    return spells[i];
                }
                if (can && found == null) {
                    found = spells[i];
                }
            }
            return found;
        }

        JsonObject act(final String action, final Integer id) throws Exception {
            lastActAt = System.currentTimeMillis();
            return http.post("/game/act", BridgeHttp.body("action", action, "id", id));
        }
    }

    private static void say(final String line) {
        System.out.println("[spike] " + line);
    }

    private static String shape(final JsonObject g) {
        if (!g.has("buttons") || !g.has("prompt")) {
            return "(no game)";
        }
        final JsonObject b = g.getAsJsonObject("buttons");
        final String prompt = g.get("prompt").getAsString().replace('\n', '|').replaceAll("[0-9]+", "#");
        return prompt + " [" + RiggedMatch.str(b.get("ok")) + "/" + RiggedMatch.str(b.get("cancel")) + "]";
    }

    private static String summary(final JsonObject g) {
        if (!g.has("buttons")) {
            return g.toString();
        }
        return "promptCount=" + g.get("promptCount") + " waiting=" + g.get("waiting") + " hasPriority=" + g.get("hasPriority")
                + " turn=" + g.get("turn") + " phase=" + g.get("phase") + " active=" + g.get("activePlayer")
                + " buttons=" + g.get("buttons") + " prompt=" + g.get("prompt").toString();
    }

    private String hostState() {
        final Game game = lobby.getHostedMatch().getGame();
        final Player priority = game.getPhaseHandler().getPriorityPlayer();
        return "turn=" + game.getPhaseHandler().getTurn() + " phase=" + game.getPhaseHandler().getPhase()
                + " active=" + game.getPhaseHandler().getPlayerTurn() + " priority=" + priority
                + " stack=" + game.getStack().size();
    }

    /** The view object a bridge seat's client holds, as it received it over the network. */
    private static GameView clientView(final AgentBridge bridge) throws Exception {
        final Field session = AgentBridge.class.getDeclaredField("session");
        session.setAccessible(true);
        final Field gui = AgentSession.class.getDeclaredField("gui");
        gui.setAccessible(true);
        return ((AgentGuiGame) gui.get(session.get(bridge))).getGameView();
    }

    private static PlayerView player(final GameView view, final String name) {
        for (final PlayerView p : view.getPlayers()) {
            if (name.equals(p.getName())) {
                return p;
            }
        }
        throw new IllegalStateException("no player " + name);
    }

    /** Each card in a hand as the view holds it: name, id, and whether the viewer may be shown it. */
    private static String hand(final GameView view, final String owner, final String viewer) {
        final PlayerView who = player(view, viewer);
        final List<String> out = new ArrayList<>();
        final Iterable<CardView> cards = player(view, owner).getHand();
        if (cards == null) {
            return "null";
        }
        for (final CardView c : cards) {
            out.add("'" + c.getName() + "'#" + c.getId() + (c.canBeShownTo(who) ? ":shown" : ":hidden"));
        }
        return out.size() + " cards " + out;
    }

    private void open() throws Exception {
        server = FServerManager.getInstance();
        hostPort = RiggedMatch.freePort();
        host = new MatchHost(hostPort, 2, true);
        lobby = host.open();
    }

    /** Seats both players, optionally installs the tap for slot 0, and starts the game as {@link MatchHost#run} does. */
    private CopilotTap start(final Seat human, final Seat opponent, final boolean tapped) throws Exception {
        JsonObject st = human.http.post("/sit", BridgeHttp.body("port", hostPort, "name", HUMAN, "dck", HUMAN_DECK));
        assertTrue(st.getAsJsonObject("lobby").get("mySlot").getAsInt() == 0, "the Human takes slot 0: " + st);
        st = opponent.http.post("/sit", BridgeHttp.body("port", hostPort, "name", OPPONENT, "dck", OPPONENT_DECK));
        assertTrue(st.getAsJsonObject("lobby").get("mySlot").getAsInt() == 1, "the opponent takes slot 1: " + st);
        AgentGameTest.waitFor("both seats ready with decks", 20_000, () -> lobby.getSlot(0).isReady() && lobby.getSlot(0).getDeck() != null
                && lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);

        CopilotTap tap = null;
        if (tapped) {
            final RemoteClient client = server.findClientByIndex(0);
            assertNotNull(client, "the host has a client for slot 0");
            say("slot 0 gui before the tap: " + client.getGui());
            tap = new CopilotTap(client);
            assertSame(client.getGui(), tap, "the constructor registers the tap on the client");
            assertSame(server.getGui(0), tap, "FServerManager.getGui(0) returns the tap");
        }
        final Runnable begin = lobby.startGame();
        assertNotNull(begin, "the lobby accepted the match setup");
        begin.run();
        AgentGameTest.waitFor("the host sets up the game", 40_000, () -> {
            final HostedMatch match = lobby.getHostedMatch();
            final Game game = match == null ? null : match.getGame();
            if (game == null || game.getPlayers().size() < 2) {
                return false;
            }
            for (final Player p : game.getPlayers()) {
                if (p.getCardsIn(ZoneType.Library).isEmpty()) {
                    return false;
                }
            }
            return true;
        });
        for (final Seat seat : List.of(human, opponent)) {
            AgentGameTest.waitFor("a seat shows the first prompt of a game", 40_000, () -> {
                final JsonObject g = seat.http.get("/game");
                return g.get("inGame").getAsBoolean() && g.has("gameOver") && !g.get("gameOver").getAsBoolean()
                        && !g.get("prompt").getAsString().isEmpty();
            });
        }
        say("lobby slot names: " + lobby.getSlot(0).getName() + ", " + lobby.getSlot(1).getName() + "; game players: " + lobby.getHostedMatch().getGame().getPlayers());
        return tap;
    }

    /** Plays both seats until the Human has cast its spells and then sits on a priority prompt. Returns the Human's game there. */
    private JsonObject playToHeldPrompt(final Seat human, final Seat opponent) throws Exception {
        final long start = System.currentTimeMillis();
        String heldKey = null;
        while (true) {
            assertTrue(System.currentTimeMillis() - start < 200_000, "the game did not reach the held prompt in time");
            final JsonObject hg = human.game();
            assertTrue(!hg.get("gameOver").getAsBoolean(), "the game ended early: " + hg);
            final boolean goals = human.castOnce.size() == human.spells.length;
            if (!human.hold && (goals || System.currentTimeMillis() - start > 75_000)) {
                human.hold = true;
                say("goals " + (goals ? "met" : "not met") + " at turn " + hg.get("turn") + ", cast " + human.castOnce + "; holding the Human at its next priority prompt");
            }
            if (human.hold && human.atPriorityPrompt(hg) && System.currentTimeMillis() - human.lastCardSelect > 3_000) {
                final String key = hg.get("promptCount") + "|" + hg.get("prompt");
                if (key.equals(heldKey)) {
                    return hg;
                }
                heldKey = key;
                Thread.sleep(700);
                continue;
            }
            heldKey = null;
            human.step(hg);
            opponent.step(opponent.game());
            Thread.sleep(150);
        }
    }

    private void end(final Seat human, final Seat opponent) throws Exception {
        human.act("concede", null);
        for (final Seat seat : List.of(human, opponent)) {
            AgentGameTest.waitFor("the game ends on the concession", 30_000, () -> seat.http.get("/game").get("gameOver").getAsBoolean());
        }
        Thread.sleep(1_500);
        human.act("quit", null);
        opponent.act("quit", null);
        AgentGameTest.waitFor("the host ends the match", 30_000, () -> lobby.getHostedMatch() == null);
    }

    @Test(timeOut = 400_000, priority = 1)
    public void baselineWithoutTheTap() throws Exception {
        open();
        final Seat human = new Seat(HUMAN, false, new String[] {"Goblin Piker", "Shock", "Magma Jet"}, new int[] {2, 1, 2});
        final Seat opponent = new Seat(OPPONENT, true, new String[] {"Goblin Guide"}, new int[] {1});
        start(human, opponent, false);
        say("baseline slot 0 gui class: " + server.findClientByIndex(0).getGui().getClass().getName());
        final JsonObject held = playToHeldPrompt(human, opponent);
        say("baseline Human client at the held prompt: " + summary(held));
        BASELINE_PROMPTS.addAll(human.promptShapes);
        end(human, opponent);
    }

    @Test(timeOut = 400_000, priority = 2)
    public void theTapSeesTheHumanSeatAndCanActForIt() throws Exception {
        open();
        final Seat human = new Seat(HUMAN, false, new String[] {"Goblin Piker", "Shock", "Magma Jet"}, new int[] {2, 1, 2});
        final Seat opponent = new Seat(OPPONENT, true, new String[] {"Goblin Guide"}, new int[] {1});
        final CopilotTap tap = start(human, opponent, true);

        // 1. the host uses the tap for slot 0
        final RemoteClient client = server.findClientByIndex(0);
        assertSame(client.getGui(), tap, "the tap is still the GUI of slot 0 after the game started");
        final IGameController controller = lobby.getController(0);
        say("Q1 lobby.getController(0) is " + (controller == null ? "null" : controller.getClass().getName()));
        final PlayerControllerHuman pch = (PlayerControllerHuman) controller;
        final IGuiGame controllerGui = pch.getGui();
        say("Q1 controller.getGui() == tap: " + (controllerGui == tap) + " (" + controllerGui.getClass().getName() + ")");
        assertSame(controllerGui, tap, "the Human's controller on the host talks to the tap");
        say("Q1 slot 1 gui class: " + server.findClientByIndex(1).getGui().getClass().getName());

        final JsonObject held = playToHeldPrompt(human, opponent);
        TAPPED_PROMPTS.addAll(human.promptShapes);
        assertTrue(tap.counts.getOrDefault("showPromptMessage", 0) > 0, "the tap saw prompts: " + tap.counts);
        assertTrue(tap.counts.getOrDefault("updateButtons", 0) > 0, "the tap saw button updates: " + tap.counts);
        say("Q1 tap call counts at the held prompt: " + new TreeMap<>(tap.counts));
        say("Q1 tap game event counts at the held prompt: " + new TreeMap<>(tap.events));
        say("Q1 Human client messages (reveals): " + human.messages);
        say("Q1 Opponent client messages (reveals): " + opponent.messages);

        // 2. hands
        final GameView hostView = tap.getGameView();
        say("Q2 tap.getGameView() == lobby.getGameView(): " + (hostView == lobby.getGameView()));
        say("Q2 host view, Human hand as Human may see it:    " + hand(hostView, HUMAN, HUMAN));
        say("Q2 host view, Opponent hand as Human may see it: " + hand(hostView, OPPONENT, HUMAN));
        say("Q2 host view, pch.getLocalPlayerView(): " + pch.getLocalPlayerView() + ", same object as the view's Human: "
                + (pch.getLocalPlayerView() == player(hostView, HUMAN)));
        final GameView oppView = clientView(opponent.bridge);
        say("Q2 Opponent client view, Human hand:    " + hand(oppView, HUMAN, OPPONENT));
        say("Q2 Opponent client view, Opponent hand: " + hand(oppView, OPPONENT, OPPONENT));
        final GameView humanView = clientView(human.bridge);
        say("Q2 Human client view, Human hand:    " + hand(humanView, HUMAN, HUMAN));
        say("Q2 Human client view, Opponent hand: " + hand(humanView, OPPONENT, HUMAN));
        final JsonObject humanMe = RiggedMatch.me(held);
        say("Q2 Human client GET /game hand: " + humanMe.get("hand"));
        say("Q2 Opponent client GET /game, Human entry keys: " + RiggedMatch.player(opponent.game(), false).keySet());

        // 4. what the client shows against what the tap sent last
        say("Q4 Human client at the held prompt: " + summary(held));
        say("Q4 tap last prompt: " + tap.lastPrompt.replace('\n', '|') + " buttons " + tap.lastButtons);
        say("Q4 tap showPromptMessage count " + tap.counts.get("showPromptMessage") + ", Human client promptCount " + held.get("promptCount"));

        // 3. act for the Human from the host
        final int mark = tap.calls.size();
        say("Q3 before: host " + hostState());
        say("Q3 before: Human client " + summary(held));
        pch.selectButtonOk();
        say("Q3 called selectButtonOk() on the Human's host-side controller from thread " + Thread.currentThread().getName());
        JsonObject after = null;
        for (final long wait : new long[] {300, 700, 2_000}) {
            Thread.sleep(wait);
            after = human.game();
            say("Q3 after +" + wait + "ms: host " + hostState());
            say("Q3 after +" + wait + "ms: Human client " + summary(after));
        }
        say("Q3 Opponent client after: " + summary(opponent.game()));
        synchronized (tap.calls) {
            for (final String line : tap.calls.subList(mark, tap.calls.size())) {
                say("Q3 tap call after the host-side OK: " + line);
            }
        }
        final boolean stale = human.atPriorityPrompt(after) && after.get("promptCount").equals(held.get("promptCount"));
        say("Q3 Human client still shows the prompt the host answered: " + stale);
        // A second OK from the Human's own client, as a click on a prompt it still shows would send.
        final String hostBeforeSecond = hostState();
        final int mark2 = tap.calls.size();
        final JsonObject second = human.http.post("/game/act", BridgeHttp.body("action", "ok", "id", null));
        Thread.sleep(2_000);
        say("Q3 second OK from the Human's client, bridge answered: " + summary(second));
        say("Q3 host before the second OK: " + hostBeforeSecond);
        say("Q3 host after the second OK:  " + hostState());
        say("Q3 Human client after the second OK: " + summary(human.game()));
        synchronized (tap.calls) {
            for (final String line : tap.calls.subList(mark2, tap.calls.size())) {
                say("Q3 tap call after the second OK: " + line);
            }
        }

        // game end
        final int mark3 = tap.calls.size();
        end(human, opponent);
        synchronized (tap.calls) {
            for (final String line : tap.calls.subList(mark3, tap.calls.size())) {
                say("Q1 tap call at game end: " + line);
            }
        }
        say("Q1 tap call counts at the end: " + new TreeMap<>(tap.counts));
        say("Q1 slot 0 gui after the match: " + client.getGui());

        // 4. prompt shapes with and without the tap
        final Set<String> onlyBaseline = new LinkedHashSet<>(BASELINE_PROMPTS);
        onlyBaseline.removeAll(TAPPED_PROMPTS);
        final Set<String> onlyTapped = new LinkedHashSet<>(TAPPED_PROMPTS);
        onlyTapped.removeAll(BASELINE_PROMPTS);
        say("Q4 prompt shapes: baseline " + BASELINE_PROMPTS.size() + ", tapped " + TAPPED_PROMPTS.size());
        say("Q4 prompt shapes only in the baseline run: " + onlyBaseline);
        say("Q4 prompt shapes only in the tapped run: " + onlyTapped);
    }
}
