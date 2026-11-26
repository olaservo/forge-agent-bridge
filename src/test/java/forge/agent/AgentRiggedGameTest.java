package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Hosts a Limited match in-process like {@link AgentGameTest}, with a deck built so one situation
 * comes up on the agent's first land drop: 24 Evolving Wilds and 16 basics. The agent sets its
 * avatar and its name in the lobby, plays a Wilds, activates it, and picks a basic from the library
 * search, which the bridge reports as {@code selection} and not as a pending decision. The test
 * stops there and concedes.
 */
public class AgentRiggedGameTest {

    private static final int BASICS = 16;
    private static final String WILDS = "Evolving Wilds";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Rigged Test",
            "[Main]",
            "24 " + WILDS,
            "8 Forest",
            "8 Mountain");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("rigged");
        }
    }

    @Test(timeOut = 300_000)
    public void agentSetsItsAvatarAndSearchesItsLibrary() throws Exception {
        // A host deck with no card that makes the seat search: a random one can, on the host's turn.
        match = RiggedMatch.inLobby(RiggedMatch.deck("Host bears", "20 Forest", "20 Grizzly Bears"));
        final BridgeHttp http = match.http;

        // The bridge and the host share one preferences file, so both seats start on the same avatar.
        final int avatar = match.lobby.getSlot(1).getAvatarIndex() == 17 ? 18 : 17;
        http.post("/lobby/avatar", BridgeHttp.body("index", avatar));
        AgentGameTest.waitFor("host sees the agent's avatar", 20_000, () -> match.lobby.getSlot(1).getAvatarIndex() == avatar);
        AgentGameTest.waitFor("the bridge reports the avatar on its own slot", 20_000, () -> slot(http.get("/status"), 1).get("avatar").getAsInt() == avatar);
        assertTrue(slot(http.get("/status"), 0).get("avatar").getAsInt() != avatar, "the host's avatar is unchanged");

        http.post("/lobby/name", BridgeHttp.body("name", "Renamed Bot"));
        AgentGameTest.waitFor("host sees the agent's new name", 20_000, () -> "Renamed Bot".equals(match.lobby.getSlot(1).getName()));
        AgentGameTest.waitFor("the bridge reports the new name", 20_000, () -> {
            final JsonObject status = http.get("/status");
            return "Renamed Bot".equals(slot(status, 1).get("name").getAsString())
                    && "Renamed Bot".equals(status.getAsJsonObject("connection").get("name").getAsString());
        });

        final JsonObject deck = match.start(DECK);
        assertEquals(deck.get("size").getAsInt(), 40, "deck text parsed: " + deck);

        final Set<String> decisionKinds = new LinkedHashSet<>();
        final Set<String> tried = new HashSet<>();
        int fetched = -1;
        long lastCardSelect = 0;
        final long deadline = System.currentTimeMillis() + 240_000;
        while (fetched < 0) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no library search in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended before the library search: " + g);
            final JsonObject buttons = g.getAsJsonObject("buttons");
            final boolean okEnabled = buttons.get("okEnabled").getAsBoolean();
            final JsonElement pd = g.get("pendingDecision");
            final JsonObject me = RiggedMatch.me(g);
            final boolean myMain = me != null && me.get("name").getAsString().equals(RiggedMatch.str(g.get("activePlayer")))
                    && RiggedMatch.str(g.get("phase")).startsWith("MAIN") && g.getAsJsonArray("stack").isEmpty();
            final boolean settled = System.currentTimeMillis() - lastCardSelect > 2_000;
            final JsonArray offered = librarySearch(g);
            final JsonObject inPlay = RiggedMatch.card(me, "battlefield", WILDS);
            final JsonObject inHand = RiggedMatch.card(me, "hand", WILDS);

            if (pd != null && !pd.isJsonNull()) {
                // Who plays first, if the host asks: the first option.
                final JsonObject d = pd.getAsJsonObject();
                decisionKinds.add(d.get("kind").getAsString());
                final JsonArray choices = new JsonArray();
                choices.add(0);
                http.post("/game/decide", BridgeHttp.body("id", d.get("id").getAsInt(), "choices", choices));
            } else if (offered != null) {
                if (!settled) {
                    continue;
                }
                http.record("GET /game#selection", g);
                final JsonObject sel = g.getAsJsonObject("selection");
                assertEquals(sel.get("max").getAsInt(), 1, "one card to find: " + sel);
                for (final JsonElement c : offered) {
                    final String name = c.getAsJsonObject().get("name").getAsString();
                    assertTrue("Forest".equals(name) || "Mountain".equals(name), "only basics are offered: " + sel);
                }
                assertEquals(offered.size(), BASICS - basicsOutsideLibrary(me), "every basic in the library is offered: " + g);
                fetched = offered.get(0).getAsJsonObject().get("id").getAsInt();
                match.act("selectCard", fetched);
            } else if (!AgentGameTest.iHavePriority(g) && g.get("prompt").getAsString().startsWith("Waiting for")) {
                // The other player is acting.
            } else if (okEnabled && myMain && settled && inPlay != null && tried.add("activate:" + inPlay.get("id").getAsInt())) {
                lastCardSelect = System.currentTimeMillis();
                match.act("selectCard", inPlay.get("id").getAsInt());
            } else if (okEnabled && myMain && settled && inHand != null && tried.add("land:" + g.get("turn").getAsInt())) {
                lastCardSelect = System.currentTimeMillis();
                match.act("selectCard", inHand.get("id").getAsInt());
            } else if (okEnabled) {
                match.act("ok", null);
            } else if (buttons.get("cancelEnabled").getAsBoolean()) {
                match.act("cancel", null);
            }
        }

        final int land = fetched;
        // The land is read in the state that first shows it, before a later untap step.
        final JsonObject[] arrived = new JsonObject[1];
        AgentGameTest.waitFor("the chosen basic is on the battlefield", 20_000, () -> {
            arrived[0] = http.get("/game");
            return card(RiggedMatch.me(arrived[0]), "battlefield", land) != null;
        });
        final JsonObject after = arrived[0];
        assertTrue(card(RiggedMatch.me(after), "battlefield", land).get("tapped").getAsBoolean(), "it entered tapped: " + after);
        AgentGameTest.waitFor("the search is over", 20_000, () -> !http.get("/game").has("selection"));
        System.out.println("[AgentRiggedGameTest] searched on turn " + after.get("turn") + ", decisions " + decisionKinds);
        match.finish();
    }

    private static JsonObject slot(final JsonObject status, final int index) {
        return status.getAsJsonObject("lobby").getAsJsonArray("slots").get(index).getAsJsonObject();
    }

    /** The cards a prompt offers, when they are in the library; other prompts can offer cards too. */
    private static JsonArray librarySearch(final JsonObject g) {
        if (!g.has("selection")) {
            return null;
        }
        final JsonArray cards = g.getAsJsonObject("selection").getAsJsonArray("cards");
        return !cards.isEmpty() && "Library".equals(RiggedMatch.str(cards.get(0).getAsJsonObject().get("zone"))) ? cards : null;
    }

    private static JsonObject card(final JsonObject me, final String zone, final int id) {
        if (me == null || !me.has(zone)) {
            return null;
        }
        for (final JsonElement c : me.getAsJsonArray(zone)) {
            if (c.getAsJsonObject().get("id").getAsInt() == id) {
                return c.getAsJsonObject();
            }
        }
        return null;
    }

    private static int basicsOutsideLibrary(final JsonObject me) {
        int n = 0;
        for (final String zone : new String[] { "hand", "battlefield", "graveyard" }) {
            for (final JsonElement c : me.getAsJsonArray(zone)) {
                if (!WILDS.equals(c.getAsJsonObject().get("name").getAsString())) {
                    n++;
                }
            }
        }
        return n;
    }
}
