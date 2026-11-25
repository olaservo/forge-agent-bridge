package forge.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.match.Selection;
import forge.gamemodes.match.YieldUpdate;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.DraftPickEvent;
import forge.gamemodes.net.event.HeartbeatEvent;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gui.interfaces.IDraftEventHandler;
import forge.interfaces.IGameController;
import forge.interfaces.ILobbyListener;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableTypes;
import forge.util.Localizer;

/**
 * One headless Forge client: connection, lobby, draft and game state, plus the actions the bridge
 * exposes. Every incoming event bumps {@link #revision} so callers can long-poll for changes.
 */
final class AgentSession implements ILobbyListener, IDraftEventHandler {

    private final AtomicLong revision = new AtomicLong();
    private final Object changeLock = new Object();

    private final AgentGuiGame gui = new AgentGuiGame(this::changed, this::reconnectStateChanged);
    private final ClientGameLobby lobby = new ClientGameLobby();
    private volatile FGameClient client;
    private volatile String host;
    private volatile int port;
    private volatile String name;
    private volatile boolean connected;
    private volatile String closeReason;
    /**
     * Forge's client sends a heartbeat only after 15 s without writing, and closes the connection after 45 s
     * without reading. The host answers each heartbeat. A seat that writes more often than that to a quiet
     * host gets nothing to read, so the bridge sends heartbeats on a timer of its own.
     */
    private static final long HEARTBEAT_MILLIS = 10_000;
    private ScheduledExecutorService heartbeat;

    private volatile GameLobbyData lobbyData;
    private volatile int mySlot = -1;

    /** A chat line. {@code from} is the player who said it, or null for a line from the host itself. */
    record ChatLine(String from, String text) {
    }

    final List<ChatLine> chat = new CopyOnWriteArrayList<>();

    // draft
    /** Replaced under {@code draftLock}: draft events arrive on the network thread and a pick on an HTTP thread. */
    private volatile DraftState draft = DraftState.NONE;
    private final Object draftLock = new Object();
    /** The deck last sent to the host for our slot. The host does not send a seat's deck back. */
    private volatile Deck lobbyDeck;
    final List<String> picks = new CopyOnWriteArrayList<>();
    final List<String> autoPicks = new CopyOnWriteArrayList<>();

    /**
     * When the host passes priority for our seat because it has no available actions. Forge's check misses
     * casts and abilities from the graveyard, so the default is opponents' turns only. See the README.
     */
    static final String AUTO_PASS_OFF = "off";
    static final String AUTO_PASS_OPPONENT_TURNS = "opponent-turns";
    static final String AUTO_PASS_ALWAYS = "always";
    private volatile String autoPass = AUTO_PASS_OPPONENT_TURNS;
    /** The setting last sent to the host, and the game it was sent for. */
    private volatile boolean yieldSent;
    private volatile int yieldSentForGame;
    /** The game for which the host was asked to list actionable cards. */
    private volatile int highlightsSentForGame;
    /** When that was, and how long the host is given to take it up before an empty list is believed. */
    private volatile long highlightsSentAt;
    private static final long HIGHLIGHTS_SETTLE_MILLIS = 2000;
    /** The count of the last prompt the bridge answered for the seat. */
    private final AtomicInteger answered = new AtomicInteger(-1);
    private static final ExecutorService ANSWERS = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Agent-Answers");
        t.setDaemon(true);
        return t;
    });

    {
        gui.answersItself = this::answersItself;
        gui.lobbyDeck = () -> lobbyDeck;
    }

    /** The game for which the host was sent the seat's stops. */
    private volatile int seededForGame;
    /**
     * Yield settings the bridge relies on, sent after the stops. The seed carries this machine's Forge
     * preferences for them, and without a seed the host uses its own.
     */
    private static final Map<FPref, String> YIELD_SETTINGS = yieldSettings();

    private static Map<FPref, String> yieldSettings() {
        final Map<FPref, String> m = new LinkedHashMap<>();
        // What ends a yield until the stack is empty or until end of turn.
        m.put(FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL, "true");
        m.put(FPref.YIELD_INTERRUPT_ON_ATTACKERS, "true");
        m.put(FPref.YIELD_INTERRUPT_ON_TARGETING, "false");
        m.put(FPref.YIELD_INTERRUPT_ON_TRIGGERS, "false");
        m.put(FPref.YIELD_INTERRUPT_ON_REVEAL, "false");
        m.put(FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL, "false");
        m.put(FPref.YIELD_AUTO_PASS_RESPECTS_INTERRUPTS, "false");
        // Anything else makes the host offer a yield with Accept and Decline buttons in place of the priority prompt.
        m.put(FPref.YIELD_DECLINE_SCOPE_STACK_YIELD, "NEVER");
        m.put(FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS, "NEVER");
        return m;
    }
    /** The turn of ours on which we pressed End Turn, or -1. */
    private volatile int endedTurn = -1;
    /** The last turn seen in a phase before its end step, and whether it was ours. Read and written under {@code yieldLock}. */
    private int turnSeen = -1;
    private boolean turnSeenIsOurs;

    /**
     * The host sends its prompt and button labels as text in its own language, and Forge's clients tell them
     * apart by comparing with their own translation. These match a host that runs in the bridge's language.
     */
    private static String endTurnLabel() {
        return Localizer.getInstance().getMessage("lblEndTurn");
    }

    private static String priorityPrefix() {
        return Localizer.getInstance().getMessage("lblPriority") + ": ";
    }

    private static final long SEAT_WAIT_MILLIS = 10_000;
    private static final long CANCEL_BEFORE_CONCEDE_MILLIS = 1000;
    private static final long ACT_FIRST_REACTION_MILLIS = 1500;
    private static final long ACT_QUIET_MILLIS = 400;
    private static final long ACT_SETTLE_MILLIS = 3000;
    /** No action is sent this soon after switching auto-pass on. */
    private static final long YIELD_ON_GAP_MILLIS = 750;
    private final Object actLock = new Object();
    private final Object yieldLock = new Object();
    private volatile long lastYieldOnAt;
    /** The thread that is sending an action to the host, or null. */
    private volatile Thread actingThread;

    long revision() {
        return revision.get();
    }

    /**
     * The opponent's end step with an empty stack and no card the host lists as playable. Auto-pass is off by
     * then, so that the setting has reached the host before the seat's own main phase, and the host asks. The
     * host fills that list and its "no available actions" flag from one scan and sends the list before the
     * prompt, so an empty list is what auto-pass would have passed on.
     */
    private boolean answersItself(final AgentGuiGame.Prompt p) {
        if (!AUTO_PASS_OPPONENT_TURNS.equals(autoPass) || gui.gamesOpened != highlightsSentForGame
                || System.currentTimeMillis() - highlightsSentAt < HIGHLIGHTS_SETTLE_MILLIS) {
            return false;
        }
        final GameView gv = gui.getGameView();
        final PlayerView me = gui.getCurrentPlayer();
        if (gv == null || me == null || gv.getPlayerTurn() == null || gv.getPlayerTurn().getId() == me.getId()) {
            return false;
        }
        if (gv.getPhase() != PhaseType.END_OF_TURN || gv.peekStack() != null || !p.message().startsWith(priorityPrefix())) {
            return false;
        }
        final Selection sel = gui.getSelection();
        return sel != null && sel.actionable().isEmpty();
    }

    /** Presses OK on the prompt the bridge answers for the seat, unless the prompt has changed. */
    private void answer(final int count) {
        synchronized (actLock) {
            final AgentGuiGame.Prompt p = gui.prompt;
            if (p.count() != count || p.waiting() || !p.okEnabled() || gui.answering != count) {
                return;
            }
            actingThread = Thread.currentThread();
            try {
                controller().selectButtonOk();
            } catch (final RuntimeException e) {
                System.err.println("[agent] could not pass the opponent's end step: " + e);
            } finally {
                actingThread = null;
            }
        }
    }

    private void changed() {
        sendYieldPrefs(false);
        final int ready = gui.answerReady;
        if (ready >= 0 && ready == gui.answering && answered.getAndSet(ready) != ready) {
            ANSWERS.execute(() -> answer(ready));
        }
        revision.incrementAndGet();
        synchronized (changeLock) {
            changeLock.notifyAll();
        }
    }

    /** Blocks until the revision passes {@code since} or the timeout elapses; returns the current revision. */
    long awaitChange(final long since, final long timeoutMillis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (changeLock) {
            while (revision.get() <= since) {
                final long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                changeLock.wait(left);
            }
        }
        return revision.get();
    }

    // ---- connection ----

    synchronized void connect(final String host0, final int port0, final String name0) {
        if (client != null) {
            disconnect();
        }
        host = host0;
        port = port0;
        name = name0;
        closeReason = null;
        final FGameClient c = new FGameClient(name0, gui, host0, port0);
        c.addLobbyListener(this);
        c.setDraftHandler(this);
        gui.setClientLobby(lobby);
        c.connect();
        client = c;
        connected = true;
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "Agent-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleWithFixedDelay(() -> sendHeartbeat(c), HEARTBEAT_MILLIS, HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
        changed();
    }

    private void sendHeartbeat(final FGameClient c) {
        try {
            if (connected && c.getReconnectState() == FGameClient.ReconnectState.CONNECTED) {
                c.send(new HeartbeatEvent());
            }
        } catch (final RuntimeException e) {
            System.err.println("[agent] could not send a heartbeat: " + e);
        }
    }

    synchronized void disconnect() {
        final FGameClient c = client;
        client = null;
        connected = false;
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
        if (c != null) {
            c.close();
        }
        gui.leaveGame();
        lobbyData = null;
        mySlot = -1;
        lobbyDeck = null;
        changed();
    }

    private FGameClient requireClient() {
        final FGameClient c = client;
        if (c == null) {
            throw new IllegalStateException("not connected");
        }
        return c;
    }

    /** True for a connection we hold, and for one Forge's client is still retrying. */
    boolean seatAlive() {
        final FGameClient c = client;
        if (c == null || !connected) {
            return false;
        }
        final FGameClient.ReconnectState state = c.getReconnectState();
        return state != FGameClient.ReconnectState.FAILED && state != FGameClient.ReconnectState.SEAT_LOST;
    }

    /**
     * Connects, waits for the host to give a slot and, with a deck, sends it and readies. Without a deck the
     * seat is left unready.
     */
    synchronized Map<String, Object> sit(final String host0, final int port0, final String name0, final Deck deck)
            throws InterruptedException {
        connect(host0, port0, name0);
        awaitUntil(() -> mySlot >= 0 || !connected, SEAT_WAIT_MILLIS);
        if (mySlot < 0 || !connected) {
            disconnect();
            throw new IllegalStateException("the host at " + host0 + ":" + port0
                    + " gave no seat; its lobby is full or a match is running");
        }
        if (deck != null) {
            // The host starts only when it has seen both, and it reads them in the order sent
            setDeck(deck);
            setReady(true);
            awaitUntil(this::readyOnHost, SEAT_WAIT_MILLIS);
        }
        return status();
    }

    private boolean readyOnHost() {
        final int slot = mySlot;
        return lobbyData != null && slot >= 0 && slot < lobby.getNumberOfSlots() && lobby.getSlot(slot).isReady();
    }

    private void awaitUntil(final BooleanSupplier done, final long timeoutMillis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            final long seen = revision.get();
            final long left = deadline - System.currentTimeMillis();
            if (done.getAsBoolean() || left <= 0) {
                return;
            }
            awaitChange(seen, left);
        }
    }

    // ---- ILobbyListener ----

    @Override
    public void message(final String source, final String message, final ChatMessage.MessageType type) {
        chat.add(new ChatLine(source, message));
        changed();
    }

    @Override
    public void update(final GameLobbyData state, final int slot) {
        lobbyData = state;
        mySlot = slot;
        lobby.setLocalPlayer(slot);
        lobby.setData(state);
        changed();
    }

    @Override
    public void close() {
        gui.leaveGame();
        lost("connection closed by host or network");
    }

    /** Forge's client tells its lobby listeners nothing when it stops trying to reconnect. */
    private void reconnectStateChanged(final FGameClient.ReconnectState state) {
        if (state == FGameClient.ReconnectState.FAILED) {
            lost("the host could not be reached again");
        } else if (state == FGameClient.ReconnectState.SEAT_LOST) {
            lost("the host no longer holds this seat");
        }
    }

    /** The connection has ended and Forge's client is not retrying. The lobby it showed goes with it. */
    private void lost(final String reason) {
        if (!connected) {
            return;
        }
        connected = false;
        closeReason = reason;
        lobbyData = null;
        mySlot = -1;
        lobbyDeck = null;
        changed();
    }

    @Override
    public ClientGameLobby getLobby() {
        return lobby;
    }

    // ---- lobby actions ----

    void setReady(final boolean ready) {
        requireClient().send(UpdateLobbyPlayerEvent.isReadyUpdate(ready));
    }

    void setAvatar(final int index) {
        requireClient().send(UpdateLobbyPlayerEvent.avatarUpdate(index));
    }

    void setName(final String newName) {
        requireClient().send(UpdateLobbyPlayerEvent.nameUpdate(newName));
        name = newName;
        changed();
    }

    void setDeck(final Deck deck) {
        requireClient().send(UpdateLobbyPlayerEvent.deckUpdate(deck));
        lobbyDeck = deck;
        changed();
    }

    void sendChat(final String text) {
        requireClient().send(new MessageEvent(text));
    }

    // ---- IDraftEventHandler ----

    @Override
    public void draftPackArrived(final int seat, final List<PaperCard> pack, final int packNo, final int pickNo, final int timer) {
        synchronized (draftLock) {
            if (draft.pool() != null) {
                startEvent();
            }
            // The host counts picks from 0 here and from 1 in its auto-pick notice. The bridge counts from 1.
            draft = draft.packArrived(seat, pack, packNo, pickNo + 1, timer);
        }
        changed();
    }

    @Override
    public void draftSeatPicked(final int seat, final int[] queueDepths) {
        synchronized (draftLock) {
            draft = draft.queueDepths(queueDepths);
        }
        changed();
    }

    @Override
    public void draftAutoPicked(final int seat, final PaperCard card, final int packNo, final int pickInPack) {
        autoPicks.add(card.getName() + " (pack " + packNo + " pick " + pickInPack + ")");
        picks.add(card.getName());
        synchronized (draftLock) {
            draft = draft.picked();
        }
        changed();
    }

    @Override
    public void receiveEventPool(final String eventId, final Deck deck) {
        String path;
        try {
            FModel.getDecks().getNetworkEventDecks().add(deck);
            path = new File(ForgeConstants.DECK_NET_EVENT_DIR, deck.getName() + ".dck").getAbsolutePath();
        } catch (final RuntimeException e) {
            path = "not saved: " + e;
        }
        synchronized (draftLock) {
            if (draft.pool() != null) {
                // A second pool with no pack in between is a sealed event after an earlier event.
                startEvent();
            }
            draft = draft.poolReceived(deck, eventId, path);
        }
        // The deck in the slot, if any, was for an earlier match.
        lobbyDeck = null;
        changed();
    }

    /** Forgets the picks of the event before this one. */
    private void startEvent() {
        picks.clear();
        autoPicks.clear();
    }

    /**
     * Builds the deck for the match from the delivered pool, sends it to the host and saves it over the pool file,
     * as the desktop's deck editor does. {@code main} names pool cards, one entry per copy. {@code lands} adds
     * basic lands, which a Limited deck may take in any number. Cards left in the pool become the sideboard.
     */
    synchronized Map<String, Object> buildDeck(final List<String> main, final Map<String, Integer> lands, final String landSet) {
        final Deck pool = draft.pool();
        if (pool == null) {
            throw new IllegalStateException("no pool yet: the draft has not finished");
        }
        final List<PaperCard> left = new ArrayList<>(pool.getAllCardsInASinglePool().toFlatList());
        final Deck deck = new Deck(pool.getName());
        deck.getTags().addAll(pool.getTags());
        final List<String> missing = new ArrayList<>();
        for (final String wanted : main) {
            PaperCard found = null;
            for (final PaperCard pc : left) {
                if (pc.getName().equalsIgnoreCase(wanted.trim())) {
                    found = pc;
                    break;
                }
            }
            if (found == null) {
                missing.add(wanted);
            } else {
                left.remove(found);
                deck.getMain().add(found);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("not in the pool, or more copies than the pool has: " + String.join(", ", missing));
        }
        final String set = landSet != null ? landSet : Snapshots.commonSet(pool);
        int landCount = 0;
        for (final Map.Entry<String, Integer> e : lands.entrySet()) {
            PaperCard land = set == null ? null : FModel.getMagicDb().getCommonCards().getCard(e.getKey(), set);
            if (land == null) {
                land = FModel.getMagicDb().getCommonCards().getCard(e.getKey());
            }
            if (land == null || !land.getRules().getType().isBasicLand()) {
                throw new IllegalArgumentException("'" + e.getKey() + "' is not a basic land");
            }
            if (e.getValue() > 0) {
                deck.getMain().add(land, e.getValue());
                landCount += e.getValue();
            }
        }
        if (!left.isEmpty()) {
            deck.getOrCreate(DeckSection.Sideboard).addAllFlat(left);
        }
        setDeck(deck);
        String path;
        try {
            FModel.getDecks().getNetworkEventDecks().add(deck);
            path = new File(ForgeConstants.DECK_NET_EVENT_DIR, deck.getName() + ".dck").getAbsolutePath();
        } catch (final RuntimeException e) {
            path = "not saved: " + e;
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("deck", deck.getName());
        out.put("size", deck.getMain().countAll());
        out.put("lands", landCount);
        out.put("sideboardSize", left.size());
        out.put("path", path);
        out.put("problem", DeckFormat.Limited.getDeckConformanceProblem(deck));
        return out;
    }

    /** Picks a card from the current pack by exact name or 0-based index string. */
    synchronized Map<String, Object> pick(final String card) {
        final DraftState d = draft;
        final List<PaperCard> pack = d.pack();
        if (pack == null || !d.pickPending()) {
            throw new IllegalStateException("no pack waiting for a pick");
        }
        PaperCard chosen = null;
        try {
            final int idx = Integer.parseInt(card.trim());
            if (idx >= 0 && idx < pack.size()) {
                chosen = pack.get(idx);
            }
        } catch (final NumberFormatException ignored) {
            for (final PaperCard pc : pack) {
                if (pc.getName().equalsIgnoreCase(card.trim())) {
                    chosen = pc;
                    break;
                }
            }
        }
        if (chosen == null) {
            throw new IllegalArgumentException("'" + card + "' is not in the current pack");
        }
        requireClient().send(new DraftPickEvent(d.seatIndex(), chosen));
        picks.add(chosen.getName());
        synchronized (draftLock) {
            // The next pack can arrive before this line. It is then the one waiting for a pick.
            if (draft.pack() == pack) {
                draft = draft.picked();
            }
        }
        changed();
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("picked", Snapshots.describe(chosen));
        out.put("packNumber", d.packNumber());
        out.put("pickNumber", d.pickNumber());
        return out;
    }

    // ---- game actions ----

    /**
     * Whether the host should be auto-passing for our seat right now, given the mode and whose turn it is.
     * {@code current} is what the host was last told for this game.
     */
    private boolean wantAutoPass(final boolean current) {
        if (AUTO_PASS_ALWAYS.equals(autoPass)) {
            return true;
        }
        final GameView gv = gui.getGameView();
        final PlayerView me = gui.getCurrentPlayer();
        if (AUTO_PASS_OFF.equals(autoPass) || gv == null || me == null || gv.getPlayerTurn() == null) {
            return false;
        }
        final PhaseType phase = gv.getPhase();
        final int turn = gv.getTurn();
        final boolean ending = phase == PhaseType.END_OF_TURN || phase == PhaseType.CLEANUP;
        if (phase != null && !ending) {
            turnSeen = turn;
            turnSeenIsOurs = gv.getPlayerTurn().getId() == me.getId();
        } else if (turn != turnSeen) {
            // The host sends the turn and its player before the phase, so as a turn begins the view still
            // shows the last turn's cleanup step. Nothing changes until the new turn's first phase is here.
            return current;
        }
        if (turnSeenIsOurs) {
            // On from End Turn or our cleanup step, so it is in place by the opponent's upkeep.
            return phase == PhaseType.CLEANUP || turn == endedTurn;
        }
        // Switch off before the opponent's turn ends, so the setting has reached the host by our main phase.
        return !ending;
    }

    /**
     * Sends the host our auto-pass setting when it differs from what was last sent for this game. The host
     * keeps it per game. Called on every change, so it follows the turn.
     */
    private void sendYieldPrefs(final boolean force) {
        // Called from the Agent-EDT thread, the network thread and HTTP threads. One at a time, so the host
        // receives the settings in the order they were decided.
        synchronized (yieldLock) {
            sendYieldPrefsLocked(force);
        }
    }

    private void sendYieldPrefsLocked(final boolean force) {
        final int game = gui.gamesOpened;
        final IGameController c = gui.getGameController();
        if (c == null || game == 0) {
            return;
        }
        if (game != yieldSentForGame) {
            endedTurn = -1;
            turnSeen = -1;
        }
        if (game != seededForGame) {
            final Thread acting = actingThread;
            if (acting != null && acting != Thread.currentThread()) {
                return; // an action is on its way to the host; the next change will try again
            }
            // First, since the seed replaces every setting the host holds for the seat.
            if (!gui.seedStops()) {
                return;
            }
            seededForGame = game;
            lastYieldOnAt = System.currentTimeMillis();
            try {
                for (final Map.Entry<FPref, String> e : YIELD_SETTINGS.entrySet()) {
                    c.setYieldPref(e.getKey(), e.getValue());
                }
            } catch (final RuntimeException e) {
                System.err.println("[agent] could not send the yield settings: " + e);
            }
        }
        if (game != highlightsSentForGame) {
            highlightsSentForGame = game;
            highlightsSentAt = System.currentTimeMillis();
            try {
                // The host fills Selection.actionable only for a seat with this setting on.
                c.setYieldPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, "true");
            } catch (final RuntimeException e) {
                System.err.println("[agent] could not ask for actionable cards: " + e);
            }
        }
        final boolean want = wantAutoPass(game == yieldSentForGame && yieldSent);
        if (!force && game == yieldSentForGame && want == yieldSent) {
            return;
        }
        final Thread sender = actingThread;
        if (want && sender != null && sender != Thread.currentThread()) {
            return; // an action is on its way to the host; the next change will try again
        }
        yieldSentForGame = game;
        yieldSent = want;
        if (want) {
            lastYieldOnAt = System.currentTimeMillis();
        }
        try {
            c.setYieldPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, String.valueOf(want));
        } catch (final RuntimeException e) {
            System.err.println("[agent] could not send the auto-pass setting: " + e);
        }
    }

    /** The stops the bridge starts with. */
    void setStops(final String mode) {
        if (AgentGuiGame.STOPS_ALL.equals(mode)) {
            gui.ownStops = AgentGuiGame.STOPPABLE;
            gui.opponentStops = AgentGuiGame.STOPPABLE;
        } else if (AgentGuiGame.STOPS_DEFAULT.equals(mode)) {
            gui.ownStops = AgentGuiGame.OWN_STOPS;
            gui.opponentStops = AgentGuiGame.OPPONENT_STOPS;
        } else {
            throw new IllegalArgumentException("stops must be default or all");
        }
    }

    /**
     * Sets where the host stops for the seat on an empty stack. A null set is left as it is. In a game each
     * change goes to the host as the desktop sends a click on a phase label, and the host then passes a
     * priority prompt the seat has up in a step that is no longer a stop. The sets stay for later games.
     */
    Map<String, Object> setStops(final Set<PhaseType> own, final Set<PhaseType> opponent) throws InterruptedException {
        for (final Set<PhaseType> set : Arrays.asList(own, opponent)) {
            if (set != null && !AgentGuiGame.STOPPABLE.containsAll(set)) {
                throw new IllegalArgumentException("UNTAP is not a stop: no player gets priority there");
            }
        }
        if (own != null && !own.contains(PhaseType.MAIN1)) {
            throw new IllegalArgumentException("own needs MAIN1: without it the seat is not asked in its first main phase "
                    + "and cannot play a land or cast a spell before combat");
        }
        synchronized (actLock) {
            final Set<PhaseType> ownBefore = gui.ownStops;
            final Set<PhaseType> opponentBefore = gui.opponentStops;
            if (own != null) {
                gui.ownStops = Collections.unmodifiableSet(EnumSet.copyOf(own));
            }
            if (opponent != null) {
                gui.opponentStops = Collections.unmodifiableSet(opponent.isEmpty() ? EnumSet.noneOf(PhaseType.class) : EnumSet.copyOf(opponent));
            }
            final GameView gv = gui.getGameView();
            final PlayerView me = gui.getCurrentPlayer();
            final IGameController c = gui.getGameController();
            // Before the seed the host has nothing to change, and the seed carries the new sets.
            if (gv != null && gv.getPlayers() != null && me != null && c != null && seededForGame == gui.gamesOpened && !gui.gameFinished) {
                final long seen = revision.get();
                for (final PlayerView p : gv.getPlayers()) {
                    final boolean mine = p.getId() == me.getId();
                    final Set<PhaseType> before = mine ? ownBefore : opponentBefore;
                    final Set<PhaseType> now = mine ? gui.ownStops : gui.opponentStops;
                    for (final PhaseType phase : AgentGuiGame.STOPPABLE) {
                        if (before.contains(phase) != now.contains(phase)) {
                            c.sendYieldUpdate(new YieldUpdate.SkipPhase(p, phase, !now.contains(phase)));
                        }
                    }
                }
                settle(seen);
            }
        }
        return stopsSnapshot();
    }

    Map<String, Object> stopsSnapshot() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("stops", gui.stops());
        m.put("own", phaseNames(gui.ownStops));
        m.put("opponent", phaseNames(gui.opponentStops));
        return m;
    }

    private static List<String> phaseNames(final Set<PhaseType> set) {
        final List<String> names = new ArrayList<>();
        for (final PhaseType phase : PhaseType.values()) {
            if (set.contains(phase)) {
                names.add(phase.name());
            }
        }
        return names;
    }

    void setAutoPass(final String mode) {
        if (!AUTO_PASS_OFF.equals(mode) && !AUTO_PASS_OPPONENT_TURNS.equals(mode) && !AUTO_PASS_ALWAYS.equals(mode)) {
            throw new IllegalArgumentException("autoPass must be off, opponent-turns or always");
        }
        autoPass = mode;
        sendYieldPrefs(true);
        changed();
    }

    private IGameController controller() {
        final IGameController c = gui.getGameController();
        if (c == null) {
            throw new IllegalStateException("no game in progress");
        }
        return c;
    }

    private CardView card(final int id) {
        final GameView gv = gui.getGameView();
        if (gv == null || gv.getTracker() == null) {
            throw new IllegalStateException("no game view");
        }
        final CardView cv = gv.getTracker().getObj(TrackableTypes.CardViewType, id);
        if (cv == null) {
            throw new IllegalArgumentException("no card with id " + id);
        }
        return cv;
    }

    /** A prompt that offers stack entries and no card. The host ignores a card click there and a network client is not told. */
    private void stackPromptTakesNoCard() {
        final Selection sel = gui.getSelection();
        if (sel != null && sel.cards().isEmpty() && sel.offersStackItems()) {
            throw new IllegalArgumentException("this prompt takes a spell or ability on the stack: use selectStack with an id from selection.stack");
        }
    }

    /**
     * A spell or ability on the stack that the prompt offers as a target. The host asks a network client to
     * pick these in its stack view, and tells them apart by the id of the stack entry.
     */
    private StackItemView stackItem(final int id) {
        final Selection sel = gui.getSelection();
        if (sel != null) {
            for (final TrackableObject o : sel.targets()) {
                if (o instanceof StackItemView && o.getId() == id) {
                    return (StackItemView) o;
                }
            }
        }
        throw new IllegalArgumentException("the prompt does not offer stack item " + id + "; selection.stack lists the ones it does");
    }

    private PlayerView player(final int id) {
        final GameView gv = gui.getGameView();
        if (gv == null || gv.getTracker() == null) {
            throw new IllegalStateException("no game view");
        }
        final PlayerView pv = gv.getTracker().getObj(TrackableTypes.PlayerViewType, id);
        if (pv == null) {
            throw new IllegalArgumentException("no player with id " + id);
        }
        return pv;
    }

    /**
     * Sends one controller call and returns once the host has reacted and gone quiet. The host runs each
     * call on its own thread, so two sent back to back can overlap there. Waiting spaces them like clicks.
     */
    boolean act(final String action, final Integer id, final Integer color, final Act opts) throws InterruptedException {
        synchronized (actLock) {
            // Let the host finish anything a just-sent "auto-pass on" started before we resume its game thread.
            final long clear = lastYieldOnAt + YIELD_ON_GAP_MILLIS - System.currentTimeMillis();
            if (clear > 0) {
                Thread.sleep(clear);
            }
            if (opts.at() != null && opts.at() != gui.prompt.count()) {
                return false;
            }
            long seen = revision.get();
            actingThread = Thread.currentThread();
            try {
                send(action, id, color, opts);
            } finally {
                actingThread = null;
            }
            settle(seen);
            final AgentGuiGame.Prompt pay = gui.prompt;
            if (opts.autoPay() && !pay.waiting() && pay.okEnabled() && autoLabel().equals(pay.okLabel())) {
                seen = revision.get();
                controller().selectButtonOk();
                settle(seen);
            }
            final boolean click = !"ok".equals(action) && !"endTurn".equals(action);
            if (click && opts.endTurn() && ownItemOnTop()) {
                // The action cast a spell or activated an ability: the rest of the turn passes with it. Only with
                // the seat's own item on the stack, so a card the host refused does not end the turn.
                seen = revision.get();
                actingThread = Thread.currentThread();
                try {
                    send("endTurn", null, null, Act.PLAIN);
                } finally {
                    actingThread = null;
                }
                settle(seen);
            } else if (opts.pass() && ownItemOnTop()) {
                seen = revision.get();
                controller().sendYieldUpdate(new YieldUpdate.StackYield(gui.getCurrentPlayer(), true, true));
                settle(seen);
            }
            return true;
        }
    }

    /**
     * How an action is sent. {@code once}: OK passes priority one time, where it would yield until the stack
     * is empty. {@code endTurn}: with OK, also yield until end of turn; with another action, yield until end of
     * turn if it put the seat's own spell or ability on the stack. {@code autoPay}: press Auto if the
     * action leads to the prompt that pays a cost. {@code pass}: afterwards, pass priority if the seat's own
     * spell or ability is on top of the stack. {@code at}: send only while this is the prompt count.
     */
    record Act(boolean once, boolean endTurn, boolean autoPay, boolean pass, Integer at) {
        static final Act PLAIN = new Act(false, false, false, false, null);
    }

    private static String autoLabel() {
        return Localizer.getInstance().getMessage("lblAuto");
    }

    /** True while the seat is offered priority and the top of the stack is its own spell or ability. */
    private boolean ownItemOnTop() {
        final GameView gv = gui.getGameView();
        final PlayerView me = gui.getCurrentPlayer();
        final AgentGuiGame.Prompt p = gui.prompt;
        if (gv == null || me == null || p.waiting() || !p.okEnabled() || !p.message().startsWith(priorityPrefix())) {
            return false;
        }
        final StackItemView top = gv.peekStack();
        return top != null && top.getActivatingPlayer() != null && top.getActivatingPlayer().getId() == me.getId();
    }

    /** Waits for the host's first reaction after {@code seen}, then lets it settle: it sends several updates per action. */
    private void settle(long seen) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + ACT_SETTLE_MILLIS;
        long wait = ACT_FIRST_REACTION_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final long now = awaitChange(seen, Math.min(wait, deadline - System.currentTimeMillis()));
            if (now == seen) {
                break; // quiet: the host is done reacting, or never reacted
            }
            seen = now;
            wait = ACT_QUIET_MILLIS;
        }
    }

    private void send(final String action, final Integer id, final Integer color, final Act opts) {
        final IGameController c = controller();
        final GameView gv = gui.getGameView();
        final PlayerView me = gui.getCurrentPlayer();
        final boolean yieldsTurn = "endTurn".equals(action) || ("ok".equals(action) && opts.endTurn());
        if (yieldsTurn && me == null) {
            throw new IllegalStateException("no game in progress");
        }
        // The clicks that end our turn: End Turn, and OK in our end step with nothing on the stack.
        int endsTurn = -1;
        if (gv != null && me != null && gv.getPlayerTurn() != null && gv.getPlayerTurn().getId() == me.getId()) {
            final boolean endTurn = yieldsTurn || ("cancel".equals(action) && endTurnLabel().equals(gui.prompt.cancelLabel()));
            final boolean lastPass = "ok".equals(action) && gv.getPhase() == PhaseType.END_OF_TURN
                    && (gv.getStack() == null || gv.getStack().isEmpty());
            if (endTurn || lastPass) {
                endsTurn = gv.getTurn();
            }
        }
        final boolean priority = !gui.prompt.waiting() && gui.prompt.message().startsWith(priorityPrefix());
        if (yieldsTurn) {
            // What the End Turn button does, whatever the Cancel button reads. On the priority prompt the host
            // passes when the yield arrives, and an OK after it would land on the next prompt.
            c.sendYieldUpdate(new YieldUpdate.SetAutoPassUntilEndOfTurn(me, true));
            if ("ok".equals(action) && !priority) {
                c.selectButtonOk();
            }
        } else if ("ok".equals(action) && !opts.once() && ownItemOnTop()) {
            // The host passes for the seat until the stack is empty, and stops when an opponent adds to it or attacks.
            c.sendYieldUpdate(new YieldUpdate.StackYield(me, true, true));
        } else {
            sendAction(c, action, id, color);
        }
        if (endsTurn >= 0) {
            // Auto-pass goes on after the click. Sent while our prompt is still up, it makes the host recompute
            // our actions on a network thread while the click resumes the game thread, and the host died with a
            // ConcurrentModificationException.
            synchronized (yieldLock) {
                endedTurn = endsTurn;
                sendYieldPrefsLocked(false);
            }
        }
    }

    private void sendAction(final IGameController c, final String action, final Integer id, final Integer color) {
        switch (action) {
            case "ok" -> c.selectButtonOk();
            case "cancel" -> c.selectButtonCancel();
            case "selectCard" -> {
                stackPromptTakesNoCard();
                c.selectCard(card(id), null, null);
            }
            case "selectPlayer" -> c.selectPlayer(player(id), null);
            case "selectStack" -> c.selectStackItem(stackItem(id));
            case "useMana" -> c.useMana((byte) (int) color);
            case "alphaStrike" -> c.alphaStrike();
            case "undo" -> c.undoLastAction();
            case "concede" -> {
                cancelSelection(c);
                c.concede();
            }
            case "quit" -> c.nextGameDecision(NextGameDecision.QUIT);
            case "continue" -> {
                // Forge's own clients offer the next game only while the match is undecided (ViewWinLose).
                final GameView gv = gui.getGameView();
                if (gv != null && gv.isGameOver() && gv.isMatchOver()) {
                    throw new IllegalStateException("the match is over: quit leaves it, newMatch plays it again");
                }
                c.nextGameDecision(NextGameDecision.CONTINUE);
            }
            case "newMatch" -> c.nextGameDecision(NextGameDecision.NEW);
            default -> throw new IllegalArgumentException("unknown action " + action);
        }
    }

    /**
     * Backs out of a prompt that is choosing cards or targets before conceding. Forge's target prompt asks
     * again after the game is over, without end, and the host's game thread died with a StackOverflowError
     * when the seat conceded with one open.
     */
    private void cancelSelection(final IGameController c) {
        final Selection sel = gui.getSelection();
        if (!gui.prompt.cancelEnabled() || sel == null || (sel.cards().isEmpty() && sel.targets().isEmpty())) {
            return;
        }
        final long seen = revision.get();
        c.selectButtonCancel();
        try {
            awaitChange(seen, CANCEL_BEFORE_CONCEDE_MILLIS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Takes the cards the seat moves between its main deck and sideboard before the next game. Both lists
     * empty is no change. The host asks for the main deck once every player has continued.
     */
    void sideboard(final List<String> in, final List<String> out) {
        if (in.isEmpty() && out.isEmpty()) {
            gui.sideboardChange = null;
            return;
        }
        final GameView gv = gui.getGameView();
        final Sideboarding.Decks decks = gui.matchDeck;
        if (gv == null || decks == null || !gui.sideboardingOpen()) {
            throw new IllegalStateException("no cards can be moved now. Sideboarding is open after a game that did not end the match.");
        }
        final Sideboarding.Change change = new Sideboarding.Change(List.copyOf(in), List.copyOf(out));
        Sideboarding.apply(decks, change, gv.getGameType());
        gui.sideboardChange = change;
    }

    Map<String, Object> sideboardSnapshot() {
        final GameView gv = gui.getGameView();
        return Sideboarding.snapshot(gui.matchDeck, gv == null ? null : gv.getGameType(), gui.sideboardingOpen());
    }

    /** Answers a decision and, like {@link #act}, returns once the host has reacted and gone quiet. False for an unknown id. */
    boolean decide(final int id, final List<Integer> indices, final String text) throws InterruptedException {
        synchronized (actLock) {
            final long seen = revision.get();
            if (!gui.answer(id, indices, text)) {
                return false;
            }
            settle(seen);
            return true;
        }
    }

    // ---- snapshot ----

    Map<String, Object> status() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("revision", revision.get());
        final Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("connected", connected && client != null);
        conn.put("host", host);
        conn.put("port", port);
        conn.put("name", name);
        conn.put("reconnectState", client == null ? null : client.getReconnectState().name());
        conn.put("closeReason", closeReason);
        m.put("connection", conn);
        m.put("lobby", lobbySnapshot());
        m.put("draft", draftSnapshot(false));
        m.put("game", gameSnapshot(false));
        final List<ChatLine> lines = List.copyOf(chat);
        m.put("chat", Snapshots.tail(lines, 20));
        m.put("chatCount", lines.size());
        return m;
    }

    Map<String, Object> lobbySnapshot() {
        return Snapshots.lobby(lobbyData, lobby, mySlot, lobbyDeck);
    }

    Map<String, Object> draftSnapshot(final boolean withCards) {
        return Snapshots.draft(draft, picks, autoPicks, withCards);
    }

    Map<String, Object> poolSnapshot() {
        return Snapshots.pool(draft);
    }

    Deck pool() {
        return draft.pool();
    }

    Map<String, Object> gameSnapshot(final boolean full) {
        final Snapshots.AutoPass passing = new Snapshots.AutoPass(autoPass, yieldSent && gui.gamesOpened == yieldSentForGame);
        return Snapshots.game(gui, priorityPrefix(), passing, full);
    }

    List<String> gameLog(final int since) {
        final List<String> out = new ArrayList<>();
        final GameView gv = gui.getGameView();
        if (gv == null || gv.getGameLog() == null) {
            return out;
        }
        final List<GameLogEntry> all = gv.getGameLog().getAllEntries();
        for (int i = Math.max(0, since); i < all.size(); i++) {
            out.add(all.get(i).toString());
        }
        return out;
    }
}
