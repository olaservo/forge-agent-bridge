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

import forge.game.zone.ZoneType;

/**
 * An opponent's trigger keeps the seat its chance to respond, above the seat's spell and during a yield. The
 * seat casts Grizzly Bears. The host has Managorger Hydra, whose trigger goes on the stack when a player casts
 * a spell, and Essence Warden, whose trigger goes on the stack when another creature enters. The test puts
 * both on the host's battlefield from its library, so the host AI does not have to draw and cast them.
 *
 * The cast is sent with {@code pass: true}. The Hydra's trigger is on top of the Bears, so nothing is passed
 * and the seat is asked. OK there passes one time: the trigger resolves and the seat is asked again with its
 * Bears on top. OK on the Bears yields until the stack is empty. The Bears resolve, the Warden's trigger goes
 * on the stack, the yield ends and the seat is asked before the host gains its life. A second Bears, on a later
 * turn, is cast with {@code endTurn: true}: the trigger is on top again and the turn does not end.
 */
public class AgentOpponentTriggerTest {

    private static final String BEARS = "Grizzly Bears";
    private static final String HYDRA = "Managorger Hydra";
    private static final String WARDEN = "Essence Warden";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Opponent Trigger Test",
            "[Main]",
            "18 Forest",
            "22 " + BEARS);

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("opponent-trigger");
        }
    }

    @Test(timeOut = 420_000)
    public void anOpponentsTriggerIsAskedAboveTheSeatsSpellAndDuringAYield() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host triggers", "38 Forest", "1 " + HYDRA, "1 " + WARDEN));
        int setupTurn = -1;
        int stage = 0;
        int stageTurn = -1;
        final long deadline = System.currentTimeMillis() + 360_000;
        while (stage < 2) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "stage " + stage + " not reached in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final int turn = g.get("turn").isJsonNull() ? -1 : g.get("turn").getAsInt();
            final boolean mine = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            final JsonObject bears = me == null ? null : RiggedMatch.card(me, "hand", BEARS);
            final boolean main = asked(g) && mine && "MAIN1".equals(RiggedMatch.str(g.get("phase"))) && stack(g).isEmpty() && match.hostWaitsOnSeat();
            if (main && setupTurn < 0) {
                // A turn before the cast: the Hydra entering is a creature entering, and the Warden's trigger for it resolves first.
                if (match.hostCount(RiggedMatch.HOST, ZoneType.Battlefield, WARDEN) == 0) {
                    match.hostPuts(RiggedMatch.HOST, WARDEN);
                }
                if (match.hostCount(RiggedMatch.HOST, ZoneType.Battlefield, HYDRA) == 0) {
                    match.hostPuts(RiggedMatch.HOST, HYDRA);
                }
                setupTurn = turn;
            }
            final boolean canCast = main && bears != null && RiggedMatch.untapped(me, "Forest") >= 2 && turn > stageTurn
                    && setupTurn >= 0 && turn > setupTurn;
            if (!canCast) {
                match.basics(g, "Forest", "no spell", 99);
                continue;
            }
            final JsonObject body = BridgeHttp.body("action", "selectCard", "id", bears.get("id").getAsInt(), "pay", "auto");
            body.addProperty(stage == 0 ? "pass" : "endTurn", true);
            match.http.post("/game/act", body);
            JsonObject now = match.http.get("/game");
            assertTrue(asked(now) && stack(now).size() == 2, "the seat is asked with the trigger on its spell: " + now);
            assertEquals(by(now, 0), RiggedMatch.HOST, "the host's trigger is on top: " + now);
            assertEquals(source(now, 0), HYDRA);
            assertEquals(by(now, 1), RiggedMatch.ME, "and the seat's spell under it: " + now);
            assertEquals(now.get("turn").getAsInt(), turn, "the turn has not ended: " + now);
            if (stage == 0) {
                match.http.record("GET /game#trigger-on-own-spell", now);
                theTriggerResolvesAndTheSeatIsAskedTwice();
            }
            stage++;
            stageTurn = turn;
        }
        match.finish();
    }

    /** OK passes the host's trigger once, then yields on the seat's own spell until the host's next trigger. */
    private void theTriggerResolvesAndTheSeatIsAskedTwice() throws Exception {
        final int bearsBefore = count(RiggedMatch.me(match.http.get("/game")), "battlefield", BEARS);
        match.act("ok", null);
        AgentGameTest.waitFor("the seat is asked again with its own spell on top", 15_000, () -> {
            final JsonObject g = match.http.get("/game");
            return asked(g) && stack(g).size() == 1 && RiggedMatch.ME.equals(by(g, 0));
        });
        assertEquals(count(RiggedMatch.me(match.http.get("/game")), "battlefield", BEARS), bearsBefore, "the Bears have not resolved");

        final int life = match.hostLife(RiggedMatch.HOST);
        match.act("ok", null); // every item on the stack is the seat's: the yield until the stack is empty
        final long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "the seat was not asked about the Warden's trigger: " + g);
            final boolean onTop = !stack(g).isEmpty() && RiggedMatch.HOST.equals(by(g, 0)) && WARDEN.equals(source(g, 0));
            if (asked(g) && onTop) {
                assertEquals(match.hostLife(RiggedMatch.HOST), life, "the trigger has not resolved: " + g);
                assertEquals(count(RiggedMatch.me(g), "battlefield", BEARS), bearsBefore + 1, "the Bears have resolved: " + g);
                match.http.record("GET /game#trigger-ends-yield", g);
                break;
            }
            assertEquals(match.hostLife(RiggedMatch.HOST), life, "the host's trigger resolved before the seat was asked: " + g);
        }
        match.act("ok", null); // one pass: the top of the stack is the host's
        AgentGameTest.waitFor("the Warden's trigger resolves", 15_000, () -> match.hostLife(RiggedMatch.HOST) == life + 1);
    }

    private static boolean asked(final JsonObject g) {
        return g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g)
                && g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean();
    }

    private static JsonArray stack(final JsonObject g) {
        return g.has("stack") ? g.getAsJsonArray("stack") : new JsonArray();
    }

    private static String by(final JsonObject g, final int index) {
        return RiggedMatch.str(stack(g).get(index).getAsJsonObject().get("by"));
    }

    private static String source(final JsonObject g, final int index) {
        return RiggedMatch.str(stack(g).get(index).getAsJsonObject().get("source"));
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
