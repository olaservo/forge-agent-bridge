package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A spell on the stack can be chosen as a target. The seat's deck is 20 Island and 20 Counterspell, and the
 * host AI casts cheap creatures. When the host's creature is on the stack the seat casts Counterspell. The
 * target prompt offers the creature under {@code selection.stack}, the seat picks it with {@code selectStack},
 * and the creature goes to the host's graveyard. A card click at that prompt is refused.
 *
 * Forge 2.0.16 asks a network client to pick a stack target in its stack view (InputSelectStackItems), and the
 * click arrives as {@code selectStackItem}. A card id does not answer that prompt.
 */
public class AgentCounterTest {

    private static final String COUNTER = "Counterspell";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Counter Test",
            "[Main]",
            "20 Island",
            "20 " + COUNTER);

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("counter");
        }
    }

    @Test(timeOut = 300_000)
    public void aSpellOnTheStackIsOfferedAsATargetAndCountered() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host creatures", "18 Forest", "11 Llanowar Elves", "11 Grizzly Bears"));
        String countering = null;
        int castAt = -1;
        final long deadline = System.currentTimeMillis() + 240_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no spell countered in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final JsonArray stack = g.has("stack") ? g.getAsJsonArray("stack") : new JsonArray();
            final JsonObject sel = g.has("selection") ? g.getAsJsonObject("selection") : null;
            final JsonArray offered = sel != null && sel.has("stack") ? sel.getAsJsonArray("stack") : new JsonArray();
            final boolean asked = g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g);

            if (countering != null) {
                if (stack.isEmpty()) {
                    final JsonObject host = RiggedMatch.player(g, false);
                    assertNotNull(RiggedMatch.card(host, "graveyard", countering), "the countered spell is in the host's graveyard: " + g);
                    assertNotNull(RiggedMatch.card(me, "graveyard", COUNTER), "and Counterspell in the seat's: " + g);
                    break;
                }
                match.basics(g, "Island", "no spell", 99);
            } else if (!offered.isEmpty()) {
                // The target prompt for Counterspell.
                match.http.record("GET /game#stack-targets", g);
                assertEquals(offered.size(), 1, "one spell is on the stack: " + sel);
                final JsonObject target = offered.get(0).getAsJsonObject();
                final JsonObject top = stack.get(0).getAsJsonObject();
                assertEquals(target.get("id").getAsInt(), top.get("id").getAsInt(), "the offered entry is the one on the stack");
                assertEquals(target.get("by").getAsString(), RiggedMatch.HOST);
                assertEquals(target.get("source").getAsString(), top.get("source").getAsString());
                assertTrue(sel.getAsJsonArray("cards").isEmpty(), "no card answers this prompt: " + sel);
                assertNull(RiggedMatch.required(g), "and none is required");

                final JsonObject wrong = match.http.postExpectingError("/game/act", BridgeHttp.body("action", "selectStack", "id", target.get("id").getAsInt() + 1000));
                assertTrue(wrong.toString().contains("does not offer stack item"), "an entry that is not offered is refused: " + wrong);

                final JsonObject card = match.http.postExpectingError("/game/act", BridgeHttp.body("action", "selectCard", "id", target.get("id").getAsInt()));
                assertTrue(card.toString().contains("use selectStack"), "a card click at this prompt is refused and names the action: " + card);

                countering = target.get("source").getAsString();
                final JsonObject body = BridgeHttp.body("action", "selectStack", "id", target.get("id").getAsInt(), "pay", "auto");
                body.addProperty("pass", true);
                final JsonObject after = match.http.post("/game/act", body);
                match.http.record("POST /game/act#selectStack", after);
            } else if (asked && stack.size() == 1 && RiggedMatch.HOST.equals(RiggedMatch.str(stack.get(0).getAsJsonObject().get("by")))
                    && me != null && RiggedMatch.untapped(me, "Island") >= 2 && RiggedMatch.card(me, "hand", COUNTER) != null
                    && stack.get(0).getAsJsonObject().get("id").getAsInt() != castAt) {
                castAt = stack.get(0).getAsJsonObject().get("id").getAsInt();
                match.act("selectCard", RiggedMatch.card(me, "hand", COUNTER).get("id").getAsInt());
            } else {
                match.basics(g, "Island", "no spell", 99);
            }
        }
        match.finish();
    }
}
