package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.deck.Deck;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.IBoosterDraft;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.event.DraftPickEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.gui.interfaces.IDraftEventHandler;
import forge.interfaces.IUpdateable;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.CardBlock;
import forge.model.FModel;

/**
 * Hosts a two-seat network booster draft in-process and lets the bridge take the second seat,
 * driven over its HTTP API like the MCP server does: pick through the draft until the pool
 * arrives, then build a 40-card deck from it as .dck text and submit it for the Limited lobby.
 */
public class AgentDraftTest {

    private static final int HOST_PORT = 36799;
    private FServerManager server;
    private ServerGameLobby lobby;
    private AgentBridge bridge;
    private BridgeHttp http;
    private final ExecutorService hostPicker = Executors.newSingleThreadExecutor();

    @BeforeClass
    public void initializeModel() {
        GuiBase.setInterface(new AgentGuiBase());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        FModel.getNetPreferences().setPref(FNetPref.UPnP, "NEVER");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (http != null) {
            http.saveFixtures("draft");
        }
        if (bridge != null) {
            bridge.stop();
        }
        if (server != null) {
            server.stopServer();
        }
        hostPicker.shutdownNow();
    }

    @Test(timeOut = 180_000)
    public void agentDraftsTheSecondSeat() throws Exception {
        server = FServerManager.getInstance();
        lobby = new ServerGameLobby();
        server.startServer(HOST_PORT);
        server.setLobby(lobby);
        lobby.setListener(new IUpdateable() {
            @Override
            public void update(final boolean fullUpdate) {
                server.updateLobbyState();
            }

            @Override
            public void update(final int slot, final LobbySlotType type) {
            }
        });

        // The host seat auto-picks the first card of every pack it sees.
        final AtomicReference<Deck> hostPool = new AtomicReference<>();
        server.setDraftHandler(new IDraftEventHandler() {
            @Override
            public void draftPackArrived(final int seatIndex, final List<PaperCard> pack, final int packNumber,
                    final int pickNumber, final int timerDurationSeconds) {
                hostPicker.execute(() -> lobby.handleDraftPick(new DraftPickEvent(seatIndex, pack.get(0)), -1));
            }

            @Override
            public void draftSeatPicked(final int seatIndex, final int[] seatQueueDepths) {
            }

            @Override
            public void draftAutoPicked(final int seatIndex, final PaperCard card, final int packNumber, final int pickInPack) {
            }

            @Override
            public void receiveEventPool(final String eventId, final Deck pool) {
                hostPool.set(pool);
            }
        });

        bridge = new AgentBridge();
        bridge.start(0);
        http = new BridgeHttp(bridge.port());

        final JsonObject before = http.get("/status");
        assertFalse(before.getAsJsonObject("connection").get("connected").getAsBoolean());
        http.post("/connect", BridgeHttp.body("host", "127.0.0.1", "port", HOST_PORT, "name", "AgentBot"));
        waitFor("agent takes a lobby slot", 20_000, () -> http.get("/status").getAsJsonObject("lobby").get("mySlot").getAsInt() >= 0);
        assertEquals(lobby.getNumberOfSlots(), 2);

        final CardBlock block = FModel.getBlocks().get("Magic 2011");
        assertNotNull(block, "Magic 2011 block in res/blockdata");
        final BoosterDraft draft = new BoosterDraft(LimitedPoolType.Block, 2) {
            {
                for (int i = 0; i < 3; i++) {
                    product.add(block.getBooster("M11"));
                }
            }
        };
        IBoosterDraft.LAND_SET_CODE[0] = block.getLandSet();
        IBoosterDraft.CUSTOM_RANKINGS_FILE[0] = null;

        lobby.setLimitedMode(true);
        lobby.createEvent(EventFormat.BOOSTER_DRAFT);
        assertTrue(lobby.configureEvent(LimitedPoolType.Block, draft, 0, 0));
        final ServerGameLobby.DraftStartResult start = lobby.startDraftEvent();
        assertNotNull(start);
        assertEquals(start.names().length, 2);

        int picks = 0;
        long seen = -1;
        final long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            final JsonObject status = http.get("/wait", Map.of("since", String.valueOf(seen), "timeout", "2"));
            seen = status.get("revision").getAsLong();
            final JsonObject d = status.getAsJsonObject("draft");
            if (d.get("poolReceived").getAsBoolean()) {
                break;
            }
            if (d.get("pickPending").getAsBoolean()) {
                final JsonObject pack = http.get("/draft/pack");
                assertTrue(pack.getAsJsonArray("pack").size() > 0, "pack has cards: " + pack);
                // The first pick goes by exact name, the rest by index, so both lookups are exercised.
                final String card = picks == 0 ? pack.getAsJsonArray("pack").get(0).getAsJsonObject().get("name").getAsString() : "0";
                final JsonObject picked = http.post("/draft/pick", BridgeHttp.body("card", card));
                assertNotNull(picked.getAsJsonObject("picked").get("name"));
                picks++;
            }
        }

        final JsonObject pool = http.get("/draft/pool");
        assertTrue(pool.get("poolReceived").getAsBoolean(), "agent received its pool: " + http.get("/status"));
        assertEquals(pool.get("size").getAsInt(), 45, "three 15-card packs");
        assertEquals(picks, 45);
        final JsonArray tags = pool.getAsJsonArray("tags");
        boolean hasEventId = false;
        for (final JsonElement t : tags) {
            hasEventId |= t.getAsString().startsWith("eventId");
        }
        assertTrue(hasEventId, "event tags: " + tags);
        assertEquals(http.get("/status").getAsJsonObject("draft").getAsJsonArray("picks").size(), 45);
        waitFor("host receives its pool", 10_000, () -> hostPool.get() != null);
        assertEquals(hostPool.get().getAllCardsInASinglePool().countAll(), 45);

        final JsonObject bad = http.postExpectingError("/draft/pick", BridgeHttp.body("card", "0"));
        assertTrue(bad.get("error").getAsString().contains("no pack"), "picking after the draft is an error: " + bad);

        // Deck from the pool as the drafter skill describes: no arguments takes the raw pool (45 cards in Sideboard) ...
        final JsonObject rawDeck = http.post("/lobby/deck", new JsonObject());
        assertEquals(rawDeck.get("size").getAsInt(), 0, "the raw pool has nothing in Main");
        // ... and .dck text builds the real 40-card deck: 23 pool cards plus 17 basic lands.
        final List<String> lines = new ArrayList<>(List.of("[metadata]", "Name=Agent Draft Deck", "[Main]"));
        int taken = 0;
        for (final JsonElement c : pool.getAsJsonArray("cards")) {
            if (taken == 23) {
                break;
            }
            final JsonObject card = c.getAsJsonObject();
            lines.add("1 " + card.get("name").getAsString() + "|" + card.get("set").getAsString());
            taken++;
        }
        lines.add("17 Plains|M11");
        final JsonObject deck = http.post("/lobby/deck", BridgeHttp.body("dck", String.join("\n", lines)));
        assertEquals(deck.get("size").getAsInt(), 40, "40-card deck from .dck text: " + deck);
        final JsonObject ready = http.post("/lobby/ready", BridgeHttp.body("ready", true));
        assertEquals(ready.get("mySlot").getAsInt(), 1);
        waitFor("host sees the deck and ready flag", 10_000, () -> lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);
        assertEquals(lobby.getSlot(1).getDeck().getMain().countAll(), 40);
        http.post("/chat", BridgeHttp.body("text", "thanks for the draft"));
        http.post("/disconnect", new JsonObject());
        assertFalse(http.get("/status").getAsJsonObject("connection").get("connected").getAsBoolean());
    }

    interface Check {
        boolean ok() throws Exception;
    }

    private static void waitFor(final String what, final long timeoutMillis, final Check cond) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!cond.ok()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(100);
        }
    }
}
