package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What the seat is told while combat is declared. The agent's deck is Forests and Garruk's Companion; the
 * host AI plays Goblin Brigand, a 2/2 that attacks each combat if able.
 *
 * The attack prompt: Forge shows it twice when it starts, and the count goes up once. The host showing it
 * again keeps the count, and an action with that count as {@code at} is sent. The prompt the host shows
 * after that click counts, and so does a priority prompt shown again.
 */
public class AgentCombatSignalsTest {

    private static final String CREATURE = "Garruk's Companion";
    private static final String LAND = "Forest";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Combat Signals Test",
            "[Main]",
            "18 " + LAND,
            "22 " + CREATURE);

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("combatSignals");
        }
    }

    @Test(timeOut = 420_000)
    public void theAttackPromptShownAgainKeepsItsCount() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host attackers", "18 Mountain", "22 Goblin Brigand"));
        boolean attackPrompt = false;
        boolean priorityCounted = false;
        int lastMain = -1;
        int lastMainTurn = -1;
        final long deadline = System.currentTimeMillis() + 360_000;
        while (!(attackPrompt && priorityCounted)) {
            final JsonObject g = match.next();
            final String seen = "attack prompt: " + attackPrompt + ", priority prompt: " + priorityCounted;
            assertTrue(System.currentTimeMillis() < deadline, seen + "; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early; " + seen);
            final boolean okEnabled = g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean();
            final boolean myTurn = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            final boolean priority = g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean();
            final String phase = RiggedMatch.str(g.get("phase"));
            final String prompt = g.get("prompt").getAsString();
            final int count = g.get("promptCount").getAsInt();
            final int turn = g.has("turn") ? g.get("turn").getAsInt() : -1;
            final JsonObject me = RiggedMatch.me(g);

            if (okEnabled && myTurn && priority && phase.startsWith("MAIN1")) {
                lastMain = count;
                lastMainTurn = turn;
            }
            if (!attackPrompt && okEnabled && myTurn && "COMBAT_DECLARE_ATTACKERS".equals(phase) && prompt.startsWith("Select creatures to attack")
                    && attacker(me) != null) {
                // Forge's InputAttack sent this prompt twice. Nothing else was sent since the OK in the main phase.
                assertEquals(lastMainTurn, turn, "the main phase prompt of this turn was seen before the attack prompt: " + g);
                assertEquals(count, lastMain + 1, "the attack prompt counts once: " + g);
                final long revision = revision();
                match.hostPrompts(prompt);
                AgentGameTest.waitFor("the bridge has the prompt the host showed again", 5_000, () -> revision() > revision);
                assertEquals(match.http.get("/game").get("promptCount").getAsInt(), count, "the same prompt shown again keeps its count");

                final JsonObject hit = match.http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", attacker(me), "at", count));
                assertFalse(hit.has("stale"), "an action for the prompt that is up is sent: " + hit);
                assertTrue(hit.get("promptCount").getAsInt() > count, "the prompt the host shows after a click counts: " + hit);
                match.act("ok", null);
                attackPrompt = true;
            } else if (!priorityCounted && okEnabled && myTurn && priority && phase.startsWith("MAIN2")) {
                final long revision = revision();
                match.hostPrompts(prompt);
                AgentGameTest.waitFor("the bridge has the prompt the host showed again", 5_000, () -> revision() > revision);
                assertEquals(match.http.get("/game").get("promptCount").getAsInt(), count + 1, "a priority prompt shown again counts");
                priorityCounted = true;
            } else {
                match.basics(g, LAND, CREATURE, 2);
            }
        }
        match.finish();
    }

    private long revision() throws Exception {
        return match.http.get("/status").get("revision").getAsLong();
    }

    /** A creature of the seat's that can attack, or null. */
    private static Integer attacker(final JsonObject me) {
        if (me == null) {
            return null;
        }
        for (final JsonElement c : me.getAsJsonArray("battlefield")) {
            final JsonObject card = c.getAsJsonObject();
            if (CREATURE.equals(card.get("name").getAsString()) && !card.get("tapped").getAsBoolean() && !card.get("sick").getAsBoolean()) {
                return card.get("id").getAsInt();
            }
        }
        return null;
    }
}
