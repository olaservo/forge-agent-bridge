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

/**
 * Stops and yields. The seat plays Forests and Elvish Visionary, whose enters trigger draws a card, and the
 * host AI plays only Forests, so it never responds and never blocks.
 *
 * The seat is asked for priority on an empty stack only in its own main phases. One game then goes through:
 * a cast paid with {@code pay: "auto"}, an OK with {@code once} that leaves the trigger to be passed, an OK
 * that yields until the stack is empty, a cast with {@code pass} that resolves spell and trigger in one call,
 * an attack with {@code endTurn}, an action refused by {@code at}, and the {@code endTurn} action.
 */
public class AgentYieldTest {

    private static final String VISIONARY = "Elvish Visionary";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Yield Test",
            "[Main]",
            "17 Forest",
            "23 " + VISIONARY);

    private RiggedMatch match;
    /** Priority prompts the seat was asked, as "turn owner phase stack". */
    private final List<String> asks = new ArrayList<>();
    private int lastAsk = -1;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("yield");
        }
    }

    @Test(timeOut = 420_000)
    public void theSeatIsAskedOnlyWhereItHasSomethingToDecide() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        int stage = 0;
        int stageTurn = -1;
        int turnSeen = -1;
        int forestsAtTurnStart = 0;
        final long deadline = System.currentTimeMillis() + 360_000;
        while (stage < 4) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "stage " + stage + " not reached in time; asks " + asks + "; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            note(g);
            final JsonObject me = RiggedMatch.me(g);
            final int turn = g.get("turn").isJsonNull() ? -1 : g.get("turn").getAsInt();
            final boolean mine = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            if (me != null && turn != turnSeen) {
                turnSeen = turn;
                forestsAtTurnStart = count(me, "battlefield", "Forest");
            }
            final boolean landDone = me != null
                    && (RiggedMatch.card(me, "hand", "Forest") == null || count(me, "battlefield", "Forest") > forestsAtTurnStart);
            final boolean myMain = mine && "MAIN1".equals(RiggedMatch.str(g.get("phase"))) && asked(g) && stack(g).isEmpty() && landDone;
            final JsonObject visionary = me == null ? null : RiggedMatch.card(me, "hand", VISIONARY);
            final boolean canCast = myMain && visionary != null && RiggedMatch.untapped(me, "Forest") >= 2 && turn > stageTurn;

            if (stage == 0 && canCast) {
                castPassingOnceThenYielding(visionary.get("id").getAsInt());
                stage = 1;
                stageTurn = turn;
            } else if (stage == 1 && canCast) {
                castWithPass(visionary.get("id").getAsInt());
                attackAndEndTurn(turn);
                stage = 2;
                stageTurn = turn;
            } else if (stage == 2 && myMain && turn > stageTurn) {
                refusedWhenThePromptHasChanged(g);
                stage = 3;
            } else if (stage == 3 && myMain) {
                endTurnFromTheMainPhase(turn);
                stage = 4;
            } else {
                match.basics(g, "Forest", "no spell", 99);
            }
        }
        for (final String ask : asks) {
            final String[] parts = ask.split(" ");
            if ("own".equals(parts[1]) && "0".equals(parts[3])) {
                assertTrue(parts[2].startsWith("MAIN"), "on its own turn the seat is asked on an empty stack only in a main phase: " + asks);
            }
        }
        assertTrue(asks.stream().anyMatch(a -> a.contains(" own MAIN2 0")), "the second main phase is a stop: " + asks);
        match.finish();
    }

    /** {@code pay: "auto"} pays, {@code once} passes one time and the plain OK passes until the stack is empty. */
    private void castPassingOnceThenYielding(final int id) throws Exception {
        final JsonObject cast = act("selectCard", id, "pay", "auto");
        assertTrue(cast.get("priorityPrompt").getAsBoolean(), "the cost was paid in the same call: " + cast);
        JsonObject g = match.http.get("/game");
        assertEquals(stack(g).size(), 1, "the spell is on the stack: " + g);
        final JsonObject spell = stack(g).get(0).getAsJsonObject();
        assertEquals(spell.get("by").getAsString(), RiggedMatch.ME);
        assertTrue(spell.get("targets").isJsonArray(), "a stack item lists its targets: " + spell);
        assertTrue(RiggedMatch.me(g).get("mana").isJsonObject(), "a player has a mana pool: " + g);
        assertTrue(g.get("logCount").getAsInt() > 0 && g.has("messageCount"), "the log and messages are counted: " + g);
        match.http.record("GET /game#stack", g);
        final int hand = RiggedMatch.me(g).get("handSize").getAsInt();

        act("ok", null, "once", true);
        AgentGameTest.waitFor("the seat is asked again with the trigger on the stack", 15_000, () -> {
            final JsonObject now = match.http.get("/game");
            return asked(now) && stack(now).size() == 1 && count(RiggedMatch.me(now), "battlefield", VISIONARY) == 1;
        });

        act("ok", null);
        AgentGameTest.waitFor("the trigger resolved", 15_000, () -> asked(match.http.get("/game")) && stack(match.http.get("/game")).isEmpty());
        g = match.http.get("/game");
        assertEquals(RiggedMatch.me(g).get("handSize").getAsInt(), hand + 1, "the trigger drew a card: " + g);
    }

    /** One call casts, pays and passes, and the seat is next asked with the stack empty. */
    private void castWithPass(final int id) throws Exception {
        final int before = count(RiggedMatch.me(match.http.get("/game")), "battlefield", VISIONARY);
        act("selectCard", id, "pay", "auto", "pass", true);
        final long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "the spell did not resolve: " + g);
            assertFalse(asked(g) && !stack(g).isEmpty(), "no ask while the seat's own spell and trigger resolve: " + g);
            if (asked(g) && count(RiggedMatch.me(g), "battlefield", VISIONARY) == before + 1) {
                return;
            }
        }
    }

    /** OK on the attack with {@code endTurn}: the attack is declared and nothing more is asked this turn. */
    private void attackAndEndTurn(final int turn) throws Exception {
        final int life = match.hostLife(RiggedMatch.HOST);
        act("ok", null); // leaves the main phase
        AgentGameTest.waitFor("the attack prompt", 15_000, () -> {
            final JsonObject g = match.http.get("/game");
            return "COMBAT_DECLARE_ATTACKERS".equals(RiggedMatch.str(g.get("phase"))) && !g.get("priorityPrompt").getAsBoolean()
                    && g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean();
        });
        act("alphaStrike", null);
        act("ok", null, "endTurn", true);
        nothingAskedUntilTheHostsTurn(turn);
        assertEquals(match.hostLife(RiggedMatch.HOST), life - 1, "the Visionary that could attack dealt its damage");
    }

    /** An action sent with a prompt count that is not the current one is not sent. */
    private void refusedWhenThePromptHasChanged(final JsonObject g) throws Exception {
        final int count = g.get("promptCount").getAsInt();
        final JsonObject refused = act("ok", null, "at", count + 7);
        assertTrue(refused.has("stale") && refused.get("stale").getAsBoolean(), "the action is marked stale: " + refused);
        assertEquals(refused.get("promptCount").getAsInt(), count, "and was not sent: " + refused);
        match.http.record("POST /game/act#stale", refused);
    }

    /** The {@code endTurn} action from the main phase, sent with the current prompt count: no attack prompt, no second main phase. */
    private void endTurnFromTheMainPhase(final int turn) throws Exception {
        final int life = match.hostLife(RiggedMatch.HOST);
        final JsonObject sent = act("endTurn", null, "at", match.http.get("/game").get("promptCount").getAsInt());
        assertFalse(sent.has("stale"), "the current prompt count is accepted: " + sent);
        nothingAskedUntilTheHostsTurn(turn);
        assertEquals(match.hostLife(RiggedMatch.HOST), life, "no attack was declared");
    }

    private void nothingAskedUntilTheHostsTurn(final int turn) throws Exception {
        final long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "the turn did not end: " + g);
            final boolean sameTurn = g.get("turn").getAsInt() == turn;
            final boolean anyButton = g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean();
            assertFalse(sameTurn && !g.get("waiting").getAsBoolean() && anyButton, "nothing is asked for the rest of the turn: " + g);
            if (!sameTurn) {
                return;
            }
        }
    }

    private JsonObject act(final String action, final Integer id, final Object... more) throws Exception {
        final JsonObject body = BridgeHttp.body("action", action, "id", id);
        for (final var e : BridgeHttp.body(more).entrySet()) {
            body.add(e.getKey(), e.getValue());
        }
        return match.http.post("/game/act", body);
    }

    /** True while the seat is offered priority. */
    private static boolean asked(final JsonObject g) {
        return g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g)
                && g.getAsJsonObject("buttons").get("okEnabled").getAsBoolean();
    }

    private static JsonArray stack(final JsonObject g) {
        return g.has("stack") ? g.getAsJsonArray("stack") : new JsonArray();
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

    private void note(final JsonObject g) {
        final int count = g.get("promptCount").getAsInt();
        if (asked(g) && count > lastAsk) {
            lastAsk = count;
            final boolean mine = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            asks.add("t" + g.get("turn").getAsInt() + " " + (mine ? "own" : "host") + " " + RiggedMatch.str(g.get("phase")) + " " + stack(g).size());
        }
    }
}
