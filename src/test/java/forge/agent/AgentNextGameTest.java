package forge.agent;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.deck.Deck;
import forge.gamemodes.match.LobbySlot;

/**
 * A second match in the same session starts clean. The agent's deck is 20 Mountain and 20 Forked
 * Bolt, a spell with up to two targets. The agent picks the opponent as the first target, which
 * leaves a second target prompt open with one target chosen, and concedes. A new match is started
 * in the same lobby, and its first state has no {@code selection}.
 *
 * Without the bridge clearing its selection when a game opens, and without it cancelling the
 * prompt before conceding, the second game reported the first game's selection, with card ids the
 * new game does not have. With the cancel in place the first game no longer ends that way, so this
 * test passes on either change alone.
 */
public class AgentNextGameTest {

    private static final String SPELL = "Forked Bolt";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Next Game Test",
            "[Main]",
            "20 Mountain",
            "20 " + SPELL);

    private RiggedMatch match;

    private static Deck hostDeck() {
        return RiggedMatch.deck("Host creatures", "18 Forest", "11 Llanowar Elves", "11 Grizzly Bears");
    }

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("nextgame");
        }
    }

    @Test(timeOut = 300_000)
    public void theNextMatchStartsWithoutTheOldSelection() throws Exception {
        match = new RiggedMatch(DECK, hostDeck());
        final long deadline = System.currentTimeMillis() + 200_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no second target prompt in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            if (!g.has("selection")) {
                match.basics(g, "Mountain", SPELL, 1);
            } else if (g.getAsJsonObject("selection").getAsJsonArray("chosen").isEmpty()) {
                match.act("selectPlayer", RiggedMatch.player(g, false).get("id").getAsInt());
            } else {
                match.http.record("GET /game#chosen", g);
                break; // one target chosen, the prompt for the second is open
            }
        }
        match.act("concede", null);
        AgentGameTest.waitFor("the first game ends", 30_000, () -> match.http.get("/game").get("gameOver").getAsBoolean());
        match.act("quit", null);
        AgentGameTest.waitFor("the host ends the first match", 30_000, () -> !match.lobby.isMatchActive());

        final LobbySlot hostSlot = match.lobby.getSlot(0);
        hostSlot.setDeck(hostDeck());
        hostSlot.setIsReady(true);
        match.http.post("/lobby/deck", BridgeHttp.body("dck", DECK));
        match.http.post("/lobby/ready", BridgeHttp.body("ready", true));
        AgentGameTest.waitFor("the agent is ready again", 20_000,
                () -> match.lobby.getSlot(1).isReady() && match.lobby.getSlot(1).getDeck() != null);
        final Runnable start = match.lobby.startGame();
        assertNotNull(start, "lobby accepted the second match");
        start.run();
        AgentGameTest.waitFor("the second game shows its first prompt", 30_000, () -> {
            final JsonObject g = match.http.get("/game");
            return g.get("inGame").getAsBoolean() && g.has("gameOver") && !g.get("gameOver").getAsBoolean()
                    && !g.get("prompt").getAsString().isEmpty();
        });
        Thread.sleep(1_500); // a late selection from the host would have arrived by now
        final JsonObject second = match.http.get("/game");
        assertFalse(second.has("selection"), "the second game starts with no selection: " + second.get("selection"));
        match.finish();
    }
}
