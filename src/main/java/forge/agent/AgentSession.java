package forge.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import forge.deck.Deck;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.match.Selection;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.DraftPickEvent;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gui.interfaces.IDraftEventHandler;
import forge.interfaces.IGameController;
import forge.interfaces.ILobbyListener;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.trackable.TrackableTypes;
import forge.util.Localizer;

/**
 * One headless Forge client: connection, lobby, draft and game state, plus the actions the bridge
 * exposes. Every incoming event bumps {@link #revision} so callers can long-poll for changes.
 */
final class AgentSession implements ILobbyListener, IDraftEventHandler {

    private final AtomicLong revision = new AtomicLong();
    private final Object changeLock = new Object();

    private final AgentGuiGame gui = new AgentGuiGame(this::changed);
    private final ClientGameLobby lobby = new ClientGameLobby();
    private volatile FGameClient client;
    private volatile String host;
    private volatile int port;
    private volatile String name;
    private volatile boolean connected;
    private volatile String closeReason;

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
    /** The turn of ours on which we pressed End Turn, or -1. */
    private volatile int endedTurn = -1;

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

    private void changed() {
        sendYieldPrefs(false);
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
        changed();
    }

    synchronized void disconnect() {
        final FGameClient c = client;
        client = null;
        connected = false;
        if (c != null) {
            c.close();
        }
        lobbyData = null;
        mySlot = -1;
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
        connected = false;
        closeReason = "connection closed by host or network";
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
    }

    void sendChat(final String text) {
        requireClient().send(new MessageEvent(text));
    }

    // ---- IDraftEventHandler ----

    @Override
    public void draftPackArrived(final int seat, final List<PaperCard> pack, final int packNo, final int pickNo, final int timer) {
        synchronized (draftLock) {
            draft = draft.packArrived(seat, pack, packNo, pickNo, timer);
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
            draft = draft.poolReceived(deck, eventId, path);
        }
        changed();
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

    /** Whether the host should be auto-passing for our seat right now, given the mode and whose turn it is. */
    private boolean wantAutoPass() {
        if (AUTO_PASS_ALWAYS.equals(autoPass)) {
            return true;
        }
        final GameView gv = gui.getGameView();
        final PlayerView me = gui.getCurrentPlayer();
        if (AUTO_PASS_OFF.equals(autoPass) || gv == null || me == null || gv.getPlayerTurn() == null) {
            return false;
        }
        final PhaseType phase = gv.getPhase();
        if (gv.getPlayerTurn().getId() == me.getId()) {
            // On from End Turn or our cleanup step, so it is in place by the opponent's upkeep.
            return phase == PhaseType.CLEANUP || gv.getTurn() == endedTurn;
        }
        // Switch off before the opponent's turn ends, so the setting has reached the host by our main phase.
        return phase != PhaseType.END_OF_TURN && phase != PhaseType.CLEANUP;
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
        }
        if (game != highlightsSentForGame) {
            highlightsSentForGame = game;
            try {
                // The host fills Selection.actionable only for a seat with this setting on.
                c.setYieldPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, "true");
            } catch (final RuntimeException e) {
                System.err.println("[agent] could not ask for actionable cards: " + e);
            }
        }
        final boolean want = wantAutoPass();
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
    void act(final String action, final Integer id, final Integer color) throws InterruptedException {
        synchronized (actLock) {
            // Let the host finish anything a just-sent "auto-pass on" started before we resume its game thread.
            final long clear = lastYieldOnAt + YIELD_ON_GAP_MILLIS - System.currentTimeMillis();
            if (clear > 0) {
                Thread.sleep(clear);
            }
            final long seen = revision.get();
            actingThread = Thread.currentThread();
            try {
                send(action, id, color);
            } finally {
                actingThread = null;
            }
            settle(seen);
        }
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

    private void send(final String action, final Integer id, final Integer color) {
        final IGameController c = controller();
        final GameView gv = gui.getGameView();
        final PlayerView me = gui.getCurrentPlayer();
        // The two clicks that end our turn: End Turn, and OK in our end step with nothing on the stack.
        int endsTurn = -1;
        if (gv != null && me != null && gv.getPlayerTurn() != null && gv.getPlayerTurn().getId() == me.getId()) {
            final boolean endTurn = "cancel".equals(action) && endTurnLabel().equals(gui.prompt.cancelLabel());
            final boolean lastPass = "ok".equals(action) && gv.getPhase() == PhaseType.END_OF_TURN
                    && (gv.getStack() == null || gv.getStack().isEmpty());
            if (endTurn || lastPass) {
                endsTurn = gv.getTurn();
            }
        }
        sendAction(c, action, id, color);
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
            case "selectCard" -> c.selectCard(card(id), null, null);
            case "selectPlayer" -> c.selectPlayer(player(id), null);
            case "useMana" -> c.useMana((byte) (int) color);
            case "alphaStrike" -> c.alphaStrike();
            case "undo" -> c.undoLastAction();
            case "concede" -> {
                cancelSelection(c);
                c.concede();
            }
            case "quit" -> c.nextGameDecision(NextGameDecision.QUIT);
            case "continue" -> c.nextGameDecision(NextGameDecision.CONTINUE);
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
        return Snapshots.lobby(lobbyData, lobby, mySlot);
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
