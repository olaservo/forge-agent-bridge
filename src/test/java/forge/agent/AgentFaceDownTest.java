package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The seat's own face-down card carries the name of its other face. The seat's deck is 20 Forest and 20
 * Snarling Undorak, a creature with morph, and the seat casts one face down with three lands. On the
 * battlefield the card has no name, as for every player, and {@code faceUpName} says what it is.
 */
public class AgentFaceDownTest {

    private static final String MORPH = "Snarling Undorak";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Face Down Test",
            "[Main]",
            "20 Forest",
            "20 " + MORPH);

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("facedown");
        }
    }

    @Test(timeOut = 300_000)
    public void theSeatsOwnFaceDownCardNamesItsOtherFace() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final long deadline = System.currentTimeMillis() + 240_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no face-down card in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            JsonObject down = null;
            for (final JsonElement c : me == null ? new JsonArray() : me.getAsJsonArray("battlefield")) {
                if (c.getAsJsonObject().get("faceDown").getAsBoolean()) {
                    down = c.getAsJsonObject();
                }
            }
            if (down != null) {
                match.http.record("GET /game#face-down", g);
                assertEquals(down.get("faceUpName").getAsString(), MORPH, "the seat's own face-down card names its other face: " + down);
                assertEquals(down.get("name").getAsString(), "", "and has no name of its own: " + down);
                for (final JsonElement c : me.getAsJsonArray("hand")) {
                    assertFalse(c.getAsJsonObject().has("faceUpName"), "a face-up card has no such field: " + c);
                }
                break;
            }
            final JsonObject ability = RiggedMatch.decision(g, "ability");
            if (ability != null) {
                // Cast it normally or face down: take the face-down one.
                final JsonArray options = ability.getAsJsonArray("options");
                int morph = -1;
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).getAsString().contains("face down")) {
                        morph = i;
                    }
                }
                assertTrue(morph >= 0, "the face-down cast is offered: " + ability);
                final JsonArray choices = new JsonArray();
                choices.add(morph);
                match.http.post("/game/decide", BridgeHttp.body("id", ability.get("id").getAsInt(), "choices", choices, "text", null));
            } else {
                match.basics(g, "Forest", MORPH, 3);
            }
        }
        match.finish();
    }
}
