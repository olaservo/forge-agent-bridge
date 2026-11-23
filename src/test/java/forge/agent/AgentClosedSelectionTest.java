package forge.agent;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.game.card.CardView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.Selection;

/**
 * A library search ends without the host sending "Waiting for ...", so the search prompt stayed while the host
 * waited on the other player (olaservo/forge-mcp-server#19). The host here sends the seat a selection that offers
 * a card and then closes it, as the search does. With nothing after it the seat is shown as waiting. When the
 * host sends a prompt after it, as the next input does, the seat is not.
 */
public class AgentClosedSelectionTest {

    private static final String DECK = String.join("\n", "[metadata]", "Name=Agent Closed Selection Test", "[Main]", "40 Forest");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("closedselection");
        }
    }

    @Test(timeOut = 300_000)
    public void aClosedSelectionWithNothingAfterItIsWaiting() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final long deadline = System.currentTimeMillis() + 120_000;
        JsonObject g = match.next();
        while (!AgentGameTest.iHavePriority(g) || g.get("waiting").getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "the seat never had priority; last state " + g);
            match.basics(g, "Forest", "no spell", 99);
            g = match.next();
        }
        final String priority = g.get("prompt").getAsString();
        final CardView card = match.hostCard(RiggedMatch.ME, ZoneType.Library).getView();
        final Selection offer = new Selection(List.of(card), 1, 1, List.of(), List.of(), List.of());

        // The next input's prompt follows the closed selection: the seat is not waiting.
        match.hostSelects(offer);
        match.hostSelects(Selection.ofChosen(List.of()));
        match.hostPrompts(priority);
        Thread.sleep(1_500);
        final JsonObject prompted = match.http.get("/game");
        assertFalse(prompted.get("waiting").getAsBoolean(), "a prompt followed: " + prompted);

        // Nothing follows: the seat is shown as waiting.
        match.hostSelects(offer);
        match.hostSelects(Selection.ofChosen(List.of()));
        AgentGameTest.waitFor("the seat is shown as waiting", 10_000, () -> match.http.get("/game").get("waiting").getAsBoolean());
        final JsonObject after = match.http.get("/game");
        assertTrue(after.get("prompt").getAsString().startsWith("Waiting for"), "the prompt says so: " + after);
        assertFalse(after.has("selection"), "the selection is gone: " + after);
        match.http.record("GET /game#closedSelection", after);
        match.finish();
    }
}
