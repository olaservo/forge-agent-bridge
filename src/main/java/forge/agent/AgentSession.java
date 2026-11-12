package forge.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import forge.deck.Deck;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.match.Selection;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.NetworkEventView;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.DraftPickEvent;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gui.interfaces.IDraftEventHandler;
import forge.interfaces.IGameController;
import forge.interfaces.ILobbyListener;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.trackable.TrackableTypes;
import forge.util.collect.FCollectionView;

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
    final List<String> chat = new CopyOnWriteArrayList<>();

    // draft
    private volatile List<PaperCard> currentPack;
    private volatile int seatIndex = -1;
    private volatile int packNumber;
    private volatile int pickNumber;
    private volatile int timerSeconds;
    private volatile int[] seatQueueDepths = new int[0];
    private volatile boolean pickPending;
    final List<String> picks = new CopyOnWriteArrayList<>();
    final List<String> autoPicks = new CopyOnWriteArrayList<>();
    private volatile Deck pool;
    private volatile String poolEventId;
    private volatile String poolPath;

    long revision() {
        return revision.get();
    }

    private void changed() {
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

    // ---- ILobbyListener ----

    @Override
    public void message(final String source, final String message, final ChatMessage.MessageType type) {
        chat.add((source == null ? "" : source + ": ") + message);
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

    void setDeck(final Deck deck) {
        requireClient().send(UpdateLobbyPlayerEvent.deckUpdate(deck));
    }

    void sendChat(final String text) {
        requireClient().send(new forge.gamemodes.net.event.MessageEvent(text));
    }

    // ---- IDraftEventHandler ----

    @Override
    public void draftPackArrived(final int seat, final List<PaperCard> pack, final int packNo, final int pickNo, final int timer) {
        seatIndex = seat;
        currentPack = new ArrayList<>(pack);
        packNumber = packNo;
        pickNumber = pickNo;
        timerSeconds = timer;
        pickPending = true;
        changed();
    }

    @Override
    public void draftSeatPicked(final int seat, final int[] queueDepths) {
        seatQueueDepths = queueDepths == null ? new int[0] : queueDepths.clone();
        changed();
    }

    @Override
    public void draftAutoPicked(final int seat, final PaperCard card, final int packNo, final int pickInPack) {
        autoPicks.add(card.getName() + " (pack " + packNo + " pick " + pickInPack + ")");
        picks.add(card.getName());
        pickPending = false;
        changed();
    }

    @Override
    public void receiveEventPool(final String eventId, final Deck deck) {
        pool = deck;
        poolEventId = eventId;
        pickPending = false;
        currentPack = null;
        try {
            FModel.getDecks().getNetworkEventDecks().add(deck);
            poolPath = new File(ForgeConstants.DECK_NET_EVENT_DIR, deck.getName() + ".dck").getAbsolutePath();
        } catch (final RuntimeException e) {
            poolPath = "not saved: " + e;
        }
        changed();
    }

    /** Picks a card from the current pack by exact name or 0-based index string. */
    synchronized Map<String, Object> pick(final String card) {
        final List<PaperCard> pack = currentPack;
        if (pack == null || !pickPending) {
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
        requireClient().send(new DraftPickEvent(seatIndex, chosen));
        picks.add(chosen.getName());
        pickPending = false;
        changed();
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("picked", describe(chosen));
        out.put("packNumber", packNumber);
        out.put("pickNumber", pickNumber);
        return out;
    }

    // ---- game actions ----

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
     * Sends one controller call and returns once the host has reacted and gone quiet for
     * {@code ACT_QUIET_MILLIS}, or after {@code ACT_FIRST_REACTION_MILLIS} with no reaction, capped at
     * {@code ACT_SETTLE_MILLIS}. The host runs each call from a client on its own thread, so two calls
     * sent back to back can overlap there; a desktop player's clicks never do. Waiting for the
     * reaction to settle spaces them the same way.
     */
    void act(final String action, final Integer id, final Integer color) throws InterruptedException {
        synchronized (actLock) {
            long seen = revision.get();
            send(action, id, color);
            // First reaction, then let it settle: the host sends several updates per action.
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
    }

    private static final long ACT_FIRST_REACTION_MILLIS = 1500;
    private static final long ACT_QUIET_MILLIS = 400;
    private static final long ACT_SETTLE_MILLIS = 3000;
    private final Object actLock = new Object();

    private void send(final String action, final Integer id, final Integer color) {
        final IGameController c = controller();
        switch (action) {
            case "ok" -> c.selectButtonOk();
            case "cancel" -> c.selectButtonCancel();
            case "selectCard" -> c.selectCard(card(id), null, null);
            case "selectPlayer" -> c.selectPlayer(player(id), null);
            case "useMana" -> c.useMana((byte) (int) color);
            case "alphaStrike" -> c.alphaStrike();
            case "undo" -> c.undoLastAction();
            case "concede" -> c.concede();
            case "quit" -> c.nextGameDecision(NextGameDecision.QUIT);
            case "continue" -> c.nextGameDecision(NextGameDecision.CONTINUE);
            case "newMatch" -> c.nextGameDecision(NextGameDecision.NEW);
            default -> throw new IllegalArgumentException("unknown action " + action);
        }
    }

    boolean decide(final int id, final List<Integer> indices, final String text) {
        return gui.answer(id, indices, text);
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
        m.put("chat", tail(chat, 20));
        return m;
    }

    Map<String, Object> lobbySnapshot() {
        final GameLobbyData d = lobbyData;
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("mySlot", mySlot);
        if (d == null) {
            return m;
        }
        m.put("limitedMode", d.isLimitedMode());
        m.put("activeEventId", d.getActiveEventId());
        m.put("formatName", d.getFormatName());
        final List<Map<String, Object>> slots = new ArrayList<>();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final LobbySlot s = lobby.getSlot(i);
            final Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("index", i);
            sm.put("type", s.getType().name());
            sm.put("name", s.getName());
            sm.put("ready", s.isReady());
            sm.put("avatar", s.getAvatarIndex());
            sm.put("team", s.getTeam());
            sm.put("deckName", s.getDeckName());
            sm.put("hasDeck", s.getDeck() != null);
            slots.add(sm);
        }
        m.put("slots", slots);
        final NetworkEventView ev = d.getEventView();
        if (ev != null) {
            final Map<String, Object> em = new LinkedHashMap<>();
            em.put("eventId", ev.getEventId());
            em.put("format", ev.getFormat() == null ? null : ev.getFormat().name());
            em.put("phase", ev.getPhase() == null ? null : ev.getPhase().name());
            em.put("product", ev.getProductDescription());
            em.put("podSize", ev.getPodSize());
            em.put("numRounds", ev.getNumRounds());
            em.put("pickTimerSeconds", ev.getPickTimerSeconds());
            final List<Map<String, Object>> ps = new ArrayList<>();
            for (final EventParticipant p : ev.getParticipants()) {
                final Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.getName());
                pm.put("type", p.getType().name());
                pm.put("seat", p.getSeatIndex());
                pm.put("lobbySlot", p.getLobbySlotIndex());
                ps.add(pm);
            }
            em.put("participants", ps);
            m.put("event", em);
        }
        return m;
    }

    Map<String, Object> draftSnapshot(final boolean withCards) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("seatIndex", seatIndex);
        m.put("packNumber", packNumber);
        m.put("pickNumber", pickNumber);
        m.put("timerSeconds", timerSeconds);
        m.put("pickPending", pickPending);
        final List<PaperCard> pack = currentPack;
        m.put("packSize", pack == null ? 0 : pack.size());
        if (withCards && pack != null) {
            final List<Map<String, Object>> cards = new ArrayList<>();
            int i = 0;
            for (final PaperCard pc : pack) {
                final Map<String, Object> cm = describe(pc);
                cm.put("index", i++);
                cards.add(cm);
            }
            m.put("pack", cards);
        }
        m.put("seatQueueDepths", seatQueueDepths);
        m.put("picks", new ArrayList<>(picks));
        m.put("autoPicks", new ArrayList<>(autoPicks));
        m.put("poolReceived", pool != null);
        m.put("poolEventId", poolEventId);
        m.put("poolPath", poolPath);
        return m;
    }

    Map<String, Object> poolSnapshot() {
        final Deck d = pool;
        final Map<String, Object> m = new LinkedHashMap<>();
        if (d == null) {
            m.put("poolReceived", false);
            return m;
        }
        m.put("poolReceived", true);
        m.put("eventId", poolEventId);
        m.put("name", d.getName());
        m.put("path", poolPath);
        m.put("tags", new ArrayList<>(d.getTags()));
        // Forge delivers a drafted pool with every pick in the Sideboard section and an empty Main.
        m.put("size", d.getAllCardsInASinglePool().countAll());
        m.put("mainSize", d.getMain().countAll());
        m.put("sideboardSize", d.has(forge.deck.DeckSection.Sideboard) ? d.get(forge.deck.DeckSection.Sideboard).countAll() : 0);
        final List<Map<String, Object>> cards = new ArrayList<>();
        for (final forge.deck.DeckSection section : forge.deck.DeckSection.values()) {
            if (!d.has(section)) {
                continue;
            }
            for (final PaperCard pc : d.get(section).toFlatList()) {
                final Map<String, Object> cm = describe(pc);
                cm.put("section", section.name());
                cards.add(cm);
            }
        }
        m.put("cards", cards);
        return m;
    }

    Deck pool() {
        return pool;
    }

    static Map<String, Object> describe(final PaperCard pc) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", pc.getName());
        m.put("set", pc.getEdition());
        m.put("collectorNumber", pc.getCollectorNumber());
        m.put("rarity", pc.getRarity() == null ? null : pc.getRarity().name());
        if (pc.getRules() != null) {
            m.put("manaCost", String.valueOf(pc.getRules().getManaCost()));
            m.put("type", String.valueOf(pc.getRules().getType()));
            m.put("colors", String.valueOf(pc.getRules().getColor()));
            m.put("power", pc.getRules().getPower());
            m.put("toughness", pc.getRules().getToughness());
            m.put("oracle", pc.getRules().getOracleText());
        }
        return m;
    }

    Map<String, Object> gameSnapshot(final boolean full) {
        final Map<String, Object> m = new LinkedHashMap<>();
        final GameView gv = gui.getGameView();
        m.put("inGame", gv != null && !gui.gameFinished);
        m.put("finished", gui.gameFinished);
        m.put("prompt", gui.promptMessage);
        m.put("promptCard", gui.promptCard == null ? null : gui.promptCard.getName());
        final Map<String, Object> buttons = new LinkedHashMap<>();
        buttons.put("ok", gui.okLabel);
        buttons.put("okEnabled", gui.okEnabled);
        buttons.put("cancel", gui.cancelLabel);
        buttons.put("cancelEnabled", gui.cancelEnabled);
        m.put("buttons", buttons);
        m.put("incorrectActions", gui.incorrectActions);
        final PendingDecision d = gui.pending;
        if (d != null && !d.isAnswered()) {
            final Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", d.id);
            dm.put("kind", d.kind);
            dm.put("prompt", d.prompt);
            dm.put("options", d.options);
            dm.put("min", d.min);
            dm.put("max", d.max);
            dm.put("default", d.defaultAnswer);
            m.put("pendingDecision", dm);
        }
        m.put("messages", tail(gui.messages, 10));
        if (gv == null) {
            return m;
        }
        m.put("turn", gv.getTurn());
        m.put("phase", gv.getPhase() == null ? null : gv.getPhase().name());
        m.put("activePlayer", gv.getPlayerTurn() == null ? null : gv.getPlayerTurn().getName());
        m.put("gameOver", gv.isGameOver());
        m.put("winner", gv.getWinningPlayerName());
        final PlayerView me = gui.getCurrentPlayer();
        m.put("me", me == null ? null : me.getName());
        if (!full) {
            return m;
        }
        final List<Map<String, Object>> players = new ArrayList<>();
        for (final PlayerView p : gv.getPlayers()) {
            final Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("name", p.getName());
            pm.put("life", p.getLife());
            pm.put("hasPriority", p.getHasPriority());
            pm.put("isMe", me != null && me.getId() == p.getId());
            pm.put("handSize", p.getZoneSize(ZoneType.Hand));
            pm.put("librarySize", p.getZoneSize(ZoneType.Library));
            pm.put("graveyardSize", p.getZoneSize(ZoneType.Graveyard));
            pm.put("exileSize", p.getZoneSize(ZoneType.Exile));
            pm.put("battlefield", cards(p.getBattlefield()));
            pm.put("graveyard", cards(p.getGraveyard()));
            if (me != null && me.getId() == p.getId()) {
                pm.put("hand", cards(p.getHand()));
            }
            players.add(pm);
        }
        m.put("players", players);
        final List<Map<String, Object>> stack = new ArrayList<>();
        for (final StackItemView si : gv.getStack()) {
            final Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", si.getId());
            sm.put("source", si.getSourceCard() == null ? null : si.getSourceCard().getName());
            sm.put("by", si.getActivatingPlayer() == null ? null : si.getActivatingPlayer().getName());
            sm.put("text", si.getText());
            stack.add(sm);
        }
        m.put("stack", stack);
        final CombatView combat = gv.getCombat();
        if (combat != null) {
            final List<Map<String, Object>> attacks = new ArrayList<>();
            for (final CardView a : combat.getAttackers()) {
                final Map<String, Object> am = new LinkedHashMap<>();
                am.put("attacker", a.getName());
                am.put("attackerId", a.getId());
                am.put("defender", combat.getDefender(a) == null ? null : combat.getDefender(a).getName());
                final List<String> blockers = new ArrayList<>();
                if (combat.getBlockers(a) != null) {
                    for (final CardView b : combat.getBlockers(a)) {
                        blockers.add(b.getName() + "#" + b.getId());
                    }
                }
                am.put("blockers", blockers);
                attacks.add(am);
            }
            m.put("combat", attacks);
        }
        // Cards the current prompt offers, including ones in zones the state does not list (a library search).
        final Selection sel = gui.getSelection();
        if (sel != null && !sel.cards().isEmpty()) {
            final Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("min", sel.min());
            sm.put("max", sel.max());
            final List<Map<String, Object>> offered = new ArrayList<>();
            for (final CardView cv : sel.cards()) {
                if (cv != null) {
                    offered.add(describe(cv));
                }
            }
            sm.put("cards", offered);
            m.put("selection", sm);
        }
        return m;
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

    private static List<Map<String, Object>> cards(final FCollectionView<CardView> cards) {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (cards == null) {
            return out;
        }
        for (final CardView cv : cards) {
            out.add(describe(cv));
        }
        return out;
    }

    static Map<String, Object> describe(final CardView cv) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cv.getId());
        m.put("name", cv.getName());
        m.put("zone", cv.getZone() == null ? null : cv.getZone().name());
        m.put("controller", cv.getController() == null ? null : cv.getController().getName());
        m.put("tapped", cv.isTapped());
        m.put("sick", cv.isSick());
        m.put("attacking", cv.isAttacking());
        m.put("blocking", cv.isBlocking());
        m.put("damage", cv.getDamage());
        m.put("token", cv.isToken());
        m.put("faceDown", cv.isFaceDown());
        final CardStateView st = cv.getCurrentState();
        if (st != null) {
            m.put("type", String.valueOf(st.getType()));
            m.put("manaCost", String.valueOf(st.getManaCost()));
            if (st.isCreature()) {
                m.put("power", st.getPower());
                m.put("toughness", st.getToughness());
            }
            m.put("text", st.getAbilityText());
        }
        return m;
    }

    private static List<String> tail(final List<String> list, final int n) {
        final int size = list.size();
        return new ArrayList<>(list.subList(Math.max(0, size - n), size));
    }
}
