package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

/**
 * Auto-pass on the opponent's turns. Both decks are 40 Forest, so the seat never has anything to do
 * on the host's turn. In the default mode the host passes for it there and no priority prompt
 * arrives before the end step, where the bridge switches auto-pass off again for the seat's own
 * turn. With the mode set to off the prompts come back. The seat's own main phase always asks.
 */
public class AgentAutoPassTest {

    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Auto-pass Test",
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
            match.close("autopass");
        }
    }

    @Test(timeOut = 300_000)
    public void theHostPassesForTheSeatOnItsOwnTurnsUnlessTheModeIsOff() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final List<String> passing = hostTurn("opponent-turns");
        assertTrue(passing.isEmpty(), "no priority prompt on the host's turn while it passes for the seat: " + passing);

        final JsonObject off = match.http.post("/game/yield", BridgeHttp.body("autoPass", "off"));
        assertEquals(off.get("autoPass").getAsString(), "off");
        assertFalse(off.get("autoPassNow").getAsBoolean(), "the host is told at once: " + off);
        assertFalse(hostTurn("off").isEmpty(), "with auto-pass off the seat is asked on the host's turn");
        match.finish();
    }

    /**
     * Plays to the end of a host turn that follows one of the seat's own, and returns the priority prompts
     * the seat got on it before the end step, as "phase prompt".
     */
    private List<String> hostTurn(final String mode) throws Exception {
        final List<String> asked = new ArrayList<>();
        boolean myTurnSeen = false;
        int hostTurn = -1;
        int lastPrompt = -1;
        final long deadline = System.currentTimeMillis() + 120_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no full host turn in time with auto-pass " + mode + "; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            assertEquals(g.get("autoPass").getAsString(), mode);
            final boolean mine = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            final String phase = RiggedMatch.str(g.get("phase"));
            final int turn = g.get("turn").getAsInt();
            final boolean asks = g.get("priorityPrompt").getAsBoolean() && AgentGameTest.iHavePriority(g)
                    && g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean();
            if (mine && hostTurn >= 0) {
                return asked; // the host's turn is over
            }
            if (mine) {
                // The seat's last prompt stays up while the host passes for it. Only a later one is new.
                lastPrompt = g.get("promptCount").getAsInt();
                if (asks && phase.startsWith("MAIN")) {
                    assertFalse(g.get("autoPassNow").getAsBoolean(), "auto-pass is off on the seat's own main phase: " + g);
                    myTurnSeen = true;
                }
            } else if (myTurnSeen && !phase.isEmpty()) {
                hostTurn = turn;
                final boolean ending = "END_OF_TURN".equals(phase) || "CLEANUP".equals(phase);
                if (!ending && "opponent-turns".equals(mode)) {
                    assertTrue(g.get("autoPassNow").getAsBoolean(), "the host is passing for the seat in " + phase + ": " + g);
                }
                if (asks && !ending && g.get("promptCount").getAsInt() > lastPrompt) {
                    lastPrompt = g.get("promptCount").getAsInt();
                    asked.add("turn " + turn + " " + phase + ", prompt " + g.get("promptCount") + " after " + lastPrompt + ": " + g.get("prompt").getAsString());
                }
            }
            match.basics(g, "Forest", "no spell", 99);
        }
    }
}
