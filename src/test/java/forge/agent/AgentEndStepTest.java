package forge.agent;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

/**
 * A seat that could act in the opponent's end step is asked there. The seat's deck is Mountains and Shock,
 * and it never casts one, so on the host's turn it holds an instant it can pay for. The bridge answers that
 * prompt itself only when the host lists no playable card, which {@link AgentAutoPassTest} covers.
 */
public class AgentEndStepTest {

    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent End Step Test",
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
            match.close("endstep");
        }
    }

    @Test(timeOut = 300_000)
    public void aSeatHoldingAnInstantIsAskedInTheOpponentsEndStep() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final long deadline = System.currentTimeMillis() + 240_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no ask in the host's end step in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final boolean hostsEndStep = RiggedMatch.HOST.equals(RiggedMatch.str(g.get("activePlayer")))
                    && "END_OF_TURN".equals(RiggedMatch.str(g.get("phase")));
            final boolean asked = g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g);
            if (hostsEndStep && asked && RiggedMatch.untapped(me, "Mountain") > 0 && RiggedMatch.card(me, "hand", "Shock") != null) {
                assertTrue(g.has("actionable") && g.getAsJsonArray("actionable").toString().contains("Shock"),
                        "the host lists the Shock as playable: " + g);
                break;
            }
            match.basics(g, "Mountain", "no spell", 99);
        }
        match.finish();
    }
}
