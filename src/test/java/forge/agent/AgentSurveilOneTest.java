package forge.agent;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.game.zone.ZoneType;

/**
 * Surveil of a single card is not a decision: the host shows a prompt whose two buttons are
 * Library and Graveyard. The deck is 20 Island and 20 Consider (surveil 1, then draw). The agent
 * casts one and presses Graveyard, and the test reads the host's own game: the card that was on
 * top of the library is in the graveyard.
 */
public class AgentSurveilOneTest {

    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Surveil One Test",
            "[Main]",
            "20 Island",
            "20 Consider");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("surveil1");
        }
    }

    @Test(timeOut = 300_000)
    public void theGraveyardButtonPutsTheCardInTheGraveyard() throws Exception {
        match = new RiggedMatch(DECK);
        int looked = -1;
        final long deadline = System.currentTimeMillis() + 240_000;
        while (looked < 0) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no surveil prompt in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject buttons = g.getAsJsonObject("buttons");
            if ("Graveyard".equals(buttons.get("cancel").getAsString()) && buttons.get("cancelEnabled").getAsBoolean()) {
                match.http.record("GET /game#surveil1", g);
                assertTrue("Library".equals(buttons.get("ok").getAsString()), "the other button keeps it on the library: " + buttons);
                assertFalse(g.has("pendingDecision"), "it is a prompt, not a decision: " + g.get("pendingDecision"));
                looked = match.hostIds(RiggedMatch.ME, ZoneType.Library).get(0);
                match.act("cancel", null);
            } else {
                match.basics(g, "Island", "Consider", 1);
            }
        }
        final int card = looked;
        AgentGameTest.waitFor("the card looked at is in the graveyard", 20_000,
                () -> match.hostIds(RiggedMatch.ME, ZoneType.Graveyard).contains(card));
        match.finish();
    }
}
