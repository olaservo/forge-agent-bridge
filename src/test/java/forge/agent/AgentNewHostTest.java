package forge.agent;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.deck.Deck;
import forge.game.zone.ZoneType;

/**
 * The bridge's game follows the host's after a connection has ended. Both decks are 20 Forest and
 * 20 Grizzly Bears. In the first test the host stops with the game still on, and the bridge is
 * seated at a new host and plays again. In the second the connection drops mid-game and Forge's
 * client reconnects, then the seat disconnects and takes its seat back. After each, the host
 * sends its whole view again, as it does when a client reports a checksum mismatch. Each time the
 * host waits on the seat, the bridge's life totals, hand and battlefields are compared with the
 * host's own game. {@code inGame} is false while the connection is gone and after the seat quits.
 *
 * Without the bridge forgetting the game when its connection ends, the view from the old
 * connection was kept. After the host sent its view again the bridge showed a played land still
 * in hand, and every checksum the host sent after that failed.
 */
public class AgentNewHostTest {

    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent New Host Test",
            "[Main]",
            "20 Forest",
            "20 Grizzly Bears");

    private RiggedMatch match;
    private int actionable;

    private static Deck hostDeck() {
        return RiggedMatch.deck("Host bears", "20 Forest", "20 Grizzly Bears");
    }

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("newhost");
        }
    }

    @Test(timeOut = 600_000)
    public void theGameOnANewHostIsThatHostsGame() throws Exception {
        match = new RiggedMatch(DECK, hostDeck());
        playToTurn(4, null);
        match.killHost();
        AgentGameTest.waitFor("the bridge is out of the first game", 30_000, () -> !match.http.get("/game").get("inGame").getAsBoolean());
        assertTrue(match.http.get("/game").get("prompt").getAsString().isEmpty(), "the first game's prompt is gone");

        match.host(hostDeck());
        match.start(DECK);
        final List<String> differences = new ArrayList<>();
        int compared = playToTurn(3, differences);
        match.resync();
        compared += playToTurn(7, differences);
        assertTrue(compared > 5, "the second game was compared with its host: " + compared);
        assertTrue(differences.isEmpty(), differences.size() + " of " + compared + " comparisons differ: " + differences);
        assertTrue(actionable > 0, "the second game's prompts named cards that can act");

        match.finish();
        assertFalse(match.http.get("/game").get("inGame").getAsBoolean(), "out of the game after quitting");
    }

    @Test(timeOut = 600_000, dependsOnMethods = "theGameOnANewHostIsThatHostsGame", alwaysRun = true)
    public void theGameGoesOnAfterTheConnectionDrops() throws Exception {
        if (match != null) {
            match.close("newhost");
        }
        match = new RiggedMatch(DECK, hostDeck());
        final List<String> differences = new ArrayList<>();
        int compared = playToTurn(3, differences);

        // The host's end of the connection closes, and Forge's client reconnects by itself.
        match.dropConnection();
        AgentGameTest.waitFor("the bridge is out of the game while it reconnects", 10_000, () -> !match.http.get("/game").get("inGame").getAsBoolean());
        AgentGameTest.waitFor("the bridge is back in the game", 30_000, () -> {
            final JsonObject s = match.http.get("/status");
            return "CONNECTED".equals(s.getAsJsonObject("connection").get("reconnectState").getAsString())
                    && s.getAsJsonObject("game").get("inGame").getAsBoolean();
        });
        match.resync();
        compared += playToTurn(5, differences);

        // The seat leaves and takes its seat back.
        final JsonObject left = match.http.post("/disconnect", new JsonObject());
        assertFalse(left.getAsJsonObject("game").get("inGame").getAsBoolean(), "out of the game after disconnecting: " + left);
        match.http.post("/connect", BridgeHttp.body("host", "127.0.0.1", "port", match.hostPort, "name", RiggedMatch.ME));
        AgentGameTest.waitFor("the bridge has its seat back", 30_000, () -> match.http.get("/game").get("inGame").getAsBoolean());
        match.resync();
        compared += playToTurn(7, differences);

        assertTrue(compared > 5, "the game was compared with its host: " + compared);
        assertTrue(differences.isEmpty(), differences.size() + " of " + compared + " comparisons differ: " + differences);
        match.finish();
    }

    /** Plays until that turn. With a list, compares with the host each time it waits on the seat. Returns the comparisons made. */
    private int playToTurn(final int turn, final List<String> differences) throws Exception {
        int compared = 0;
        final long deadline = System.currentTimeMillis() + 200_000;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "turn " + turn + " not reached in time; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            if (g.get("turn").getAsInt() >= turn) {
                return compared;
            }
            if (differences != null && g.has("actionable")) {
                actionable++;
            }
            if (differences != null && match.hostWaitsOnSeat()) {
                compared++;
                final String diff = compare();
                if (diff != null) {
                    differences.add(diff);
                }
            }
            match.basics(match.http.get("/game"), "Forest", "Grizzly Bears", 2);
        }
    }

    /** The host is parked on the seat, so its game is not moving. The bridge's view gets a moment to catch up. */
    private String compare() throws Exception {
        String diff = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            final String host = hostState();
            final String mine = bridgeState(match.http.get("/game"));
            if (host.equals(mine)) {
                return null;
            }
            diff = "  host   " + host + "\n  bridge " + mine;
            Thread.sleep(500);
        }
        return diff;
    }

    private String hostState() {
        final Map<String, String> players = new TreeMap<>();
        for (final String name : List.of(RiggedMatch.ME, RiggedMatch.HOST)) {
            players.put(name, "life " + match.hostLife(name) + " hand " + match.hostIds(name, ZoneType.Hand).size()
                    + " field " + new TreeSet<>(match.hostIds(name, ZoneType.Battlefield)));
        }
        return players + " my hand " + new TreeSet<>(match.hostIds(RiggedMatch.ME, ZoneType.Hand));
    }

    private static String bridgeState(final JsonObject g) {
        final Map<String, String> players = new TreeMap<>();
        for (final JsonElement e : g.getAsJsonArray("players")) {
            final JsonObject p = e.getAsJsonObject();
            players.put(p.get("name").getAsString(), "life " + p.get("life").getAsInt() + " hand " + p.get("handSize").getAsInt()
                    + " field " + ids(p, "battlefield"));
        }
        // The game view arrives a moment before the host fills it in, and names no player as the seat's until then.
        final JsonObject me = RiggedMatch.me(g);
        return players + " my hand " + (me == null ? "not sent yet" : ids(me, "hand"));
    }

    private static TreeSet<Integer> ids(final JsonObject player, final String zone) {
        final TreeSet<Integer> out = new TreeSet<>();
        for (final JsonElement c : player.getAsJsonArray(zone)) {
            out.add(c.getAsJsonObject().get("id").getAsInt());
        }
        return out;
    }
}
