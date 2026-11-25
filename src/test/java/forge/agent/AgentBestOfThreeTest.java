package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.Match;
import forge.game.event.GameEventGameOutcome;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * A best-of-three match on {@link MatchHost} between two bridge seats. Each game ends on a concession, the
 * seats continue between games, and the match ends when one seat has won two. Each seat's game view gives
 * the score after every game, and it is compared with the host's own count.
 */
public class AgentBestOfThreeTest {

    private static final String FIRST = "FirstBot";
    private static final String SECOND = "SecondBot";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Best Of Three Test",
            "[Main]",
            "20 Mountain",
            "20 Goblin Piker");

    private MatchHost host;
    private ServerGameLobby lobby;
    private Thread loop;
    private int hostPort;
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
            first.saveFixtures("bo3");
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
    public void twoSeatsPlayABestOfThree() throws Exception {
        final File preferences = new File(ForgeConstants.MAIN_PREFS_FILE);
        final long savedAt = preferences.lastModified();

        hostPort = RiggedMatch.freePort();
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

        // Game 1: the first seat concedes.
        Game game = inGame(null, 1, 0, 0);
        final HostedMatch hosted = lobby.getHostedMatch();
        final Match match = hosted.getMatch();
        assertEquals(match.getRules().getGamesPerMatch(), 3, "the host plays three games per match");
        concede(first, SECOND, 1, 0, 1, false);
        first.record("GET /status#gameOver", first.get("/status"));
        assertFalse(hosted.isMatchOver(), "the host's match is not over after one game");

        // The next game waits for both seats.
        first.record("POST /game/act#continue", first.post("/game/act", BridgeHttp.body("action", "continue", "id", null)));
        Thread.sleep(2_000);
        assertSame(hosted.getGame(), game, "the host starts no game while one seat has not continued");
        assertTrue(second.get("/game").get("gameOver").getAsBoolean(), "the second seat still sees the finished game");
        second.post("/game/act", BridgeHttp.body("action", "continue", "id", null));

        // Game 2: the second seat concedes.
        game = inGame(game, 2, 0, 1);
        first.record("GET /game#secondGame", first.get("/game"));
        concede(second, FIRST, 2, 1, 1, false);
        second.post("/game/act", BridgeHttp.body("action", "continue", "id", null));
        first.post("/game/act", BridgeHttp.body("action", "continue", "id", null));

        // Game 3: the first seat concedes, and the second seat has won two.
        game = inGame(game, 3, 1, 1);
        concede(first, SECOND, 3, 1, 2, true);
        first.record("GET /status#matchOver", first.get("/status"));
        first.record("GET /game#matchOver", first.get("/game"));
        assertTrue(hosted.isMatchOver(), "the host's match is over");
        assertEquals(match.getOutcomes().size(), 3, "three games were played");

        final JsonObject refused = first.postExpectingError("/game/act", BridgeHttp.body("action", "continue", "id", null));
        assertTrue(refused.get("error").getAsString().contains("the match is over"), "continue after the match is refused: " + refused);
        Thread.sleep(1_000);
        assertSame(hosted.getGame(), game, "no fourth game");

        first.post("/game/act", BridgeHttp.body("action", "quit", "id", null));
        second.post("/game/act", BridgeHttp.body("action", "quit", "id", null));
        AgentGameTest.waitFor("the host ends the match", 30_000, () -> lobby.getHostedMatch() == null);
        assertEquals(match.getOutcomes().size(), 3, "the match ended with three games played");
        assertEquals(preferences.lastModified(), savedAt, "the preferences file was not written");
    }

    private BridgeHttp seat() throws Exception {
        final AgentBridge bridge = new AgentBridge();
        bridge.start(0);
        bridges.add(bridge);
        return new BridgeHttp(bridge.port());
    }

    /**
     * Waits for the game after {@code previous} on the host and on both seats, answers what the seats are
     * asked before the opening hands, and checks what each seat's view says of the match during the game.
     */
    private Game inGame(final Game previous, final int number, final int firstWins, final int secondWins) throws Exception {
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
        final Game game = lobby.getHostedMatch().getGame();
        for (final BridgeHttp seat : List.of(first, second)) {
            AgentGameTest.waitFor("a seat shows the first prompt of game " + number, 40_000, () -> {
                final JsonObject g = seat.get("/game");
                return g.get("inGame").getAsBoolean() && g.has("gameOver") && !g.get("gameOver").getAsBoolean()
                        && g.getAsJsonObject("match").get("game").getAsInt() == number && !g.get("prompt").getAsString().isEmpty();
            });
        }
        // Who plays first is a decision. The loser of the last game is asked.
        int quiet = 0;
        final long deadline = System.currentTimeMillis() + 30_000;
        while (quiet < 3) {
            assertTrue(System.currentTimeMillis() < deadline, "the seats are still being asked before game " + number);
            quiet++;
            for (final BridgeHttp seat : List.of(first, second)) {
                final JsonElement pd = seat.get("/game").get("pendingDecision");
                if (pd != null && !pd.isJsonNull()) {
                    final JsonArray choices = new JsonArray();
                    choices.add(0);
                    seat.post("/game/decide", BridgeHttp.body("id", pd.getAsJsonObject().get("id").getAsInt(), "choices", choices, "text", null));
                    quiet = 0;
                }
            }
            Thread.sleep(500);
        }
        for (final BridgeHttp seat : List.of(first, second)) {
            assertMatch(seat.get("/game"), number, firstWins, secondWins, false);
        }
        return game;
    }

    /** One seat concedes, and both seats and the host agree on the score. */
    private void concede(final BridgeHttp loser, final String winner, final int number, final int firstWins, final int secondWins,
            final boolean matchOver) throws Exception {
        loser.post("/game/act", BridgeHttp.body("action", "concede", "id", null));
        for (final BridgeHttp seat : List.of(first, second)) {
            AgentGameTest.waitFor("game " + number + " ends on the concession", 30_000, () -> {
                final JsonObject g = seat.get("/game");
                return g.get("gameOver").getAsBoolean() && g.get("finished").getAsBoolean();
            });
            final JsonObject g = seat.get("/game");
            assertEquals(g.get("winner").getAsString(), winner, "the winner of game " + number);
            assertMatch(g, number, firstWins, secondWins, matchOver);
            assertMatch(seat.get("/status").getAsJsonObject("game"), number, firstWins, secondWins, matchOver);
        }
        final Match match = lobby.getHostedMatch().getMatch();
        for (final Player p : lobby.getHostedMatch().getGame().getRegisteredPlayers()) {
            assertEquals(match.getGamesWonBy(p.getLobbyPlayer()), FIRST.equals(p.getName()) ? firstWins : secondWins,
                    "the host's count for " + p.getName() + " after game " + number);
        }
        assertEquals(match.isMatchOver(), matchOver, "the host's match is over after game " + number);
        // The text the host sends with the result, which the bridge reads the score from and the game log shows.
        final Game game = lobby.getHostedMatch().getGame();
        final String summary = new GameEventGameOutcome(game.getOutcome(), match.getOutcomes()).matchSummary();
        assertEquals(Snapshots.wins(summary, game.getView()), Map.of(FIRST, firstWins, SECOND, secondWins), "the score read from \"" + summary + "\"");
        for (final BridgeHttp seat : List.of(first, second)) {
            assertTrue(seat.get("/game/log").getAsJsonArray("log").toString().contains(summary.trim()), "a seat's game log has \"" + summary + "\"");
        }
    }

    private static void assertMatch(final JsonObject g, final int number, final int firstWins, final int secondWins, final boolean over) {
        final JsonObject m = g.getAsJsonObject("match");
        assertEquals(m.get("gamesPerMatch").getAsInt(), 3, "games per match: " + m);
        assertEquals(m.get("game").getAsInt(), number, "the game's number: " + m);
        assertEquals(m.get("over").getAsBoolean(), over, "the match is over: " + m);
        final JsonObject wins = m.getAsJsonObject("wins");
        assertEquals(wins.size(), 2, "a count for each player: " + m);
        assertEquals(Map.of(FIRST, wins.get(FIRST).getAsInt(), SECOND, wins.get(SECOND).getAsInt()), Map.of(FIRST, firstWins, SECOND, secondWins),
                "games won in game " + number + ": " + m);
    }
}
