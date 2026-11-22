package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.zone.ZoneType;

/**
 * An Aura names what it is attached to. The deck is 20 Forest and 20 Wild Growth, an Aura that
 * enchants a land. The agent casts one on the first land it is offered, and the bridge lists the
 * Aura with {@code attachedTo}, a Forest the host has on a battlefield.
 */
public class AgentAttachedTest {

    private static final String AURA = "Wild Growth";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Attached Test",
            "[Main]",
            "20 Forest",
            "20 " + AURA);

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("attached");
        }
    }

    @Test(timeOut = 300_000)
    public void anAuraNamesWhatItIsAttachedTo() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final long deadline = System.currentTimeMillis() + 240_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no Aura was attached in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject aura = aura(RiggedMatch.me(g));
            if (aura != null) {
                assertTrue(aura.has("attachedTo"), "the Aura names its host: " + aura);
                final JsonObject to = aura.getAsJsonObject("attachedTo");
                assertEquals(to.get("type").getAsString(), "card");
                assertEquals(to.get("name").getAsString(), "Forest");
                final int id = to.get("id").getAsInt();
                assertTrue(match.hostIds(RiggedMatch.ME, ZoneType.Battlefield).contains(id)
                        || match.hostIds(RiggedMatch.HOST, ZoneType.Battlefield).contains(id),
                        "the host has the enchanted land on a battlefield");
                match.http.record("GET /game#attachedTo", g);
                break;
            }
            match.basics(g, "Forest", AURA, 1);
        }
        match.finish();
    }

    private static JsonObject aura(final JsonObject me) {
        if (me != null) {
            for (final JsonElement c : me.getAsJsonArray("battlefield")) {
                if (AURA.equals(c.getAsJsonObject().get("name").getAsString())) {
                    return c.getAsJsonObject();
                }
            }
        }
        return null;
    }
}
