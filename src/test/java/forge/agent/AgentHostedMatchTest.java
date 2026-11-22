package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.server.ServerGameLobby;

/**
 * {@link MatchHost} with two bridge seats and no seat of its own. The first seat to join takes slot 0,
 * which a desktop host keeps for itself. Ports are picked free at run time.
 */
public class AgentHostedMatchTest {

    private static final String PRECON_FILE = "res/quest/precons/Azorius Advance.dck";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Hosted Match Test",
            "[Main]",
            "20 Mountain",
            "20 Goblin Piker");

    private MatchHost host;
    private ServerGameLobby lobby;
    private Thread loop;
    private int hostPort;
    private final List<AgentBridge> bridges = new ArrayList<>();

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        for (final AgentBridge b : bridges) {
            b.stop();
        }
        bridges.clear();
        if (host != null) {
            host.close();
            loop.join(5_000);
        }
    }

    @Test(timeOut = 300_000)
    public void twoSeatsPlayTwoMatchesWithNoHostSeat() throws Exception {
        host(true);
        final BridgeHttp first = seat();
        final BridgeHttp second = seat();

        JsonObject st = first.post("/sit", BridgeHttp.body("port", hostPort, "name", "FirstBot", "dck", DECK));
        assertEquals(st.getAsJsonObject("lobby").get("mySlot").getAsInt(), 0, "the first seat takes slot 0: " + st);
        st = second.post("/sit", BridgeHttp.body("port", hostPort, "name", "SecondBot", "dck", DECK));
        assertEquals(st.getAsJsonObject("lobby").get("mySlot").getAsInt(), 1, "the second seat takes slot 1: " + st);

        final JsonObject full = seat().postExpectingError("/sit", BridgeHttp.body("port", hostPort, "name", "LateBot"));
        assertTrue(full.get("error").getAsString().contains("gave no seat"), "a full lobby is an error: " + full);

        inGame(first, second);
        final List<String> names = new ArrayList<>();
        for (final Player p : lobby.getHostedMatch().getGame().getPlayers()) {
            names.add(p.getName());
        }
        assertEquals(names, List.of("FirstBot", "SecondBot"), "the game has the two seats and nobody else");
        endMatch(first, second);
        assertFalse(lobby.getSlot(0).isReady() || lobby.getSlot(1).isReady(), "the host unreadies both seats after a match");
        assertEquals(lobby.getSlot(0).getType(), LobbySlotType.REMOTE, "the seats stay in the lobby");

        first.post("/lobby/ready", BridgeHttp.body("ready", true));
        second.post("/lobby/ready", BridgeHttp.body("ready", true));
        inGame(first, second);
        endMatch(first, second);
    }

    @Test(timeOut = 300_000)
    public void aDeckTheLobbyRefusesIsNamedInChat() throws Exception {
        host(false);
        final BridgeHttp first = seat();
        final BridgeHttp second = seat();
        first.post("/sit", BridgeHttp.body("port", hostPort, "name", "FirstBot", "dck", DECK));
        second.post("/sit", BridgeHttp.body("port", hostPort, "name", "SecondBot", "deckFile", PRECON_FILE));

        AgentGameTest.waitFor("the host says in chat that the 40-card deck is not legal", 20_000,
                () -> chat(first).contains("FirstBot's deck Agent Hosted Match Test is not legal here"));
        Thread.sleep(4_000); // longer than the host's start delay
        assertTrue(lobby.getHostedMatch() == null, "no match with a refused deck");

        first.post("/lobby/deck", BridgeHttp.body("dck", DECK.replace("20 Goblin Piker", "4 Goblin Piker\n36 Mountain")));
        inGame(first, second);
        endMatch(first, second);
    }

    private void host(final boolean limited) throws Exception {
        hostPort = RiggedMatch.freePort();
        host = new MatchHost(hostPort, 2, limited);
        lobby = host.open();
        loop = new Thread(() -> {
            try {
                host.run();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MatchHost-Test");
        loop.setDaemon(true);
        loop.start();
    }

    private BridgeHttp seat() throws Exception {
        final AgentBridge bridge = new AgentBridge();
        bridge.start(0);
        bridges.add(bridge);
        return new BridgeHttp(bridge.port());
    }

    private static String chat(final BridgeHttp seat) throws Exception {
        final StringBuilder text = new StringBuilder();
        for (final JsonElement line : seat.get("/status").getAsJsonArray("chat")) {
            text.append(line.getAsJsonObject().get("text").getAsString()).append('\n');
        }
        return text.toString();
    }

    /** Waits until the host has filled the libraries, since a concession while it sets the game up breaks the host. */
    private void inGame(final BridgeHttp... seats) throws Exception {
        AgentGameTest.waitFor("the host sets up the game", 40_000, () -> {
            final HostedMatch match = lobby.getHostedMatch();
            final Game game = match == null ? null : match.getGame();
            if (game == null || game.getPlayers().size() < 2) {
                return false;
            }
            for (final Player p : game.getPlayers()) {
                if (p.getCardsIn(ZoneType.Library).isEmpty()) {
                    return false;
                }
            }
            return true;
        });
        Thread.sleep(500);
        for (final BridgeHttp seat : seats) {
            AgentGameTest.waitFor("a seat shows the first prompt of a game", 40_000, () -> {
                final JsonObject g = seat.get("/game");
                return g.get("inGame").getAsBoolean() && g.has("gameOver") && !g.get("gameOver").getAsBoolean()
                        && !g.get("prompt").getAsString().isEmpty();
            });
        }
    }

    /** The first seat concedes, both leave the match, and the host goes back to waiting. */
    private void endMatch(final BridgeHttp first, final BridgeHttp second) throws Exception {
        first.post("/game/act", BridgeHttp.body("action", "concede", "id", null));
        for (final BridgeHttp seat : List.of(first, second)) {
            AgentGameTest.waitFor("the game ends on the concession", 30_000, () -> seat.get("/game").get("gameOver").getAsBoolean());
        }
        // Seats that read the result before leaving take longer than the host's start delay
        Thread.sleep(5_000);
        assertTrue(first.get("/game").get("gameOver").getAsBoolean(), "the host starts no match while the seats are still in the finished one");
        first.post("/game/act", BridgeHttp.body("action", "quit", "id", null));
        second.post("/game/act", BridgeHttp.body("action", "quit", "id", null));
        AgentGameTest.waitFor("the host ends the match", 30_000, () -> lobby.getHostedMatch() == null);
    }
}
