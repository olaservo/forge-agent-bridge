package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * {@link MatchHost} with one bridge seat and one seat played by Forge's AI. The seat readies, the AI takes
 * its turns, the seat concedes and the lobby takes another match. With three games per match the next
 * game starts when the one human seat continues.
 */
public class AgentHostAiSeatTest {

    private static final String SEAT = "SeatBot";
    private static final String PRECON_FILE = "res/quest/precons/Azorius Advance.dck";
    private static final String PRECON_NAME = "Azorius Advance";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Host AI Seat Test",
            "[Main]",
            "56 Mountain",
            "4 Goblin Piker");

    private MatchHost host;
    private ServerGameLobby lobby;
    private Thread loop;
    private int hostPort;
    private String gamesPerMatchBefore;
    private String playerNameBefore;
    private final List<AgentBridge> bridges = new ArrayList<>();

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
        gamesPerMatchBefore = FModel.getPreferences().getPref(FPref.UI_MATCHES_PER_GAME);
        playerNameBefore = FModel.getPreferences().getPref(FPref.PLAYER_NAME);
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
            host = null;
        }
        // The model is shared by every test in this JVM.
        FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, gamesPerMatchBefore);
        FModel.getPreferences().setPref(FPref.PLAYER_NAME, playerNameBefore);
    }

    @Test
    public void aiSeatsThatCannotBeHostedAreRefused() {
        assertNull(MatchHost.aiProblem(2, false, 0, 0), "no AI seat");
        assertNull(MatchHost.aiProblem(2, false, 1, 1), "one AI seat with a deck");
        assertNull(MatchHost.aiProblem(4, false, 3, 1), "three AI seats, one deck given");
        assertTrue(MatchHost.aiProblem(2, true, 1, 0).contains("not available with --limited"), "an AI seat in a Limited lobby");
        assertTrue(MatchHost.aiProblem(2, false, 2, 0).contains("one seat is left for a player"), "every seat an AI");
        assertTrue(MatchHost.aiProblem(2, false, 1, 2).contains("--ai-deck was given 2 times"), "more decks than AI seats");
        assertNull(MatchHost.aiProblem(2, true, 0, 0), "a Limited lobby with no AI seat");
    }

    @Test(timeOut = 300_000)
    public void aSeatPlaysForgeAiAndTheLobbyTakesAnotherMatch() throws Exception {
        final File preferences = new File(ForgeConstants.MAIN_PREFS_FILE);
        final long savedAt = preferences.lastModified();
        // With one human in the match and no player name, Forge asks for the name in a dialog.
        FModel.getPreferences().setPref(FPref.PLAYER_NAME, "");

        host(1, List.of());
        final LobbySlot aiSlot = lobby.getSlot(1);
        assertEquals(lobby.getSlot(0).getType(), LobbySlotType.OPEN, "slot 0 is open");
        assertEquals(aiSlot.getType(), LobbySlotType.AI, "the last slot is the AI's");
        assertTrue(aiSlot.isReady(), "an AI slot is ready");
        assertFalse(aiSlot.getName().isEmpty(), "the AI has a name");
        assertNotNull(aiSlot.getDeck(), "the AI has a deck");
        assertNull(GameType.Constructed.getDeckFormat().getDeckConformanceProblem(aiSlot.getDeck()), "the precon picked for the AI is legal");
        final String ai = aiSlot.getName();
        final String aiDeck = aiSlot.getDeck().getName();
        assertEquals(aiSlot.getDeckName(), aiDeck, "the slot names the AI's deck");

        Thread.sleep(4_000); // longer than the host's start delay
        assertNull(lobby.getHostedMatch(), "no match while the open slot is empty");

        final BridgeHttp seat = seat();
        final JsonObject st = seat.post("/sit", BridgeHttp.body("port", hostPort, "name", SEAT, "dck", DECK));
        final JsonObject seen = st.getAsJsonObject("lobby");
        assertEquals(seen.get("mySlot").getAsInt(), 0, "the seat takes slot 0: " + st);
        final JsonObject seenAi = seen.getAsJsonArray("slots").get(1).getAsJsonObject();
        assertEquals(seenAi.get("type").getAsString(), "AI", "the seat sees the AI slot: " + seen);
        assertEquals(seenAi.get("name").getAsString(), ai, "the seat sees the AI's name: " + seen);
        assertEquals(seenAi.get("deckName").getAsString(), aiDeck, "the seat sees the AI's deck name: " + seen);
        final String line = ai + " is Forge's AI and plays the deck " + aiDeck + ".";
        AgentGameTest.waitFor("the host tells the seat of the AI in chat", 20_000, () -> chat(seat).contains(line));

        Game game = inGame(seat, null, 1);
        final List<String> names = new ArrayList<>();
        for (final Player p : game.getPlayers()) {
            names.add(p.getName());
        }
        assertEquals(names, List.of(SEAT, ai), "the game has the seat and the AI");
        final Player aiPlayer = game.getPlayers().get(1);
        assertTrue(aiPlayer.getLobbyPlayer() instanceof LobbyPlayerAi, "Forge's AI plays the second player");
        assertEquals(aiPlayer.getRegisteredPlayer().getDeck().getName(), aiDeck, "the AI plays the slot's deck");

        // The seat does nothing but pass. The AI has played a land or cast a spell once a card of its own has left its hand.
        final long deadline = System.currentTimeMillis() + 120_000;
        JsonObject aiSide = null;
        while (aiSide == null) {
            assertTrue(System.currentTimeMillis() < deadline, "the AI put no card on the battlefield or in its graveyard: " + seat.get("/game"));
            final JsonObject g = seat.get("/game");
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended while the seat passed: " + g);
            assertTrue(g.get("turn").getAsInt() <= 6, "the AI played nothing in its first turns: " + g);
            for (final JsonElement p : g.getAsJsonArray("players")) {
                final JsonObject player = p.getAsJsonObject();
                if (player.get("name").getAsString().equals(ai)
                        && !(player.getAsJsonArray("battlefield").isEmpty() && player.getAsJsonArray("graveyard").isEmpty())) {
                    aiSide = player;
                }
            }
            if (aiSide == null) {
                pass(seat, g);
                Thread.sleep(200);
            }
        }
        assertFalse(game.getPlayers().get(1).getCardsIn(List.of(ZoneType.Battlefield, ZoneType.Graveyard)).isEmpty(),
                "the host's game has the AI's card too: " + aiSide);
        final String log = seat.get("/game/log").getAsJsonArray("log").toString();
        System.out.println("[ai-seat-test] the AI's side as the seat sees it: " + aiSide);
        System.out.println("[ai-seat-test] the seat's game log: " + log);
        assertTrue(log.contains(ai), "the seat's game log names the AI: " + log);

        endMatch(seat, ai);
        assertFalse(lobby.getSlot(0).isReady(), "the host unreadies the seat after a match");
        assertEquals(lobby.getSlot(0).getType(), LobbySlotType.REMOTE, "the seat stays in the lobby");
        assertEquals(lobby.getSlot(1).getType(), LobbySlotType.AI, "the AI keeps its slot");
        Thread.sleep(4_000);
        assertNull(lobby.getHostedMatch(), "no match while the seat is not ready");

        seat.post("/lobby/ready", BridgeHttp.body("ready", true));
        game = inGame(seat, game, 1);
        assertEquals(game.getPlayers().get(1).getName(), ai, "the second match has the same AI");
        endMatch(seat, ai);
        assertEquals(preferences.lastModified(), savedAt, "the preferences file was not written");
    }

    @Test(timeOut = 300_000)
    public void aBestOfThreeAgainstForgeAiContinuesOnTheSeatsWord() throws Exception {
        host(3, List.of(PRECON_FILE));
        final String ai = lobby.getSlot(1).getName();
        assertEquals(lobby.getSlot(1).getDeck().getName(), PRECON_NAME, "the AI plays the deck in the file");

        final BridgeHttp seat = seat();
        seat.post("/sit", BridgeHttp.body("port", hostPort, "name", SEAT, "dck", DECK));
        Game game = inGame(seat, null, 1);
        final HostedMatch hosted = lobby.getHostedMatch();
        final Match match = hosted.getMatch();
        assertEquals(match.getRules().getGamesPerMatch(), 3, "the host plays three games per match");

        concede(seat, ai);
        assertFalse(hosted.isMatchOver(), "the host's match is not over after one game");
        Thread.sleep(2_000);
        final Game first = game;
        assertSame(hosted.getGame(), first, "the host starts no game before the seat continues");

        // Forge waits for the humans in the match. The AI is not asked.
        seat.post("/game/act", BridgeHttp.body("action", "continue", "id", null));
        game = inGame(seat, first, 2);
        final JsonObject wins = seat.get("/game").getAsJsonObject("match").getAsJsonObject("wins");
        assertEquals(wins.get(ai).getAsInt(), 1, "the AI won the first game: " + wins);
        assertEquals(wins.get(SEAT).getAsInt(), 0, "the seat won none: " + wins);

        concede(seat, ai);
        assertTrue(hosted.isMatchOver(), "the AI has won two games and the match");
        assertEquals(match.getOutcomes().size(), 2, "two games were played");
        seat.post("/game/act", BridgeHttp.body("action", "quit", "id", null));
        AgentGameTest.waitFor("the host ends the match", 30_000, () -> lobby.getHostedMatch() == null);
    }

    @Test
    public void anAiDeckIsAFileOrANameAndMustBeLegal() throws Exception {
        final MatchHost unopened = new MatchHost(1, 2, false, 1, 1, List.of());
        assertEquals(unopened.aiDeck(PRECON_FILE).getName(), PRECON_NAME, "a deck file");
        assertEquals(unopened.aiDeck(PRECON_NAME.toLowerCase()).getName(), PRECON_NAME, "a precon by name");
        try {
            unopened.aiDeck("no such deck anywhere");
            throw new AssertionError("an unknown AI deck was accepted");
        } catch (final IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("no deck file at 'no such deck anywhere'"), e.getMessage());
        }
        final Path small = Files.createTempFile("agent-host-ai-seat", ".dck");
        try {
            Files.writeString(small, DECK.replace("56 Mountain", "16 Mountain"));
            unopened.aiDeck(small.toString());
            throw new AssertionError("a 20-card AI deck was accepted");
        } catch (final IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Agent Host AI Seat Test is not legal here"), e.getMessage());
        } finally {
            Files.delete(small);
        }
        try {
            new MatchHost(1, 2, true, 1, 1, List.of());
            throw new AssertionError("an AI seat in a Limited lobby was accepted");
        } catch (final IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("not available with --limited"), e.getMessage());
        }
    }

    private void host(final int games, final List<String> aiDecks) throws Exception {
        hostPort = RiggedMatch.freePort();
        host = new MatchHost(hostPort, 2, false, games, 1, aiDecks);
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
    }

    private BridgeHttp seat() throws Exception {
        final AgentBridge bridge = new AgentBridge();
        bridge.start(0);
        bridges.add(bridge);
        return new BridgeHttp(bridge.port());
    }

    private static String chat(final BridgeHttp seat) throws Exception {
        final StringBuilder text = new StringBuilder();
        for (final JsonElement line : seat.get("/status").getAsJsonArray("chat")) {
            text.append(line.getAsJsonObject().get("text").getAsString()).append('\n');
        }
        return text.toString();
    }

    /** A prompt can change between the read and the answer, and the answer is then refused. */
    private static void tryPost(final BridgeHttp seat, final String path, final JsonObject body) throws Exception {
        try {
            seat.post(path, body);
        } catch (final IllegalStateException refused) {
            System.out.println("[ai-seat-test] " + refused.getMessage());
        }
    }

    /** Answers a decision with its first option, or presses the button the prompt offers. */
    private static void pass(final BridgeHttp seat, final JsonObject g) throws Exception {
        final JsonElement pd = g.get("pendingDecision");
        if (pd != null && !pd.isJsonNull()) {
            final JsonArray choices = new JsonArray();
            choices.add(0);
            tryPost(seat, "/game/decide", BridgeHttp.body("id", pd.getAsJsonObject().get("id").getAsInt(), "choices", choices, "text", null));
            return;
        }
        final JsonObject buttons = g.getAsJsonObject("buttons");
        if (g.get("waiting").getAsBoolean()) {
            return;
        }
        if (buttons.get("okEnabled").getAsBoolean()) {
            tryPost(seat, "/game/act", BridgeHttp.body("action", "ok", "id", null, "at", g.get("promptCount").getAsInt()));
        } else if (buttons.get("cancelEnabled").getAsBoolean()) {
            tryPost(seat, "/game/act", BridgeHttp.body("action", "cancel", "id", null, "at", g.get("promptCount").getAsInt()));
        }
    }

    /**
     * Waits for the game after {@code previous} on the host and on the seat, and answers what the seat is asked
     * before the opening hands. A concession while the host sets the game up breaks the host.
     */
    private Game inGame(final BridgeHttp seat, final Game previous, final int number) throws Exception {
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
        AgentGameTest.waitFor("the seat shows the first prompt of game " + number, 40_000, () -> {
            final JsonObject g = seat.get("/game");
            return g.get("inGame").getAsBoolean() && g.has("gameOver") && !g.get("gameOver").getAsBoolean()
                    && g.getAsJsonObject("match").get("game").getAsInt() == number && !g.get("prompt").getAsString().isEmpty();
        });
        // Who plays first is a decision. The loser of the last game is asked.
        int quiet = 0;
        final long deadline = System.currentTimeMillis() + 30_000;
        while (quiet < 3) {
            assertTrue(System.currentTimeMillis() < deadline, "the seat is still being asked before game " + number);
            quiet++;
            final JsonElement pd = seat.get("/game").get("pendingDecision");
            if (pd != null && !pd.isJsonNull()) {
                final JsonArray choices = new JsonArray();
                choices.add(0);
                seat.post("/game/decide", BridgeHttp.body("id", pd.getAsJsonObject().get("id").getAsInt(), "choices", choices, "text", null));
                quiet = 0;
            }
            Thread.sleep(500);
        }
        return game;
    }

    private void concede(final BridgeHttp seat, final String winner) throws Exception {
        seat.post("/game/act", BridgeHttp.body("action", "concede", "id", null));
        AgentGameTest.waitFor("the game ends on the concession", 30_000, () -> {
            final JsonObject g = seat.get("/game");
            return g.get("gameOver").getAsBoolean() && g.get("finished").getAsBoolean();
        });
        assertEquals(seat.get("/game").get("winner").getAsString(), winner, "the AI wins the conceded game");
    }

    /** The seat concedes and leaves the match, and the host goes back to waiting. */
    private void endMatch(final BridgeHttp seat, final String winner) throws Exception {
        concede(seat, winner);
        seat.post("/game/act", BridgeHttp.body("action", "quit", "id", null));
        AgentGameTest.waitFor("the host ends the match", 30_000, () -> lobby.getHostedMatch() == null);
    }
}
