package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.zone.ZoneType;

/**
 * A prompt that requires a number of cards is answered by picking them. The host
 * AI's deck is Swamps and Blackmail, which has the agent reveal three cards from its hand and
 * discard the one the AI picks. The prompt arrives as a {@code selection} with min and max of
 * three over the hand, with OK off. The third pick answers it without OK, because the maximum is
 * reached. The card discarded is one of the three.
 * The test needs the AI to cast its Blackmail.
 */
public class AgentChooseCardsTest {

    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Choose Cards Test",
            "[Main]",
            "40 Island");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("choosecards");
        }
    }

    @Test(timeOut = 300_000)
    public void threeRequiredCardsArePickedAndOneIsDiscarded() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host Blackmail", "20 Swamp", "20 Blackmail"));
        final List<Integer> picked = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + 240_000;
        while (picked.isEmpty()) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no prompt for three cards in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            if (!g.has("selection") || g.getAsJsonObject("selection").get("min").getAsInt() != 3) {
                match.basics(g, "Island", "no spell", 99);
                continue;
            }
            match.http.record("GET /game#required", g);
            final JsonObject sel = g.getAsJsonObject("selection");
            final JsonArray cards = sel.getAsJsonArray("cards");
            assertEquals(sel.get("max").getAsInt(), 3, "exactly three: " + sel);
            assertEquals(cards.size(), RiggedMatch.me(g).getAsJsonArray("hand").size(), "the whole hand is offered: " + sel);
            assertFalse(g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean(), "OK is off with nothing chosen: " + g.get("buttons"));
            for (final JsonElement c : cards) {
                if (picked.size() < 3) {
                    picked.add(c.getAsJsonObject().get("id").getAsInt());
                    match.act("selectCard", c.getAsJsonObject().get("id").getAsInt());
                }
                if (picked.size() == 2 && !g.has("checked")) {
                    final JsonObject two = match.http.get("/game");
                    match.http.record("GET /game#requiredTwoChosen", two);
                    assertEquals(two.getAsJsonObject("selection").getAsJsonArray("chosen").size(), 2, "two are chosen: " + two.get("selection"));
                    assertFalse(two.getAsJsonObject("buttons").get("okEnabled").getAsBoolean(), "OK is still off: " + two.get("buttons"));
                    g.addProperty("checked", true);
                }
            }
        }
        AgentGameTest.waitFor("one card is discarded", 30_000, () -> !match.hostIds(RiggedMatch.ME, ZoneType.Graveyard).isEmpty());
        final List<Integer> graveyard = match.hostIds(RiggedMatch.ME, ZoneType.Graveyard);
        assertEquals(graveyard.size(), 1, "one card was discarded: " + graveyard);
        assertTrue(picked.contains(graveyard.get(0)), "it is one of the three revealed, " + picked + ": " + graveyard);
        match.finish();
    }
}
