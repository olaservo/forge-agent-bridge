package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.ServerGameLobby;

/**
 * {@link MatchHost} with a {@link Copilot} on one seat. Two bridge seats play. What {@code GET /copilot}
 * says of the watched seat is compared with that seat's own {@code GET /game}, and searched for the
 * names of the other seat's hidden cards. The two decks share no card name.
 */
public class CopilotHostTest {

    private static final String WATCHED = "Johnny";
    private static final String OPPONENT = "Opponent";
    private static final String WATCHED_DECK = String.join("\n",
            "[metadata]",
            "Name=Copilot Host Watched",
            "[Main]",
            "17 Mountain",
            "9 Goblin Piker",
            "7 Shock",
            "7 Magma Jet");
    private static final String OPPONENT_DECK = String.join("\n",
            "[metadata]",
            "Name=Copilot Host Opponent",
            "[Main]",
            "22 Forest",
            "18 Grizzly Bears");

    private MatchHost host;
    private ServerGameLobby lobby;
    private Thread loop;
    private int hostPort;
    private final List<AgentBridge> bridges = new ArrayList<>();

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        for (final AgentBridge b : bridges) {
            b.stop();
        }
        bridges.clear();
        if (host != null) {
            host.close();
            loop.join(5_000);
        }
    }

    private void host(final String copilotSeat) throws Exception {
        hostPort = RiggedMatch.freePort();
        host = new MatchHost(hostPort, 2, true);
        lobby = host.open();
        if (copilotSeat != null) {
            host.copilot = new Copilot(copilotSeat, 0);
            host.copilot.start(lobby);
        }
        loop = new Thread(() -> {
            try {
                host.run();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MatchHost-Test");
        loop.setDaemon(true);
        loop.start();
    }

    /** One seat as a test drives it. */
    private final class Seat {
        final String name;
        final String land;
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

        Seat(final String name, final String land, final boolean attacks, final String[] spells, final int[] costs) throws Exception {
            this.name = name;
            this.land = land;
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
            } else if (okEnabled && myMain && RiggedMatch.card(me, "hand", land) != null && tried.add(turn + "land")) {
                lastCardSelect = System.currentTimeMillis();
                act("selectCard", RiggedMatch.card(me, "hand", land).get("id").getAsInt());
            } else if (okEnabled && myMain && castable(me, turn) != null) {
                final String spell = castable(me, turn);
                tried.add(turn + spell);
                castOnce.add(spell);
                lastCardSelect = System.currentTimeMillis();
                say(name + " casts " + spell + " in turn " + g.get("turn"));
                act("selectCard", RiggedMatch.card(me, "hand", spell).get("id").getAsInt());
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
                final boolean can = RiggedMatch.untapped(me, land) >= costs[i] && RiggedMatch.card(me, "hand", spells[i]) != null
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
        System.out.println("[copilot-test] " + line);
    }

    private static String shape(final JsonObject g) {
        return g.has("prompt") ? g.get("prompt").getAsString() : "";
    }

    private static String chat(final BridgeHttp seat) throws Exception {
        final StringBuilder text = new StringBuilder();
        for (final JsonElement line : seat.get("/status").getAsJsonArray("chat")) {
            text.append(line.getAsJsonObject().get("text").getAsString()).append('\n');
        }
        return text.toString();
    }

    private static int count(final String text, final String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    /** The cards of a hand as sorted "id:name" entries. */
    private static Set<String> hand(final JsonObject player) {
        final Set<String> out = new TreeSet<>();
        if (player != null && player.has("hand")) {
            for (final JsonElement c : player.getAsJsonArray("hand")) {
                out.add(c.getAsJsonObject().get("id").getAsInt() + ":" + RiggedMatch.str(c.getAsJsonObject().get("name")));
            }
        }
        return out;
    }

    /** Why the copilot's prompt, buttons and hand differ from the seat's own, or null when they are equal. */
    private static String difference(final JsonObject copilot, final JsonObject client) {
        if (!copilot.get("prompt").equals(client.get("prompt"))) {
            return "prompt " + copilot.get("prompt") + " against " + client.get("prompt");
        }
        if (!copilot.get("promptCount").equals(client.get("promptCount"))) {
            return "promptCount " + copilot.get("promptCount") + " against " + client.get("promptCount");
        }
        if (!copilot.get("buttons").equals(client.get("buttons"))) {
            return "buttons " + copilot.get("buttons") + " against " + client.get("buttons");
        }
        final Set<String> mine = hand(RiggedMatch.me(copilot));
        final Set<String> theirs = hand(RiggedMatch.me(client));
        return mine.equals(theirs) ? null : "hand " + mine + " against " + theirs;
    }

    /** Names of the opponent's cards that no player may see now: in its hand or library and in no public zone. */
    private Set<String> hiddenOpponentNames() {
        final HostedMatch match = lobby.getHostedMatch();
        final Game game = match == null ? null : match.getGame();
        final Set<String> hidden = new TreeSet<>();
        if (game == null) {
            return hidden;
        }
        final Set<String> open = new HashSet<>();
        for (final Player p : game.getPlayers()) {
            for (final ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile)) {
                for (final Card c : p.getCardsIn(zone)) {
                    open.add(c.getName());
                }
            }
        }
        for (final Card c : game.getStackZone().getCards()) {
            open.add(c.getName());
        }
        for (final Player p : game.getPlayers()) {
            if (OPPONENT.equals(p.getName())) {
                for (final ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library)) {
                    for (final Card c : p.getCardsIn(zone)) {
                        if (!open.contains(c.getName())) {
                            hidden.add(c.getName());
                        }
                    }
                }
            }
        }
        return hidden;
    }

    @Test(timeOut = 400_000)
    public void theCopilotShowsWhatTheWatchedSeatSeesAndNoHiddenCard() throws Exception {
        host(WATCHED);
        final BridgeHttp copilot = new BridgeHttp(host.copilot.port());
        say("ready line part:" + host.copilot.readyLine());
        JsonObject view = copilot.get("/copilot");
        assertFalse(view.get("watching").getAsBoolean(), "nothing is watched before a game: " + view);

        final Seat watched = new Seat(WATCHED, "Mountain", false, new String[] {"Goblin Piker", "Shock", "Magma Jet"}, new int[] {2, 1, 2});
        final Seat opponent = new Seat(OPPONENT, "Forest", true, new String[] {"Grizzly Bears"}, new int[] {2});
        watched.http.post("/sit", BridgeHttp.body("port", hostPort, "name", WATCHED, "dck", WATCHED_DECK));
        AgentGameTest.waitFor("the host says in chat that the seat has a copilot", 20_000, () -> chat(watched.http).contains(WATCHED + " has a copilot"));
        opponent.http.post("/sit", BridgeHttp.body("port", hostPort, "name", OPPONENT, "dck", OPPONENT_DECK));
        AgentGameTest.waitFor("the watched seat is in a game", 60_000, () -> watched.http.get("/game").get("inGame").getAsBoolean());

        final Set<String> comparedPrompts = new LinkedHashSet<>();
        final Set<Integer> comparedTurns = new TreeSet<>();
        final Set<String> dialogs = new LinkedHashSet<>();
        final Set<String> hiddenChecked = new TreeSet<>();
        int compared = 0;
        int secrecy = 0;
        int bothHidden = 0;
        boolean scry = false;
        final long deadline = System.currentTimeMillis() + 240_000;
        while (compared < 12 || comparedTurns.size() < 3 || watched.castOnce.size() < watched.spells.length || !scry) {
            assertTrue(System.currentTimeMillis() < deadline, "not enough comparisons in time: " + compared + " in turns " + comparedTurns + ", cast " + watched.castOnce + ", scry dialog seen " + scry);

            // no hidden card of the opponent by name; the copilot is read first, so a card played since is not counted
            final String json = copilot.get("/copilot").toString();
            final Set<String> hidden = hiddenOpponentNames();
            for (final String name : hidden) {
                assertFalse(json.contains(name), "the copilot names " + name + ", which is hidden from " + WATCHED + ": " + json);
            }
            if (!hidden.isEmpty()) {
                secrecy++;
                hiddenChecked.addAll(hidden);
                bothHidden += hidden.size() == 2 ? 1 : 0;
            }

            // prompt, buttons and hand against the seat's own client, at a prompt that is not moving
            String difference = "not compared";
            for (int attempt = 0; attempt < 4 && difference != null; attempt++) {
                final JsonObject before = copilot.get("/copilot");
                final JsonObject client = watched.http.get("/game");
                final JsonObject after = copilot.get("/copilot");
                assertFalse(client.get("gameOver").getAsBoolean(), "the game ended early: " + client);
                final JsonElement pd = client.get("pendingDecision");
                if (!after.get("watching").getAsBoolean() || !before.get("revision").equals(after.get("revision"))) {
                    break; // moving
                }
                if (pd != null && !pd.isJsonNull()) {
                    final JsonElement dialog = after.get("dialog");
                    if (!dialog.isJsonNull()) {
                        final JsonObject d = dialog.getAsJsonObject();
                        if (dialogs.add(d.get("kind").getAsString() + " " + d.get("options"))) {
                            say("dialog on the copilot " + d + " while the seat's client shows " + pd);
                        }
                        if ("manipulateCardList".equals(d.get("kind").getAsString())) {
                            scry = true;
                            assertEquals(d.getAsJsonArray("options").size(), pd.getAsJsonObject().getAsJsonArray("options").size(),
                                    "the dialog lists the cards the seat is shown and no more: " + d);
                        }
                    }
                    break;
                }
                if (client.get("waiting").getAsBoolean() || after.get("waiting").getAsBoolean()) {
                    break; // the other player is acting
                }
                difference = difference(after, client);
                if (difference == null) {
                    compared++;
                    comparedTurns.add(client.get("turn").getAsInt());
                    comparedPrompts.add(client.get("prompt").getAsString().replace('\n', '|').replaceAll("[0-9]+", "#") + " " + client.get("buttons"));
                } else if (attempt == 3) {
                    throw new AssertionError("the copilot differs from the seat's client: " + difference);
                } else {
                    Thread.sleep(300);
                }
            }

            watched.step(watched.game());
            opponent.step(opponent.game());
            Thread.sleep(150);
        }
        view = copilot.get("/copilot");
        say("compared equal " + compared + " times in turns " + comparedTurns);
        for (final String p : comparedPrompts) {
            say("compared prompt: " + p);
        }
        say("secrecy checks " + secrecy + ", of which " + bothHidden + " with every opponent card name hidden; names checked " + hiddenChecked);
        say("dialogs seen " + dialogs.size());
        say("last copilot view keys " + view.keySet());
        say("last copilot view lastReveal " + view.get("lastReveal") + " lastAnswer " + view.get("lastAnswer"));
        say("last copilot view opponent entry " + RiggedMatch.player(view, false));
        assertTrue(bothHidden > 0, "at least one check ran while no opponent card was public");
        assertFalse(RiggedMatch.player(view, false).has("hand"), "the opponent's hand is not listed");

        final JsonObject refused = copilot.postExpectingError("/copilot", new JsonObject());
        say("POST /copilot answered " + refused);
        assertTrue(refused.get("error").getAsString().contains("read-only"), "the port takes no POST: " + refused);

        assertEquals(count(chat(opponent.http), "has a copilot"), 1, "the opponent was told once: " + chat(opponent.http));
        assertEquals(count(chat(watched.http), "has a copilot"), 1, "the watched seat was told once");

        watched.act("concede", null);
        AgentGameTest.waitFor("the game ends on the concession", 30_000, () -> opponent.http.get("/game").get("gameOver").getAsBoolean());
        Thread.sleep(1_500);
        watched.act("quit", null);
        opponent.act("quit", null);
        AgentGameTest.waitFor("the host ends the match", 30_000, () -> lobby.getHostedMatch() == null);
        AgentGameTest.waitFor("the copilot stops watching after the match", 10_000, () -> !copilot.get("/copilot").get("watching").getAsBoolean());
        say("after the match " + copilot.get("/copilot"));
    }

    @Test(timeOut = 120_000)
    public void aHostWithoutTheOptionHasNoCopilot() throws Exception {
        host(null);
        assertNull(host.copilot, "no copilot, so no port");
        assertNull(Copilot.fromArgs(new String[] {"--port", "1", "--seats", "2"}), "no copilot without --copilot");
        final Seat seat = new Seat(WATCHED, "Mountain", false, new String[0], new int[0]);
        seat.http.post("/sit", BridgeHttp.body("port", hostPort, "name", WATCHED, "dck", WATCHED_DECK));
        Thread.sleep(2_500);
        assertFalse(chat(seat.http).contains("copilot"), "no chat line about a copilot: " + chat(seat.http));
    }
}
