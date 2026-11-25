package forge.agent;

import static org.testng.Assert.assertEquals;
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
 * What the seat is told while combat is declared. The agent's deck is Forests and Garruk's Companion; the
 * host AI plays Goblin Brigand, a 2/2 that attacks each combat if able. The seat is asked at every stop.
 *
 * The attack prompt: Forge shows it twice when it starts, and the count goes up once. The host showing it
 * again keeps the count, and an action with that count as {@code at} is sent. The prompt the host shows
 * after that click counts, and so does a priority prompt shown again.
 *
 * The first attack on the seat is not blocked: {@code blocksFinal} is false at the block prompt and true
 * after OK, with {@code blocked} false. In a later attack a blocker is planned with {@code blocksFinal}
 * false, and after OK the attacker is {@code blocked} with {@code blocksFinal} true.
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
    public void theSeatIsToldWhatItsClicksAndTheBlocksCameTo() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host attackers", "18 Mountain", "22 Goblin Brigand"));
        match.http.post("/game/yield", BridgeHttp.body("autoPass", "off"));
        final Set<String> tried = new HashSet<>();
        boolean attackPrompt = false;
        boolean priorityCounted = false;
        boolean unblocked = false;
        boolean blocked = false;
        int lastMain = -1;
        int lastMainTurn = -1;
        final long deadline = System.currentTimeMillis() + 360_000;
        while (!(attackPrompt && priorityCounted && unblocked && blocked)) {
            final JsonObject g = match.next();
            final String seen = "attack prompt: " + attackPrompt + ", priority prompt: " + priorityCounted + ", unblocked: " + unblocked
                    + ", blocked: " + blocked;
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
            final boolean blockPrompt = okEnabled && !myTurn && !priority && "COMBAT_DECLARE_BLOCKERS".equals(phase) && g.has("combat")
                    && !g.getAsJsonArray("combat").isEmpty() && g.get("pendingDecision") == null;

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
            } else if (blockPrompt && !unblocked && tried.add(turn + "block")) {
                for (final JsonElement c : g.getAsJsonArray("combat")) {
                    assertFalse(c.getAsJsonObject().get("blocksFinal").getAsBoolean(), "no block is final at the block prompt: " + c);
                    assertFalse(c.getAsJsonObject().get("blocked").getAsBoolean(), "no attacker is blocked at the block prompt: " + c);
                }
                match.act("ok", null);
                final JsonObject after = afterBlocks();
                match.http.record("GET /game#unblocked", after);
                for (final JsonElement c : after.getAsJsonArray("combat")) {
                    assertTrue(c.getAsJsonObject().get("blocksFinal").getAsBoolean(), "the blocks are final after OK: " + c);
                    assertFalse(c.getAsJsonObject().get("blocked").getAsBoolean(), "the attacker is unblocked: " + c);
                    assertTrue(c.getAsJsonObject().getAsJsonArray("blockers").isEmpty(), "no blocker is listed: " + c);
                }
                unblocked = true;
            } else if (blockPrompt && unblocked && !blocked && RiggedMatch.card(me, "battlefield", CREATURE) != null && tried.add(turn + "block")) {
                final int attacker = g.getAsJsonArray("combat").get(0).getAsJsonObject().get("attackerId").getAsInt();
                final int blocker = RiggedMatch.card(me, "battlefield", CREATURE).get("id").getAsInt();
                match.act("selectCard", attacker);
                match.act("selectCard", blocker);
                final JsonObject mid = match.http.get("/game");
                match.http.record("GET /game#plannedBlock", mid);
                final JsonObject planned = entry(mid, attacker);
                assertTrue(planned.getAsJsonArray("blockers").toString().contains("#" + blocker + "\""), "the planned blocker is listed: " + planned);
                assertFalse(planned.get("blocked").getAsBoolean(), "the block is not final before OK: " + planned);
                assertFalse(planned.get("blocksFinal").getAsBoolean(), "the block is not final before OK: " + planned);
                match.act("ok", null);
                final JsonObject after = afterBlocks();
                match.http.record("GET /game#blocked", after);
                final JsonObject done = entry(after, attacker);
                assertTrue(done.get("blocked").getAsBoolean(), "the attacker is blocked after OK: " + done);
                assertTrue(done.get("blocksFinal").getAsBoolean(), "the block is final after OK: " + done);
                for (final JsonElement c : after.getAsJsonArray("combat")) {
                    assertTrue(c.getAsJsonObject().get("blocksFinal").getAsBoolean(), "every attacker's blocks are final after OK: " + c);
                }
                blocked = true;
            } else {
                match.basics(g, LAND, CREATURE, 2);
            }
        }
        match.finish();
    }

    private long revision() throws Exception {
        return match.http.get("/status").get("revision").getAsLong();
    }

    /** The game once the seat is asked for priority in the declare blockers step, which is after the blocks are final. */
    private JsonObject afterBlocks() throws Exception {
        AgentGameTest.waitFor("the seat is asked for priority after blockers are declared", 15_000, () -> {
            final JsonObject g = match.http.get("/game");
            return g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && g.has("combat")
                    && "COMBAT_DECLARE_BLOCKERS".equals(RiggedMatch.str(g.get("phase")));
        });
        return match.http.get("/game");
    }

    private static JsonObject entry(final JsonObject g, final int attacker) {
        for (final JsonElement c : g.getAsJsonArray("combat")) {
            if (c.getAsJsonObject().get("attackerId").getAsInt() == attacker) {
                return c.getAsJsonObject();
            }
        }
        throw new AssertionError("attacker " + attacker + " is not in combat: " + g.get("combat"));
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
