package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The seat decides how a blocked trampler's damage is split, and its planned blocks show before
 * they are final. The agent's deck is Forests and Garruk's Companion, a 3/2 with trample; the host
 * AI plays Goblin Brigand, a 2/2 that attacks each combat if able, and Razorgrass Screen, a 2/1
 * that blocks each combat if able.
 *
 * Planned block, first: the agent does not attack until a Brigand has attacked it. It then picks
 * the attacker and the blocker. Before OK is pressed the state lists the blocker under that
 * attacker, with {@code blocked} false. The prompt's {@code actionable} names the creature that can
 * block, and at declare attackers the one that can attack.
 *
 * Split, second: the agent attacks with one Companion a turn until it is blocked, and goes on
 * blocking the Brigands. The bridge asks
 * with an "assign" decision whose default tramples over, and the agent puts all the damage on the
 * blocker instead. The host player's life total does not change in that combat.
 */
public class AgentCombatTest {

    private static final String CREATURE = "Garruk's Companion";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Combat Test",
            "[Main]",
            "18 Forest",
            "22 " + CREATURE);

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("combat");
        }
    }

    @Test(timeOut = 420_000)
    public void theSeatSplitsTrampleDamageAndSeesItsPlannedBlock() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host attackers and blockers", "18 Mountain", "11 Goblin Brigand", "11 Razorgrass Screen"));
        final Set<String> tried = new HashSet<>();
        boolean split = false;
        boolean planned = false;
        int lifeBeforeSplit = -1;
        int splitTurn = -1;
        final long deadline = System.currentTimeMillis() + 360_000;
        while (!(split && planned)) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "split seen: " + split + ", planned block seen: " + planned + "; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early; split seen: " + split + ", planned block seen: " + planned);
            final JsonObject buttons = g.getAsJsonObject("buttons");
            final boolean okEnabled = buttons.get("okEnabled").getAsBoolean();
            final boolean myTurn = RiggedMatch.ME.equals(RiggedMatch.str(g.get("activePlayer")));
            final String phase = RiggedMatch.str(g.get("phase"));
            final String turn = "t" + RiggedMatch.str(g.get("turn")) + ":";
            final JsonElement pd = g.get("pendingDecision");
            final JsonObject me = RiggedMatch.me(g);

            if (planned && pd != null && !pd.isJsonNull() && "assign".equals(pd.getAsJsonObject().get("kind").getAsString())) {
                final JsonObject d = pd.getAsJsonObject();
                match.http.record("GET /game#assign", g);
                final int amount = d.get("amount").getAsInt();
                final JsonArray options = d.getAsJsonArray("options");
                assertTrue(options.size() >= 2, "the blockers and the defender: " + d);
                assertTrue(options.get(0).getAsString().contains("lethal damage"), "the blocker says what is lethal: " + d);
                assertTrue(options.get(options.size() - 1).getAsString().contains(RiggedMatch.HOST), "the defender is the last option: " + d);
                lifeBeforeSplit = match.hostLife(RiggedMatch.HOST);
                splitTurn = g.get("turn").getAsInt();
                final JsonArray amounts = new JsonArray();
                amounts.add(amount);
                for (int i = 1; i < options.size(); i++) {
                    amounts.add(0);
                }
                assertFalse(d.get("default").getAsString().equals(amounts.toString().replace("[", "").replace("]", "")),
                        "the default does not put it all on the first blocker: " + d);
                match.http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "amounts", amounts));
                split = true;
            } else if (okEnabled && myTurn && "COMBAT_DECLARE_ATTACKERS".equals(phase) && planned && !split && tried.add(turn + "attack")) {
                // One attacker only, so that the host's life total changes by nothing but what tramples over.
                for (final JsonElement c : me.getAsJsonArray("battlefield")) {
                    final JsonObject card = c.getAsJsonObject();
                    if (CREATURE.equals(card.get("name").getAsString()) && !card.get("tapped").getAsBoolean() && !card.get("sick").getAsBoolean()) {
                        assertTrue(actionable(g).contains(card.get("id").getAsInt()), "a creature that can attack is actionable: " + g.get("actionable"));
                        match.act("selectCard", card.get("id").getAsInt());
                        break;
                    }
                }
            } else if (okEnabled && !myTurn && "COMBAT_DECLARE_BLOCKERS".equals(phase) && planned && g.has("combat")
                    && !g.getAsJsonArray("combat").isEmpty() && tried.add(turn + "block")) {
                // Each attacker gets a blocker of its own while there are untapped ones.
                final List<Integer> blockers = new ArrayList<>();
                for (final JsonElement c : me.getAsJsonArray("battlefield")) {
                    final JsonObject card = c.getAsJsonObject();
                    if (CREATURE.equals(card.get("name").getAsString()) && !card.get("tapped").getAsBoolean()) {
                        blockers.add(card.get("id").getAsInt());
                    }
                }
                final JsonArray attackers = g.getAsJsonArray("combat");
                for (int i = 0; i < attackers.size() && i < blockers.size(); i++) {
                    match.act("selectCard", attackers.get(i).getAsJsonObject().get("attackerId").getAsInt());
                    match.act("selectCard", blockers.get(i));
                }
                match.act("ok", null);
            } else if (okEnabled && !myTurn && "COMBAT_DECLARE_BLOCKERS".equals(phase) && !planned && g.has("combat")
                    && !g.getAsJsonArray("combat").isEmpty() && RiggedMatch.card(me, "battlefield", CREATURE) != null && tried.add(turn + "block")) {
                final int attacker = g.getAsJsonArray("combat").get(0).getAsJsonObject().get("attackerId").getAsInt();
                final int blocker = RiggedMatch.card(me, "battlefield", CREATURE).get("id").getAsInt();
                // The selection follows the prompt in a message of its own.
                AgentGameTest.waitFor("the block prompt's selection arrives", 5_000, () -> match.http.get("/game").has("selection"));
                final JsonObject asked = match.http.get("/game");
                assertTrue(actionable(asked).contains(blocker), "the creature that can block is actionable: " + asked);
                match.http.record("GET /game#blockers", asked);
                match.act("selectCard", attacker);
                match.act("selectCard", blocker);
                final JsonObject mid = match.http.get("/game");
                match.http.record("GET /game#plannedBlock", mid);
                JsonObject entry = null;
                for (final JsonElement c : mid.getAsJsonArray("combat")) {
                    if (c.getAsJsonObject().get("attackerId").getAsInt() == attacker) {
                        entry = c.getAsJsonObject();
                    }
                }
                assertTrue(entry != null, "the attacker is still in combat: " + mid.get("combat"));
                assertTrue(entry.getAsJsonArray("blockers").toString().contains("#" + blocker + "\""), "the planned blocker is listed: " + entry);
                assertFalse(entry.get("blocked").getAsBoolean(), "the block is not final before OK: " + entry);
                match.act("ok", null);
                planned = true;
            } else {
                match.basics(g, "Forest", CREATURE, 2);
            }
        }
        // The split: wait for that combat to be over, then the host player took nothing from the trampler.
        final int turnOfSplit = splitTurn;
        if (match.http.get("/game").get("turn").getAsInt() == turnOfSplit) {
            final long until = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < until) {
                final JsonObject g = match.next();
                if (g.get("gameOver").getAsBoolean() || g.get("turn").getAsInt() != turnOfSplit || RiggedMatch.str(g.get("phase")).startsWith("MAIN2")
                        || RiggedMatch.str(g.get("phase")).equals("END_OF_TURN")) {
                    break;
                }
                match.basics(g, "Forest", "no spell", 99);
            }
        }
        assertEquals(match.hostLife(RiggedMatch.HOST), lifeBeforeSplit, "all the damage went to the blocker, none trampled over");
        match.finish();
    }

    private static Set<Integer> actionable(final JsonObject g) {
        final Set<Integer> ids = new HashSet<>();
        if (g.has("actionable")) {
            for (final JsonElement c : g.getAsJsonArray("actionable")) {
                ids.add(c.getAsJsonObject().get("id").getAsInt());
            }
        }
        return ids;
    }
}
