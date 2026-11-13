package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A target prompt says that a player can be chosen, and conceding with one open does not break the
 * host. The deck is 20 Mountain and 20 Shock. The agent casts a Shock, finds the opponent under
 * {@code selection.players}, targets it and the host's life total drops by two. It casts a second
 * Shock and concedes while that target prompt is open. Forge's target prompt asks again without
 * end once the game is over, and before the bridge cancelled the prompt first the host's game
 * thread died with a StackOverflowError.
 */
public class AgentTargetTest {

    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Target Test",
            "[Main]",
            "20 Mountain",
            "20 Shock");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("target");
        }
    }

    @Test(timeOut = 300_000)
    public void aPlayerIsOfferedAsATargetAndConcedingMidTargetIsSafe() throws Exception {
        final List<String> uncaught = new CopyOnWriteArrayList<>();
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.add(t.getName() + ": " + e));
        try {
            match = new RiggedMatch(DECK);
            final int lifeAtStart = match.hostLife(RiggedMatch.HOST);
            boolean shocked = false;
            final long deadline = System.currentTimeMillis() + 240_000;
            while (true) {
                final JsonObject g = match.next();
                assertTrue(System.currentTimeMillis() < deadline, "not finished in time; last state " + g);
                assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
                if (!shocked && match.hostLife(RiggedMatch.HOST) <= lifeAtStart - 2) {
                    shocked = true;
                }
                final JsonArray players = g.has("selection") ? g.getAsJsonObject("selection").getAsJsonArray("players") : new JsonArray();
                if (players.isEmpty()) {
                    match.basics(g, "Mountain", "Shock", 1);
                } else if (!shocked) {
                    match.http.record("GET /game#targets", g);
                    final JsonObject opponent = RiggedMatch.player(g, false);
                    JsonObject offered = null;
                    for (int i = 0; i < players.size(); i++) {
                        if (players.get(i).getAsJsonObject().get("id").getAsInt() == opponent.get("id").getAsInt()) {
                            offered = players.get(i).getAsJsonObject();
                        }
                    }
                    assertTrue(offered != null, "the opponent is offered as a target: " + g.get("selection"));
                    assertEquals(offered.get("type").getAsString(), "player");
                    assertEquals(offered.get("name").getAsString(), RiggedMatch.HOST);
                    match.act("selectPlayer", offered.get("id").getAsInt());
                } else {
                    break; // the second Shock's target prompt is open
                }
            }
            assertEquals(match.hostLife(RiggedMatch.HOST), lifeAtStart - 2, "the first Shock hit the player it was aimed at");

            match.act("concede", null);
            AgentGameTest.waitFor("the game ends on the concession", 30_000, () -> match.http.get("/game").get("gameOver").getAsBoolean());
            Thread.sleep(3_000); // the overflow followed the end of the game by a moment
            assertEquals(uncaught, List.of(), "nothing on the host died");
            match.finish();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }
}
