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
 * A click the host refuses passes nothing, also with the seat's own spell already on the stack. The seat's
 * deck is Islands and Opt, and the host AI plays only Forests. In its main phase the seat casts Opt without
 * {@code pass} and keeps priority. It then clicks an Island in its hand, which the host refuses with a spell
 * on the stack, once with {@code pass: true} and once with {@code endTurn: true}. Opt stays on the stack and
 * the seat is still asked in the same turn.
 */
public class AgentRefusedClickTest {

    private static final String OPT = "Opt";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Refused Click Test",
            "[Main]",
            "20 Island",
            "20 " + OPT);

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("refused-click");
        }
    }

    @Test(timeOut = 300_000)
    public void aRefusedClickDoesNotPassTheSpellCastBeforeIt() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final long deadline = System.currentTimeMillis() + 240_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "the seat did not get to cast in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final boolean mine = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            final JsonObject opt = me == null ? null : RiggedMatch.card(me, "hand", OPT);
            final JsonObject land = me == null ? null : RiggedMatch.card(me, "hand", "Island");
            if (asked(g) && mine && "MAIN1".equals(RiggedMatch.str(g.get("phase"))) && stack(g).isEmpty() && opt != null && land != null
                    && RiggedMatch.untapped(me, "Island") >= 1) {
                final int turn = g.get("turn").getAsInt();
                match.http.post("/game/act", BridgeHttp.body("action", "selectCard", "id", opt.get("id").getAsInt(), "pay", "auto"));
                JsonObject now = match.http.get("/game");
                assertTrue(asked(now) && stack(now).size() == 1, "the seat keeps priority with Opt on the stack: " + now);
                final int spell = stack(now).get(0).getAsJsonObject().get("id").getAsInt();

                JsonObject body = BridgeHttp.body("action", "selectCard", "id", land.get("id").getAsInt());
                body.addProperty("pass", true);
                match.http.post("/game/act", body);
                now = match.http.get("/game");
                assertTrue(asked(now) && stack(now).size() == 1 && stack(now).get(0).getAsJsonObject().get("id").getAsInt() == spell,
                        "a refused click with pass does not pass the spell cast before it: " + now);

                body = BridgeHttp.body("action", "selectCard", "id", land.get("id").getAsInt());
                body.addProperty("endTurn", true);
                match.http.post("/game/act", body);
                now = match.http.get("/game");
                assertTrue(asked(now) && stack(now).size() == 1 && stack(now).get(0).getAsJsonObject().get("id").getAsInt() == spell,
                        "a refused click with endTurn does not yield the turn: " + now);
                assertEquals(now.get("turn").getAsInt(), turn);
                assertEquals(RiggedMatch.str(now.get("phase")), "MAIN1", "the seat is still in its main phase: " + now);
                break;
            }
            match.basics(g, "Island", "no spell", 99);
        }
        match.finish();
    }

    private static boolean asked(final JsonObject g) {
        return g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g)
                && g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean();
    }

    private static JsonArray stack(final JsonObject g) {
        return g.has("stack") ? g.getAsJsonArray("stack") : new JsonArray();
    }
}
