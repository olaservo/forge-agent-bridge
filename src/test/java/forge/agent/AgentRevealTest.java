package forge.agent;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A reveal is shown to the seat without waiting for an answer. The deck is 20 Mountain and 20
 * Goblin Guide: when a Guide attacks, the defending player reveals the top card of their library.
 * The bridge reports it in {@code messages}, raises no pending decision, and the game moves on to
 * the next turn. Before this was fixed the host stayed parked on the reveal.
 */
public class AgentRevealTest {

    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Reveal Test",
            "[Main]",
            "20 Mountain",
            "20 Goblin Guide");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("reveal");
        }
    }

    @Test(timeOut = 300_000)
    public void aRevealIsShownAndTheGameGoesOn() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final Set<Integer> attacked = new HashSet<>();
        String revealed = null;
        int revealTurn = -1;
        final long deadline = System.currentTimeMillis() + 240_000;
        JsonObject g = match.http.get("/game");
        while (revealed == null || g.get("turn").getAsInt() == revealTurn) {
            assertTrue(System.currentTimeMillis() < deadline,
                    (revealed == null ? "no reveal in time" : "the game did not go on after the reveal") + "; last state " + g);
            g = match.next();
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final int turn = g.get("turn").getAsInt();

            if (revealed == null) {
                for (final JsonElement m : g.getAsJsonArray("messages")) {
                    if (m.getAsString().startsWith("Revealed, ")) {
                        revealed = m.getAsString();
                        revealTurn = turn;
                        match.http.record("GET /game#revealed", g);
                    }
                }
            }

            final JsonElement pd = g.get("pendingDecision");
            final boolean deciding = pd != null && !pd.isJsonNull();
            if (deciding) {
                assertTrue(pd.getAsJsonObject().get("min").getAsInt() >= 0, "a reveal must not arrive as a decision: " + pd);
            }
            final boolean myAttack = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")))
                    && "COMBAT_DECLARE_ATTACKERS".equals(RiggedMatch.str(g.get("phase")));
            if (!deciding && myAttack && g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean() && attacked.add(turn)) {
                match.act("alphaStrike", null);
            } else {
                match.basics(g, "Mountain", "Goblin Guide", 1);
            }
        }
        assertTrue(revealed.contains(RiggedMatch.HOST), "the message says whose card it was: " + revealed);
        System.out.println("[AgentRevealTest] " + revealed);
        match.finish();
    }
}
