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
 * Surveil of two or more cards asks which cards go to the graveyard, and the seat may choose any
 * number of them. The deck is 20 Island and 20 Curate (surveil 2, then draw). The agent casts one,
 * sends the first card to the graveyard and keeps the second. The test then reads the host's own
 * game: the first card is in the graveyard and the second was drawn.
 */
public class AgentSurveilTest {

    private static final Pattern ID = Pattern.compile("\\((\\d+)\\)\\s*$");
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Surveil Test",
            "[Main]",
            "20 Island",
            "20 Curate");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("surveil");
        }
    }

    @Test(timeOut = 300_000)
    public void agentChoosesWhatASurveilPutsInTheGraveyard() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final List<Integer> looked = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + 240_000;
        while (looked.isEmpty()) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no surveil in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended before the surveil: " + g);
            final JsonObject d = RiggedMatch.decision(g, "order");
            if (d == null) {
                match.basics(g, "Island", "Curate", 2);
                continue;
            }
            match.http.record("GET /game#order", g);
            final JsonArray options = d.getAsJsonArray("options");
            assertEquals(options.size(), 2, "surveil 2 shows two cards: " + d);
            assertTrue(d.get("prompt").getAsString().contains("graveyard"), "it asks what goes to the graveyard: " + d);
            assertEquals(d.get("min").getAsInt(), 0, "none has to go: " + d);
            assertEquals(d.get("max").getAsInt(), 2, "both may go: " + d);
            for (final JsonElement o : options) {
                final Matcher m = ID.matcher(o.getAsString());
                assertTrue(m.find(), "an option names its card id: " + o);
                looked.add(Integer.parseInt(m.group(1)));
            }
            final JsonArray choices = new JsonArray();
            choices.add(0); // the first card goes to the graveyard; the second stays on top
            match.http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices));
        }

        // What the host did with the answer, read from its own game.
        final int toGraveyard = looked.get(0);
        final int kept = looked.get(1);
        AgentGameTest.waitFor("the kept card is drawn", 20_000, () -> match.hostIds(RiggedMatch.ME, ZoneType.Hand).contains(kept));
        assertTrue(match.hostIds(RiggedMatch.ME, ZoneType.Graveyard).contains(toGraveyard), "the other card is in the graveyard");
        assertFalse(match.http.get("/game").has("pendingDecision"), "the surveil is over");
        match.finish();
    }
}
