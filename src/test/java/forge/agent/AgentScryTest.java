package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.zone.ZoneType;

/**
 * Scry reaches the seat as a decision, and the answer is what the host does. The deck is 20
 * Island and 20 Preordain (scry 2, then draw). The agent casts one, is asked which cards to keep
 * on top, keeps the second and sends the first to the bottom. The test then reads the host's own
 * game: the kept card was drawn and the other is the last card of the library.
 */
public class AgentScryTest {

    private static final Pattern ID = Pattern.compile("\\((\\d+)\\)\\s*$");
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Scry Test",
            "[Main]",
            "20 Island",
            "20 Preordain");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("scry");
        }
    }

    @Test(timeOut = 300_000)
    public void agentChoosesWhatAScryKeepsOnTop() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final List<Integer> looked = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + 240_000;
        while (looked.isEmpty()) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no scry in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended before the scry: " + g);
            final JsonObject d = RiggedMatch.decision(g, "order");
            if (d == null) {
                match.basics(g, "Island", "Preordain", 1);
                continue;
            }
            match.http.record("GET /game#order", g);
            final JsonArray options = d.getAsJsonArray("options");
            assertEquals(options.size(), 2, "scry 2 shows two cards: " + d);
            assertEquals(d.get("min").getAsInt(), 0, "any number may go to the bottom: " + d);
            assertEquals(d.get("max").getAsInt(), 2, "or stay on top: " + d);
            for (final JsonElement o : options) {
                final Matcher m = ID.matcher(o.getAsString());
                assertTrue(m.find(), "an option names its card id: " + o);
                looked.add(Integer.parseInt(m.group(1)));
            }
            final JsonArray choices = new JsonArray();
            choices.add(1); // keep the second card on top; the first goes to the bottom
            match.http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices));
        }

        // What the host did with the answer, read from its own game.
        final int toBottom = looked.get(0);
        final int kept = looked.get(1);
        AgentGameTest.waitFor("the kept card is drawn", 20_000, () -> match.hostIds(RiggedMatch.ME, ZoneType.Hand).contains(kept));
        final List<Integer> library = match.hostIds(RiggedMatch.ME, ZoneType.Library);
        assertEquals(library.get(library.size() - 1).intValue(), toBottom, "the other card is on the bottom of the library");
        assertFalse(match.http.get("/game").has("pendingDecision"), "the scry is over");
        match.finish();
    }
}
