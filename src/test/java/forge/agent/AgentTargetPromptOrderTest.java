package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.Selection;

/**
 * A target prompt opened by a trigger reaches the seat as "Waiting for ...", the prompt, its buttons with neither
 * enabled, and then the selection that names the targets (Forge's InputBase.showMessageInitial). The seat's game
 * showed the prompt before its selection, and forge-mcp-server built its event from that. The host here sends the
 * same calls in that order. The prompt is shown once its selection is there, and without one after a short wait.
 */
public class AgentTargetPromptOrderTest {

    private static final String DECK = String.join("\n", "[metadata]", "Name=Agent Target Prompt Order Test", "[Main]", "40 Forest");
    private static final String TARGET = "Viashino Pyromancer (36) - When Viashino Pyromancer enters, it deals 2 damage to target player or planeswalker.\n\nSelect target player or planeswalker";

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("targetpromptorder");
        }
    }

    @Test(timeOut = 300_000)
    public void aTargetPromptIsShownWithItsSelection() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final long deadline = System.currentTimeMillis() + 120_000;
        JsonObject g = match.next();
        while (!AgentGameTest.iHavePriority(g) || g.get("waiting").getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "the seat never had priority; last state " + g);
            match.basics(g, "Forest", "no spell", 99);
            g = match.next();
        }
        final PlayerView me = match.hostCard(RiggedMatch.ME, ZoneType.Library).getController().getView();
        final Selection targets = new Selection(List.of(), 1, 1, List.of(me), List.of(), List.of());

        // The selection follows the prompt and its buttons: no reader sees the prompt without it.
        match.hostAwaitsNextInput();
        AgentGameTest.waitFor("the seat is shown as waiting", 10_000, () -> match.http.get("/game").get("waiting").getAsBoolean());
        final int before = match.http.get("/game").get("promptCount").getAsInt();
        match.hostPrompts(TARGET);
        match.hostDisablesButtons();
        // Longer than the gap between the two calls in a game, and shorter than the wait for a selection.
        Thread.sleep(100);
        final JsonObject between = match.http.get("/game");
        assertTrue(between.get("waiting").getAsBoolean() && between.get("promptCount").getAsInt() == before, "the prompt is not shown before its selection: " + between);
        match.hostSelects(targets);
        final JsonObject[] seen = new JsonObject[1];
        AgentGameTest.waitFor("the seat is shown the prompt", 10_000, () -> {
            seen[0] = match.http.get("/game");
            return seen[0].get("promptCount").getAsInt() != before;
        });
        final JsonObject first = seen[0];
        assertEquals(first.get("prompt").getAsString(), TARGET, "the first the seat sees of the prompt: " + first);
        assertEquals(first.get("promptCount").getAsInt(), before + 1, "one prompt: " + first);
        assertFalse(first.get("waiting").getAsBoolean(), "the seat is asked: " + first);
        assertFalse(first.getAsJsonObject("buttons").get("okEnabled").getAsBoolean() || first.getAsJsonObject("buttons").get("cancelEnabled").getAsBoolean(),
                "no button answers it: " + first);
        assertEquals(first.getAsJsonObject("selection").getAsJsonArray("players").get(0).getAsJsonObject().get("name").getAsString(), RiggedMatch.ME,
                "the prompt comes with its target: " + first);
        match.http.record("GET /game#targetPromptWithSelection", first);

        // No selection follows: the prompt is kept back for a moment and then shown, so the seat is still asked.
        match.hostSelects(Selection.ofChosen(List.of()));
        match.hostAwaitsNextInput();
        AgentGameTest.waitFor("the seat is shown as waiting again", 10_000, () -> match.http.get("/game").get("waiting").getAsBoolean());
        match.hostPrompts(TARGET);
        match.hostDisablesButtons();
        final JsonObject held = match.http.get("/game");
        assertTrue(held.get("waiting").getAsBoolean() && held.get("promptCount").getAsInt() == before + 1, "the prompt is kept back at first: " + held);
        AgentGameTest.waitFor("the prompt is shown without a selection", 5_000, () -> {
            final JsonObject shown = match.http.get("/game");
            return shown.get("promptCount").getAsInt() == before + 2 && !shown.get("waiting").getAsBoolean() && shown.get("prompt").getAsString().equals(TARGET);
        });
        assertFalse(match.http.get("/game").has("selection"), "it has no selection");
        match.finish();
    }
}
