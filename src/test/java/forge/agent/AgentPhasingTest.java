package forge.agent;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.zone.ZoneType;

/**
 * A phased-out permanent is marked. The deck is 20 Island and 20 Teferi's Isle, a land with
 * phasing. The agent plays an Isle, and on its next turn the Isle phases out: the bridge still
 * lists it on the battlefield, now with {@code phasedOut}, while the host's own game no longer
 * counts it among the cards there.
 */
public class AgentPhasingTest {

    private static final String ISLE = "Teferi's Isle";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Phasing Test",
            "[Main]",
            "20 Island",
            "20 " + ISLE);

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("phasing");
        }
    }

    @Test(timeOut = 300_000)
    public void aPhasedOutCardIsMarked() throws Exception {
        match = new RiggedMatch(DECK);
        boolean seenPhasedIn = false;
        final long deadline = System.currentTimeMillis() + 240_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "the Isle did not phase out in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject isle = isle(RiggedMatch.me(g));
            if (isle != null && isle.has("phasedOut")) {
                assertTrue(seenPhasedIn, "it was on the battlefield unmarked first");
                assertTrue(isle.get("phasedOut").getAsBoolean());
                assertFalse(match.hostIds(RiggedMatch.ME, ZoneType.Battlefield).contains(isle.get("id").getAsInt()),
                        "the host does not count it on the battlefield");
                match.http.record("GET /game#phasedOut", g);
                break;
            }
            seenPhasedIn |= isle != null;
            // One Isle only: it is legendary. After it, Islands.
            match.basics(g, isle == null ? ISLE : "Island", "no spell", 99);
        }
        match.finish();
    }

    private static JsonObject isle(final JsonObject me) {
        if (me != null) {
            for (final JsonElement c : me.getAsJsonArray("battlefield")) {
                if (ISLE.equals(c.getAsJsonObject().get("name").getAsString())) {
                    return c.getAsJsonObject();
                }
            }
        }
        return null;
    }
}
