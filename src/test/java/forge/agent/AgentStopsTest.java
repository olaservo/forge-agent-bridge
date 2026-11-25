package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The seat sets where it is asked on an empty stack. The seat's deck is Mountains and Shock, and it never
 * casts one, so it holds an instant it can pay for and the host asks it wherever it has a stop. The host's
 * deck is Forests, so nothing goes on the stack. Forge has a declare attackers step with no attacker.
 */
public class AgentStopsTest {

    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Stops Test",
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
            match.close("stops");
        }
    }

    private static JsonArray phases(final String... names) {
        final JsonArray list = new JsonArray();
        for (final String name : names) {
            list.add(name);
        }
        return list;
    }

    private static boolean asked(final JsonObject g) {
        return g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g);
    }

    @Test(timeOut = 420_000)
    public void theSeatSetsWhereItIsAsked() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final BridgeHttp http = match.http;

        final JsonObject start = http.get("/game/stops");
        assertEquals(RiggedMatch.str(start.get("stops")), "default");
        assertEquals(start.get("own"), phases("MAIN1", "COMBAT_DECLARE_BLOCKERS", "MAIN2"));
        assertEquals(start.get("opponent"), phases("COMBAT_BEGIN", "COMBAT_DECLARE_ATTACKERS", "COMBAT_DECLARE_BLOCKERS", "END_OF_TURN"));

        // A seat that is not asked in its first main phase cannot play a land.
        assertTrue(http.postExpectingError("/game/stops", BridgeHttp.body("own", phases("MAIN2"))).toString().contains("MAIN1"));
        assertTrue(http.postExpectingError("/game/stops", BridgeHttp.body("opponent", phases("UNTAP"))).toString().contains("UNTAP"));
        assertTrue(http.postExpectingError("/game/stops", BridgeHttp.body("opponent", phases("COMBAT"))).toString().contains("unknown phase"));
        assertEquals(RiggedMatch.str(http.get("/game/stops").get("stops")), "default");

        // The seed switches off the auto-yields and trigger answers of this machine's Forge profile for the seat.
        AgentGameTest.waitFor("the host holds the seat's auto-yields as switched off", 20_000, match::hostAsksSeatAboutAutoYields);

        int removedInTurn = -1;
        int upkeepSetInTurn = -1;
        boolean askedAtAttackers = false;
        final long deadline = System.currentTimeMillis() + 360_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "ran out of time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final int turn = g.get("turn").getAsInt();
            final String active = RiggedMatch.str(g.get("activePlayer"));
            final String phase = RiggedMatch.str(g.get("phase"));
            final boolean hostsTurn = RiggedMatch.HOST.equals(active);

            if (removedInTurn < 0) {
                if (asked(g) && hostsTurn && "END_OF_TURN".equals(phase)) {
                    // Asked at the host's end step by default. With the stop removed the host passes this prompt.
                    final JsonObject set = http.post("/game/stops", BridgeHttp.body("opponent", phases("COMBAT_DECLARE_ATTACKERS")));
                    assertEquals(RiggedMatch.str(set.get("stops")), "custom");
                    assertEquals(set.get("opponent"), phases("COMBAT_DECLARE_ATTACKERS"));
                    assertEquals(set.get("own"), phases("MAIN1", "COMBAT_DECLARE_BLOCKERS", "MAIN2"));
                    removedInTurn = turn;
                    final JsonObject after = http.get("/game");
                    assertFalse(asked(after) && "END_OF_TURN".equals(RiggedMatch.str(after.get("phase"))) && after.get("turn").getAsInt() == turn,
                            "still asked at the end step whose stop was removed: " + after);
                    assertEquals(RiggedMatch.str(after.get("stops")), "custom");
                    continue;
                }
            } else if (upkeepSetInTurn < 0) {
                // One stop is left on the host's turn.
                assertFalse(asked(g) && hostsTurn && !"COMBAT_DECLARE_ATTACKERS".equals(phase),
                        "asked on the host's turn in " + phase + " with no stop there: " + g);
                askedAtAttackers |= asked(g) && hostsTurn;
                if (!hostsTurn && turn >= removedInTurn + 3 && asked(g)) {
                    assertTrue(askedAtAttackers, "not asked at the stop that was kept on the host's turn");
                    final JsonObject set = http.post("/game/stops",
                            BridgeHttp.body("own", phases("UPKEEP", "MAIN1", "COMBAT_DECLARE_BLOCKERS", "MAIN2")));
                    assertEquals(set.get("own"), phases("UPKEEP", "MAIN1", "COMBAT_DECLARE_BLOCKERS", "MAIN2"));
                    assertEquals(set.get("opponent"), phases("COMBAT_DECLARE_ATTACKERS"));
                    upkeepSetInTurn = turn;
                }
            } else {
                assertFalse(asked(g) && hostsTurn && !"COMBAT_DECLARE_ATTACKERS".equals(phase),
                        "asked on the host's turn in " + phase + " with no stop there: " + g);
                if (asked(g) && !hostsTurn && "UPKEEP".equals(phase) && turn > upkeepSetInTurn) {
                    break; // asked in its own upkeep, which is no stop by default
                }
                assertTrue(turn <= upkeepSetInTurn + 4, "not asked in its own upkeep after adding the stop: " + g);
            }
            match.basics(g, "Mountain", "no spell", 99);
        }
        match.finish();
    }
}
