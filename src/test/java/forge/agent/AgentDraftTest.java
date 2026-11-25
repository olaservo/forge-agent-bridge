package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

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
 * arrives, then build a 40-card deck from it and submit it for the Limited lobby.
 */
public class AgentDraftTest {

    private static final int HOST_PORT = 36799;
    /** Long enough that no pick in the test runs out. */
    private static final int PICK_TIMER_SECONDS = 120;
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
        assertTrue(lobby.configureEvent(LimitedPoolType.Block, draft, PICK_TIMER_SECONDS, 0));
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
                // Picks count from 1 in each pack, and a pack of 15 has one card fewer at each pick.
                assertEquals(pack.get("pickNumber").getAsInt(), 16 - pack.get("packSize").getAsInt(), "pick number: " + pack);
                final int left = pack.get("secondsLeft").getAsInt();
                assertTrue(left > 0 && left <= PICK_TIMER_SECONDS, "seconds left on the pick clock: " + pack);
                final String at = pack.get("packNumber").getAsInt() + ":" + pack.get("pickNumber").getAsInt();
                assertEquals(pack.get("at").getAsString(), at, "the pack names itself: " + pack);
                if (picks == 2) {
                    // A pick that names an earlier pack is not sent, and the pack is still waiting.
                    final JsonObject stale = http.post("/draft/pick", BridgeHttp.body("card", "0", "at", "1:1"));
                    assertTrue(stale.get("stale").getAsBoolean(), "a pick from another pack is stale: " + stale);
                    assertEquals(stale.get("at").getAsString(), at);
                    assertFalse(stale.has("picked"), "nothing was picked: " + stale);
                    http.record("POST /draft/pick#stale", stale);
                    final JsonObject after = http.get("/draft/pack");
                    assertTrue(after.get("pickPending").getAsBoolean(), "the pack still waits: " + after);
                    assertEquals(after.getAsJsonArray("picks").size(), picks, "no pick was recorded: " + after);
                }
                // The first pick goes by exact name, the rest by index, so both lookups are exercised.
                final String card = picks == 0 ? pack.getAsJsonArray("pack").get(0).getAsJsonObject().get("name").getAsString() : "0";
                // Every second pick names its pack, and the others pick from whatever pack is waiting.
                final JsonObject picked = http.post("/draft/pick", picks % 2 == 0 ? BridgeHttp.body("card", card, "at", at) : BridgeHttp.body("card", card));
                assertNotNull(picked.getAsJsonObject("picked").get("name"));
                assertFalse(picked.has("stale"), "a pick from the waiting pack is made: " + picked);
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
        // With the pack named, the same pick is stale: no pack is waiting.
        final JsonObject late = http.post("/draft/pick", BridgeHttp.body("card", "0", "at", "3:15"));
        assertTrue(late.get("stale").getAsBoolean() && late.get("at").isJsonNull(), "a named pick after the draft is stale: " + late);
        assertTrue(http.get("/status").getAsJsonObject("draft").get("at").isJsonNull(), "no pack to name once the draft is over");

        assertEquals(pool.get("landSet").getAsString(), "M11");
        assertFalse(http.get("/status").getAsJsonObject("draft").has("secondsLeft"), "no clock once the draft is over");
        assertFalse(http.get("/status").getAsJsonObject("lobby").has("myDeck"), "no deck in the slot yet");

        // No arguments takes the raw pool (45 cards in Sideboard), which is not a deck ...
        final JsonObject rawDeck = http.post("/lobby/deck", new JsonObject());
        assertEquals(rawDeck.get("size").getAsInt(), 0, "the raw pool has nothing in Main");
        // ... and /draft/deck builds the real one: 23 pool cards plus 17 basic lands, the rest as sideboard.
        final JsonArray main = new JsonArray();
        for (final JsonElement c : pool.getAsJsonArray("cards")) {
            if (main.size() == 23) {
                break;
            }
            main.add(c.getAsJsonObject().get("name").getAsString());
        }
        final JsonObject lands = new JsonObject();
        lands.addProperty("Plains", 17);
        final JsonArray notMine = main.deepCopy();
        notMine.add("Black Lotus");
        final JsonObject refused = http.postExpectingError("/draft/deck", BridgeHttp.body("main", notMine, "lands", lands));
        assertTrue(refused.get("error").getAsString().contains("Black Lotus"), "a card outside the pool is refused: " + refused);
        final JsonObject deck = http.post("/draft/deck", BridgeHttp.body("main", main, "lands", lands));
        assertEquals(deck.get("size").getAsInt(), 40, "40-card deck from the pool: " + deck);
        assertEquals(deck.get("sideboardSize").getAsInt(), 22);
        assertTrue(deck.get("problem").isJsonNull(), "the deck is legal for Limited: " + deck);
        final JsonObject ready = http.post("/lobby/ready", BridgeHttp.body("ready", true));
        assertEquals(ready.get("mySlot").getAsInt(), 1);
        assertEquals(ready.getAsJsonObject("myDeck").get("mainSize").getAsInt(), 40);
        waitFor("host sees the deck and ready flag", 10_000, () -> lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);
        assertEquals(lobby.getSlot(1).getDeck().getMain().countAll(), 40);
        // A pack holds a basic land, so the 23 pool cards can include Plains of their own.
        int draftedPlains = 0;
        for (final JsonElement c : main) {
            if ("Plains".equals(c.getAsString())) {
                draftedPlains++;
            }
        }
        assertEquals(lobby.getSlot(1).getDeck().getMain().countAll(pc -> pc.getName().equals("Plains")), 17 + draftedPlains,
                "the basic lands reached the host");
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
