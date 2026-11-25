package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

/**
 * An action sent while nothing is asked of the seat is refused and not sent. Both decks are Forests. In the
 * seat's first main phase, before it has played a land, the host tells the seat to wait while its own priority
 * prompt stays up. That is the state of a seat whose prompt the host has just ended, with the host's next
 * prompt on its way: an action sent then would answer that next prompt. Here an OK would pass priority and a
 * click on the Forest would play it. Each is refused, the host's game does not move, and conceding still works.
 */
public class AgentNotAskedTest {

    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Not Asked Test",
            "[Main]",
            "40 Forest");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("not-asked");
        }
    }

    @Test(timeOut = 300_000)
    public void anActionSentWhileTheSeatWaitsIsRefused() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final long deadline = System.currentTimeMillis() + 240_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "the seat did not reach its main phase in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final boolean asked = g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g)
                    && g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean();
            final JsonObject forest = me == null ? null : RiggedMatch.card(me, "hand", "Forest");
            if (asked && RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer"))) && "MAIN1".equals(RiggedMatch.str(g.get("phase")))
                    && forest != null && RiggedMatch.untapped(me, "Forest") == 0) {
                final int count = g.get("promptCount").getAsInt();
                final int turn = g.get("turn").getAsInt();
                final int hand = me.get("handSize").getAsInt();

                match.hostAwaits();
                AgentGameTest.waitFor("the seat is shown as waiting", 15_000, () -> match.http.get("/game").get("waiting").getAsBoolean());

                refused(BridgeHttp.body("action", "ok"));
                refused(BridgeHttp.body("action", "cancel"));
                refused(BridgeHttp.body("action", "endTurn"));
                refused(BridgeHttp.body("action", "alphaStrike"));
                refused(BridgeHttp.body("action", "selectCard", "id", forest.get("id").getAsInt()));
                refused(BridgeHttp.body("action", "selectPlayer", "id", me.get("id").getAsInt()));
                refused(BridgeHttp.body("action", "selectStack", "id", 1));
                refused(BridgeHttp.body("action", "useMana", "color", 16));
                final JsonObject withCount = BridgeHttp.body("action", "ok", "at", count);
                refused(withCount);

                // Long enough for the host to have reacted to any of them.
                Thread.sleep(2_000);
                final JsonObject now = match.http.get("/game");
                assertTrue(now.get("waiting").getAsBoolean(), "the seat still waits: " + now);
                assertEquals(now.get("promptCount").getAsInt(), count, "no new prompt came: " + now);
                assertEquals(now.get("turn").getAsInt(), turn);
                assertEquals(RiggedMatch.str(now.get("phase")), "MAIN1", "the host's game has not moved: " + now);
                assertEquals(RiggedMatch.me(now).get("handSize").getAsInt(), hand, "the Forest was not played: " + now);
                assertNotNull(RiggedMatch.card(RiggedMatch.me(now), "hand", "Forest"));
                assertTrue(match.hostWaitsOnSeat(), "the host still has the seat's prompt up");
                break;
            }
            match.basics(g, "Forest", "no spell", 99);
        }
        // Conceding and leaving are sent while the seat waits.
        match.finish();
    }

    private void refused(final JsonObject body) throws Exception {
        final String answer = match.http.postExpectingError("/game/act", body).toString();
        assertTrue(answer.contains("nothing is asked of the seat") && answer.contains("was not sent"), body + " is refused with a message: " + answer);
    }
}
