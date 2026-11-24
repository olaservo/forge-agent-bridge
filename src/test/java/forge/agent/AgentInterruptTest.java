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
 * A yield ends when the opponent responds, so the seat keeps its chance to answer. The seat casts Centaur
 * Courser, which costs three, and the host AI holds Counterspell with Islands untapped: its profile counters
 * a spell of that cost every time. The seat casts once with {@code pass}, which yields until the stack is
 * empty, and once with {@code endTurn}, which yields until end of turn. Each time it is asked again with the
 * Counterspell on top of its own spell, before either resolves.
 *
 * After {@code endTurn} on its own turn the bridge also switches auto-pass on, as it does after the End Turn
 * button, so the host asks only a seat that could act. For that cast the seat holds a Fog and a Forest to
 * pay for it. With nothing it could play it is not asked, and reads in the game log that its spell was countered.
 */
public class AgentInterruptTest {

    private static final String COURSER = "Centaur Courser";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Interrupt Test",
            "[Main]",
            "20 Forest",
            "12 " + COURSER,
            "8 Fog");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("interrupt");
        }
    }

    @Test(timeOut = 420_000)
    public void anOpponentsResponseEndsTheYieldAndTheSeatIsAsked() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host counters", "20 Island", "20 Counterspell"));
        int interrupted = 0;
        int castTurn = -1;
        int turnSeen = -1;
        int forestsAtTurnStart = 0;
        final long deadline = System.currentTimeMillis() + 360_000;
        while (interrupted < 2) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, interrupted + " of 2 casts were answered in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final int turn = g.get("turn").isJsonNull() ? -1 : g.get("turn").getAsInt();
            if (me != null && turn != turnSeen) {
                turnSeen = turn;
                forestsAtTurnStart = RiggedMatch.untapped(me, "Forest");
            }
            final JsonArray stack = g.has("stack") ? g.getAsJsonArray("stack") : new JsonArray();
            final boolean asked = g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g);
            final boolean mine = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            final boolean landDone = me != null && (RiggedMatch.card(me, "hand", "Forest") == null || RiggedMatch.untapped(me, "Forest") > forestsAtTurnStart);
            final JsonObject courser = me == null ? null : RiggedMatch.card(me, "hand", COURSER);

            if (asked && stack.size() == 2) {
                // The host answered the cast. The seat is asked before anything resolves.
                assertEquals(turn, castTurn, "in the turn of the cast: " + g);
                // The stack is listed from the top down.
                assertEquals(stack.get(1).getAsJsonObject().get("by").getAsString(), RiggedMatch.ME);
                final JsonObject top = stack.get(0).getAsJsonObject();
                assertEquals(top.get("by").getAsString(), RiggedMatch.HOST, "the host's spell is on top: " + g);
                assertEquals(top.get("source").getAsString(), "Counterspell");
                assertTrue(top.get("targets").toString().contains(COURSER), "and it names what it targets: " + top);
                match.http.record("GET /game#response", g);
                interrupted++;
                match.act("ok", null); // no response of our own: one pass, since the top of the stack is the host's
            } else if (asked && mine && stack.isEmpty() && "MAIN1".equals(RiggedMatch.str(g.get("phase"))) && landDone && courser != null
                    && turn > castTurn && (interrupted == 0 ? RiggedMatch.untapped(me, "Forest") >= 3
                            : RiggedMatch.untapped(me, "Forest") >= 4 && RiggedMatch.card(me, "hand", "Fog") != null)) {
                castTurn = turn;
                // First with the yield until the stack is empty, then with the yield until end of turn.
                final JsonObject body = BridgeHttp.body("action", "selectCard", "id", courser.get("id").getAsInt(), "pay", "auto");
                body.addProperty(interrupted == 0 ? "pass" : "endTurn", true);
                match.http.post("/game/act", body);
            } else {
                match.basics(g, "Forest", "no spell", 99);
            }
        }
        match.finish();
    }
}
