package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.zone.ZoneType;

/**
 * The yield until end of turn ends when the opponent acts, outside the seat's main phase too. In both games
 * the seat holds an instant and a land to pay for it, so the host asks it once the yield has ended.
 *
 * In its end step: the seat sends {@code endTurn} in its main phase. The host has Luminarch Ascension, put on
 * its battlefield by the test, whose trigger goes on the stack at the beginning of each opponent's end step in
 * a turn the host lost no life. The seat is asked in its own end step with that trigger on the stack. The host
 * AI was not made to cast a spell there: with Think Twice in hand it cast none in eight end steps.
 *
 * By an attack: on the host's turn the seat is asked at the beginning of combat and sends {@code endTurn}.
 * The host AI attacks with a Grizzly Bears the test put on its battlefield a turn earlier. The seat is asked
 * after attackers are declared.
 */
public class AgentEndTurnInterruptTest {

    private static final String ASCENSION = "Luminarch Ascension";
    private static final String BEARS = "Grizzly Bears";

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("end-turn-interrupt");
            match = null;
        }
    }

    @Test(timeOut = 420_000)
    public void anOpponentsTriggerInTheSeatsEndStepEndsTheYield() throws Exception {
        match = new RiggedMatch(deck("Forest", "Fog"), RiggedMatch.deck("Host lands", "38 Plains", "2 " + ASCENSION));
        int endedTurn = -1;
        final long deadline = System.currentTimeMillis() + 360_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "the seat was not asked in its end step in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final int turn = g.get("turn").isJsonNull() ? -1 : g.get("turn").getAsInt();
            final boolean mine = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            final String phase = RiggedMatch.str(g.get("phase"));
            if (asked(g) && turn == endedTurn) {
                // The only ask left in a turn the seat has ended is the one that ends the yield.
                assertTrue(mine && "END_OF_TURN".equals(phase), "the seat is asked in its own end step: " + g);
                assertEquals(stack(g).size(), 1, "with the host's trigger on the stack: " + g);
                assertEquals(RiggedMatch.str(stack(g).get(0).getAsJsonObject().get("by")), RiggedMatch.HOST);
                assertEquals(RiggedMatch.str(stack(g).get(0).getAsJsonObject().get("source")), ASCENSION);
                match.http.record("GET /game#end-step-response", g);
                break;
            }
            final boolean ready = asked(g) && mine && "MAIN1".equals(phase) && stack(g).isEmpty() && turn > endedTurn
                    && RiggedMatch.card(me, "hand", "Fog") != null && RiggedMatch.untapped(me, "Forest") >= 1 && match.hostWaitsOnSeat();
            if (ready) {
                if (match.hostCount(RiggedMatch.HOST, ZoneType.Battlefield, ASCENSION) == 0) {
                    match.hostPuts(RiggedMatch.HOST, ASCENSION);
                }
                endedTurn = turn;
                match.http.post("/game/act", BridgeHttp.body("action", "endTurn", "at", g.get("promptCount").getAsInt()));
            } else {
                match.basics(g, "Forest", "no spell", 99);
            }
        }
        match.finish();
    }

    @Test(timeOut = 420_000)
    public void anAttackEndsTheYieldOnTheOpponentsTurn() throws Exception {
        match = new RiggedMatch(deck("Mountain", "Shock"), RiggedMatch.deck("Host lands", "38 Forest", "2 " + BEARS));
        int endedTurn = -1;
        final long deadline = System.currentTimeMillis() + 360_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "the host did not attack in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final int turn = g.get("turn").isJsonNull() ? -1 : g.get("turn").getAsInt();
            final boolean mine = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            final String phase = RiggedMatch.str(g.get("phase"));
            final boolean holdsShock = me != null && RiggedMatch.card(me, "hand", "Shock") != null && RiggedMatch.untapped(me, "Mountain") >= 1;
            if (asked(g) && turn == endedTurn) {
                assertEquals(phase, "COMBAT_DECLARE_ATTACKERS", "the seat is asked after the attack is declared: " + g);
                assertTrue(g.has("combat") && !g.getAsJsonArray("combat").isEmpty(), "with the attack in the game: " + g);
                assertTrue(g.getAsJsonArray("combat").toString().contains(BEARS), g.get("combat").toString());
                match.http.record("GET /game#attack-ends-yield", g);
                break;
            }
            if (asked(g) && mine && stack(g).isEmpty() && match.hostWaitsOnSeat() && match.hostCount(RiggedMatch.HOST, ZoneType.Battlefield, BEARS) == 0
                    && RiggedMatch.untapped(me, "Mountain") >= 1) {
                // On the seat's turn, so that the Bears can attack on the host's next one.
                match.hostPuts(RiggedMatch.HOST, BEARS);
            }
            if (asked(g) && !mine && "COMBAT_BEGIN".equals(phase) && stack(g).isEmpty() && holdsShock
                    && match.hostCount(RiggedMatch.HOST, ZoneType.Battlefield, BEARS) >= 1) {
                endedTurn = turn;
                match.http.post("/game/act", BridgeHttp.body("action", "endTurn", "at", g.get("promptCount").getAsInt()));
            } else {
                match.basics(g, "Mountain", "no spell", 99);
            }
        }
        match.finish();
    }

    private static String deck(final String land, final String instant) {
        return String.join("\n", "[metadata]", "Name=Agent End Turn Interrupt Test", "[Main]", "20 " + land, "20 " + instant);
    }

    private static boolean asked(final JsonObject g) {
        return g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g)
                && g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean();
    }

    private static JsonArray stack(final JsonObject g) {
        return g.has("stack") ? g.getAsJsonArray("stack") : new JsonArray();
    }
}
