package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A response to an opponent's spell leaves the seat its second chance to respond. The seat's deck is Islands
 * and Opt, and the host AI casts cheap creatures. With the host's creature on the stack the seat casts Opt in
 * the guide's cast form, {@code pay: "auto", pass: true}. Opt resolves, and the seat is asked again with the
 * creature on top of the stack before it resolves.
 *
 * The seat holds a second Opt and an untapped Island then, so the host asks it on the host's turn.
 */
public class AgentSecondResponseTest {

    private static final String OPT = "Opt";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Second Response Test",
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
            match.close("second-response");
        }
    }

    @Test(timeOut = 300_000)
    public void theSeatIsAskedAgainAboutTheSpellUnderItsOwn() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host creatures", "18 Forest", "11 Llanowar Elves", "11 Grizzly Bears"));
        int creature = -1;
        final long deadline = System.currentTimeMillis() + 240_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "the seat was not asked a second time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final JsonArray stack = g.has("stack") ? g.getAsJsonArray("stack") : new JsonArray();
            final boolean asked = g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g);

            if (creature >= 0) {
                // After the cast with pass. Nothing is sent here but the answer to Opt's scry.
                assertTrue(onStack(stack, creature), "the host's spell left the stack before the seat was asked again: " + g);
                if (g.has("pendingDecision") && !g.get("pendingDecision").isJsonNull()) {
                    match.basics(g, "Island", "no spell", 99);
                } else if (asked && stack.size() == 1) {
                    final JsonObject top = stack.get(0).getAsJsonObject();
                    assertEquals(top.get("by").getAsString(), RiggedMatch.HOST, "the host's spell is on top: " + g);
                    assertNotNull(RiggedMatch.card(me, "graveyard", OPT), "and Opt has resolved: " + g);
                    match.http.record("GET /game#second-response", g);
                    break;
                }
            } else if (asked && stack.size() == 1 && RiggedMatch.HOST.equals(RiggedMatch.str(stack.get(0).getAsJsonObject().get("by")))
                    && me != null && RiggedMatch.untapped(me, "Island") >= 2 && count(me, "hand", OPT) >= 2) {
                creature = stack.get(0).getAsJsonObject().get("id").getAsInt();
                final JsonObject body = BridgeHttp.body("action", "selectCard", "id", RiggedMatch.card(me, "hand", OPT).get("id").getAsInt(), "pay", "auto");
                body.addProperty("pass", true);
                match.http.post("/game/act", body);
            } else {
                match.basics(g, "Island", "no spell", 99);
            }
        }
        match.finish();
    }

    private static boolean onStack(final JsonArray stack, final int id) {
        for (final JsonElement item : stack) {
            if (item.getAsJsonObject().get("id").getAsInt() == id) {
                return true;
            }
        }
        return false;
    }

    private static int count(final JsonObject player, final String zone, final String name) {
        int n = 0;
        if (player != null && player.has(zone)) {
            for (final JsonElement c : player.getAsJsonArray(zone)) {
                if (name.equals(c.getAsJsonObject().get("name").getAsString())) {
                    n++;
                }
            }
        }
        return n;
    }
}
