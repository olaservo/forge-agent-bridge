package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.testng.annotations.AfterClass;
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
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * Sideboarding between the games of a best of three on {@link MatchHost}. Both seats bring the same deck
 * with one card in the sideboard. After game 1 the first seat names changes the bridge refuses, then swaps
 * the card in with its continue, and the host deals game 2 from the changed deck. After game 2 it continues
 * with the swap back and withdraws it with a second continue, and game 3 is dealt from the deck of game 2.
 * The second seat never changes its deck.
 */
public class AgentSideboardTest {

    private static final String FIRST = "FirstBot";
    private static final String SECOND = "SecondBot";
    private static final String IN = "Raging Goblin";
    private static final String OUT = "Hill Giant";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Sideboard Test",
            "[Main]",
            "20 Mountain",
            "19 Goblin Piker",
            "1 " + OUT,
            "[Sideboard]",
            "1 " + IN);

    private MatchHost host;
    private ServerGameLobby lobby;
    private Thread loop;
    private String gamesPerMatchBefore;
    private BridgeHttp first;
    private BridgeHttp second;
    private final List<AgentBridge> bridges = new ArrayList<>();

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
        gamesPerMatchBefore = FModel.getPreferences().getPref(FPref.UI_MATCHES_PER_GAME);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (first != null) {
            first.saveFixtures("sideboard");
        }
        for (final AgentBridge b : bridges) {
            b.stop();
        }
        if (host != null) {
            host.close();
            loop.join(5_000);
        }
        // The model is shared by every test in this JVM.
        FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, gamesPerMatchBefore);
    }

    @Test(timeOut = 300_000)
    public void aSeatSideboardsBetweenGames() throws Exception {
        final int hostPort = RiggedMatch.freePort();
        host = new MatchHost(hostPort, 2, true, 3);
        lobby = host.open();
        loop = new Thread(() -> {
            try {
                host.run();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MatchHost-Test");
        loop.setDaemon(true);
        loop.start();
        first = seat();
        second = seat();
        first.post("/sit", BridgeHttp.body("port", hostPort, "name", FIRST, "dck", DECK));
        second.post("/sit", BridgeHttp.body("port", hostPort, "name", SECOND, "dck", DECK));

        // Game 1 is played with the decks as they were brought.
        Game game = inGame(null, 1);
        assertEquals(hostDeck(game, FIRST).get(OUT), Integer.valueOf(1), "the first seat's deck in game 1");
        assertFalse(hostDeck(game, FIRST).containsKey(IN), "the sideboard card is not in the deck of game 1");
        assertFalse(first.get("/game/sideboard").get("open").getAsBoolean(), "no sideboarding during a game");
        endGame(first, 1);

        // After it, the seat is shown its decks, and swaps the two cards as it continues.
        JsonObject sideboard = first.get("/game/sideboard");
        first.record("GET /game/sideboard#open", sideboard);
        first.record("GET /status#sideboarding", first.get("/status"));
        first.record("GET /game#sideboarding", first.get("/game"));
        assertTrue(sideboard.get("open").getAsBoolean(), "sideboarding is open after game 1: " + sideboard);
        assertEquals(sideboard.get("minMain").getAsInt(), 40, "a Limited main deck keeps 40 cards: " + sideboard);
        assertEquals(counts(sideboard, "main"), Map.of("Mountain", 20, "Goblin Piker", 19, OUT, 1), "the main deck: " + sideboard);
        assertEquals(counts(sideboard, "sideboard"), Map.of(IN, 1), "the sideboard: " + sideboard);
        assertTrue(first.get("/game").getAsJsonObject("match").get("sideboarding").getAsBoolean(), "the game state says so");
        // The bridge refuses a card the seat does not have, a main deck that is too small, and cards with another action.
        JsonObject refused = first.postExpectingError("/game/act", swap("continue", List.of("Black Lotus"), List.of(OUT)));
        assertTrue(refused.get("error").getAsString().contains("no Black Lotus in your sideboard"), "a card that is not the seat's: " + refused);
        refused = first.postExpectingError("/game/act", swap("continue", List.of(), List.of("Mountain")));
        assertTrue(refused.get("error").getAsString().contains("needs 40"), "a main deck of 39: " + refused);
        refused = first.postExpectingError("/game/act", swap("ok", List.of(IN), List.of(OUT)));
        assertTrue(refused.get("error").getAsString().contains("go with continue"), "a change with another action: " + refused);
        second.post("/game/act", BridgeHttp.body("action", "continue", "id", null));
        Thread.sleep(1_500);
        assertSame(lobby.getHostedMatch().getGame(), game, "a refused change did not continue for the seat");
        first.record("POST /game/act#swap", first.post("/game/act", swap("continue", List.of(IN), List.of(OUT))));

        // Game 2: the host dealt the first seat the changed deck.
        game = inGame(game, 2);
        assertEquals(hostDeck(game, FIRST), Map.of("Mountain", 20, "Goblin Piker", 19, IN, 1), "the first seat's deck in game 2");
        assertEquals(hostCards(game, FIRST, ZoneType.Sideboard), Map.of(OUT, 1), "the first seat's sideboard in game 2");
        assertEquals(hostDeck(game, SECOND), Map.of("Mountain", 20, "Goblin Piker", 19, OUT, 1), "the second seat's deck in game 2");
        sideboard = first.get("/game/sideboard");
        assertEquals(counts(sideboard, "main"), Map.of("Mountain", 20, "Goblin Piker", 19, IN, 1), "the bridge knows the changed deck: " + sideboard);
        assertEquals(counts(sideboard, "sideboard"), Map.of(OUT, 1), "and the changed sideboard: " + sideboard);
        endGame(second, 2);

        // After game 2 the seat continues with the cards swapped back, and then changes its mind. The host has
        // its continue and gets no second one: only the choice the bridge holds changes.
        first.post("/game/act", swap("continue", List.of(OUT), List.of(IN)));
        refused = first.postExpectingError("/game/act", swap("continue", List.of(OUT), List.of(IN)));
        assertTrue(refused.get("error").getAsString().contains("already answered the end of this game with continue"), "the same continue again: " + refused);
        first.post("/game/act", BridgeHttp.body("action", "continue", "id", null));
        refused = first.postExpectingError("/game/act", BridgeHttp.body("action", "continue", "id", null));
        assertTrue(refused.get("error").getAsString().contains("already answered the end of this game with continue"), "a continue that changes nothing: " + refused);
        Thread.sleep(1_000);
        assertSame(lobby.getHostedMatch().getGame(), game, "the next game waits for the second seat");
        second.post("/game/act", BridgeHttp.body("action", "continue", "id", null));

        // Game 3 is dealt from the decks of game 2: the swap back was withdrawn.
        game = inGame(game, 3);
        assertEquals(hostDeck(game, FIRST), Map.of("Mountain", 20, "Goblin Piker", 19, IN, 1), "the first seat's deck in game 3");
        assertEquals(hostDeck(game, SECOND), Map.of("Mountain", 20, "Goblin Piker", 19, OUT, 1), "the second seat's deck in game 3");
        endGame(first, 3);
        assertFalse(first.get("/game/sideboard").get("open").getAsBoolean(), "no sideboarding once the match is over");
        assertFalse(first.get("/game").getAsJsonObject("match").get("sideboarding").getAsBoolean(), "the game state says so");
        first.post("/game/act", BridgeHttp.body("action", "quit", "id", null));
        second.post("/game/act", BridgeHttp.body("action", "quit", "id", null));
        AgentGameTest.waitFor("the host ends the match", 30_000, () -> lobby.getHostedMatch() == null);
    }

    private BridgeHttp seat() throws Exception {
        final AgentBridge bridge = new AgentBridge();
        bridge.start(0);
        bridges.add(bridge);
        return new BridgeHttp(bridge.port());
    }

    private static JsonObject swap(final String action, final List<String> in, final List<String> out) {
        final JsonArray ins = new JsonArray();
        in.forEach(ins::add);
        final JsonArray outs = new JsonArray();
        out.forEach(outs::add);
        return BridgeHttp.body("action", action, "id", null, "in", ins, "out", outs);
    }

    /** Card names with counts from one list of {@code GET /game/sideboard}. */
    private static Map<String, Integer> counts(final JsonObject sideboard, final String list) {
        final Map<String, Integer> counts = new TreeMap<>();
        for (final JsonElement e : sideboard.getAsJsonArray(list)) {
            counts.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject().get("count").getAsInt());
        }
        return counts;
    }

    /** The cards the host dealt a player from: its library and hand, by name. */
    private static Map<String, Integer> hostDeck(final Game game, final String player) {
        final Map<String, Integer> counts = hostCards(game, player, ZoneType.Library);
        hostCards(game, player, ZoneType.Hand).forEach((name, n) -> counts.merge(name, n, Integer::sum));
        return counts;
    }

    private static Map<String, Integer> hostCards(final Game game, final String player, final ZoneType zone) {
        final Map<String, Integer> counts = new TreeMap<>();
        for (final Player p : game.getRegisteredPlayers()) {
            if (player.equals(p.getName())) {
                for (final Card c : p.getCardsIn(zone)) {
                    counts.merge(c.getName(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** Waits for the game after {@code previous} on the host and for both seats to be in it. */
    private Game inGame(final Game previous, final int number) throws Exception {
        AgentGameTest.waitFor("the host sets up game " + number, 40_000, () -> {
            final HostedMatch hosted = lobby.getHostedMatch();
            final Game game = hosted == null ? null : hosted.getGame();
            if (game == null || game == previous || game.getPlayers().size() < 2) {
                return false;
            }
            for (final Player p : game.getPlayers()) {
                if (p.getCardsIn(ZoneType.Library).isEmpty()) {
                    return false;
                }
            }
            return true;
        });
        for (final BridgeHttp seat : List.of(first, second)) {
            AgentGameTest.waitFor("a seat shows the first prompt of game " + number, 40_000, () -> {
                final JsonObject g = seat.get("/game");
                return g.get("inGame").getAsBoolean() && g.has("gameOver") && !g.get("gameOver").getAsBoolean()
                        && g.getAsJsonObject("match").get("game").getAsInt() == number && !g.get("prompt").getAsString().isEmpty();
            });
        }
        return lobby.getHostedMatch().getGame();
    }

    private void endGame(final BridgeHttp loser, final int number) throws Exception {
        loser.post("/game/act", BridgeHttp.body("action", "concede", "id", null));
        for (final BridgeHttp seat : List.of(first, second)) {
            AgentGameTest.waitFor("game " + number + " ends on the concession", 30_000, () -> {
                final JsonObject g = seat.get("/game");
                return g.get("gameOver").getAsBoolean() && g.get("finished").getAsBoolean();
            });
        }
    }
}
