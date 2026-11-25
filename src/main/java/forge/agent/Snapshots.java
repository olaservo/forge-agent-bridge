package forge.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import forge.card.mana.ManaAtom;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.Selection;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.NetworkEventView;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.item.PaperCard;
import forge.trackable.TrackableObject;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;

/**
 * The JSON payloads the bridge answers with, as maps in the order their fields are sent. The MCP server's
 * output schemas name the same fields, and its fixture test fails when the two differ.
 */
final class Snapshots {

    /** In the order of {@code ManaAtom.MANATYPES}. */
    private static final String[] MANA_LETTERS = {"W", "U", "B", "R", "G", "C"};

    private Snapshots() {
    }

    static Map<String, Object> lobby(final GameLobbyData d, final ClientGameLobby lobby, final int mySlot, final Deck myDeck) {
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
        if (myDeck != null) {
            final Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("name", myDeck.getName());
            dm.put("mainSize", myDeck.getMain().countAll());
            dm.put("sideboardSize", myDeck.has(DeckSection.Sideboard) ? myDeck.get(DeckSection.Sideboard).countAll() : 0);
            m.put("myDeck", dm);
        }
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

    static Map<String, Object> draft(final DraftState d, final List<String> picks, final List<String> autoPicks, final boolean withCards) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("seatIndex", d.seatIndex());
        m.put("packNumber", d.packNumber());
        m.put("pickNumber", d.pickNumber());
        m.put("timerSeconds", d.timerSeconds());
        final int secondsLeft = d.secondsLeft();
        if (secondsLeft >= 0) {
            m.put("secondsLeft", secondsLeft);
        }
        m.put("pickPending", d.pickPending());
        final List<PaperCard> pack = d.pack();
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
        m.put("seatQueueDepths", d.seatQueueDepths());
        m.put("picks", new ArrayList<>(picks));
        m.put("autoPicks", new ArrayList<>(autoPicks));
        m.put("poolReceived", d.pool() != null);
        m.put("poolEventId", d.poolEventId());
        m.put("poolPath", d.poolPath());
        return m;
    }

    static Map<String, Object> pool(final DraftState draft) {
        final Deck d = draft.pool();
        final Map<String, Object> m = new LinkedHashMap<>();
        if (d == null) {
            m.put("poolReceived", false);
            return m;
        }
        m.put("poolReceived", true);
        m.put("eventId", draft.poolEventId());
        m.put("name", d.getName());
        m.put("path", draft.poolPath());
        m.put("tags", new ArrayList<>(d.getTags()));
        m.put("landSet", commonSet(d));
        // Forge delivers a drafted pool with every pick in the Sideboard section and an empty Main.
        m.put("size", d.getAllCardsInASinglePool().countAll());
        m.put("mainSize", d.getMain().countAll());
        m.put("sideboardSize", d.has(DeckSection.Sideboard) ? d.get(DeckSection.Sideboard).countAll() : 0);
        final List<Map<String, Object>> cards = new ArrayList<>();
        for (final DeckSection section : DeckSection.values()) {
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

    /** The set most of the pool's cards come from, for naming basic lands. Null for an empty pool. */
    static String commonSet(final Deck pool) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final PaperCard pc : pool.getAllCardsInASinglePool().toFlatList()) {
            counts.merge(pc.getEdition(), 1, Integer::sum);
        }
        String best = null;
        for (final Map.Entry<String, Integer> e : counts.entrySet()) {
            if (best == null || e.getValue() > counts.get(best)) {
                best = e.getKey();
            }
        }
        return best;
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

    /** The auto-pass fields of a game snapshot: the mode, and whether the host is passing for the seat now. */
    record AutoPass(String mode, boolean now) {
    }

    /**
     * The game as the seat sees it. Without {@code full} it is the summary an action returns: the prompt,
     * the buttons, a pending decision and whose turn it is.
     */
    static Map<String, Object> game(final AgentGuiGame gui, final String priorityPrefix, final AutoPass autoPass, final boolean full) {
        final Map<String, Object> m = new LinkedHashMap<>();
        final GameView gv = gui.getGameView();
        m.put("inGame", gv != null && !gui.gameFinished);
        m.put("finished", gui.gameFinished);
        final AgentGuiGame.Prompt prompt = gui.prompt;
        final PendingDecision d = gui.pending;
        final boolean deciding = d != null && !d.isAnswered();
        // The host sends no prompt when the game ends, so the last one would stay.
        final boolean over = gv != null && gv.isGameOver();
        // A prompt the bridge answers for the seat asks the seat nothing.
        final boolean waiting = !over && !deciding && (prompt.waiting() || gui.answering == prompt.count());
        // A decision is what the seat is asked while it is pending. The host's prompt under it can read "Waiting for ...".
        m.put("prompt", over ? "Game over. Winner: " + Objects.toString(gv.getWinningPlayerName(), "") : deciding ? d.prompt : prompt.message());
        m.put("promptCount", prompt.count());
        m.put("waiting", waiting);
        m.put("priorityPrompt", !over && !deciding && prompt.message().startsWith(priorityPrefix));
        m.put("promptCard", prompt.card() == null ? null : prompt.card().getName());
        final Map<String, Object> buttons = new LinkedHashMap<>();
        buttons.put("ok", prompt.okLabel());
        buttons.put("okEnabled", prompt.okEnabled());
        buttons.put("cancel", prompt.cancelLabel());
        buttons.put("cancelEnabled", prompt.cancelEnabled());
        m.put("buttons", buttons);
        m.put("incorrectActions", gui.incorrectActions);
        if (deciding) {
            final Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", d.id);
            dm.put("kind", d.kind);
            dm.put("prompt", d.prompt);
            dm.put("options", d.options);
            dm.put("min", d.min);
            dm.put("max", d.max);
            dm.put("default", d.defaultAnswer);
            if (d.amount != null) {
                dm.put("amount", d.amount);
            }
            m.put("pendingDecision", dm);
        }
        m.put("messages", tail(gui.messages, 10));
        // Counts for a caller that wants only what is new: messages this game, and lines in the game log.
        m.put("messageCount", gui.messages.size());
        m.put("logCount", gv == null || gv.getGameLog() == null ? 0 : gv.getGameLog().getAllEntries().size());
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
        // The host sends no new prompt when it passes for us, so a "Priority: ..." prompt can outlive our priority.
        // The player view can still say we hold priority after we passed, until the host updates it.
        m.put("hasPriority", me == null ? null : !over && !waiting && me.getHasPriority());
        m.put("autoPass", autoPass.mode());
        m.put("autoPassNow", autoPass.now());
        m.put("stops", gui.stops());
        if (!full) {
            return m;
        }
        // The game view arrives a moment before the host fills it in. Until then its players and stack are null.
        final FCollectionView<PlayerView> seats = gv.getPlayers();
        final FCollectionView<StackItemView> items = gv.getStack();
        final List<Map<String, Object>> players = new ArrayList<>();
        for (final PlayerView p : seats == null ? List.<PlayerView>of() : seats) {
            final Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("name", p.getName());
            pm.put("life", p.getLife());
            final boolean isMe = me != null && me.getId() == p.getId();
            pm.put("hasPriority", isMe ? m.get("hasPriority") : !over && p.getHasPriority());
            pm.put("isMe", isMe);
            pm.put("handSize", p.getZoneSize(ZoneType.Hand));
            pm.put("librarySize", p.getZoneSize(ZoneType.Library));
            pm.put("graveyardSize", p.getZoneSize(ZoneType.Graveyard));
            pm.put("exileSize", p.getZoneSize(ZoneType.Exile));
            // Mana floating in the pool, by the letter of its colour. Empty when there is none.
            final Map<String, Integer> mana = new LinkedHashMap<>();
            for (int i = 0; i < ManaAtom.MANATYPES.length; i++) {
                final int amount = p.getMana(ManaAtom.MANATYPES[i]);
                if (amount > 0) {
                    mana.put(MANA_LETTERS[i], amount);
                }
            }
            pm.put("mana", mana);
            pm.put("battlefield", cards(gui, p.getBattlefield()));
            pm.put("graveyard", cards(gui, p.getGraveyard()));
            if (me != null && me.getId() == p.getId()) {
                pm.put("hand", cards(gui, p.getHand()));
            }
            players.add(pm);
        }
        m.put("players", players);
        final List<Map<String, Object>> stack = new ArrayList<>();
        for (final StackItemView si : items == null ? List.<StackItemView>of() : items) {
            final Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", si.getId());
            sm.put("source", si.getSourceCard() == null ? null : si.getSourceCard().getName());
            sm.put("by", si.getActivatingPlayer() == null ? null : si.getActivatingPlayer().getName());
            sm.put("text", si.getText());
            final List<String> targets = new ArrayList<>();
            if (si.getTargetCards() != null) {
                for (final CardView t : si.getTargetCards()) {
                    targets.add(t.getName() + "#" + t.getId());
                }
            }
            if (si.getTargetPlayers() != null) {
                for (final PlayerView t : si.getTargetPlayers()) {
                    targets.add(t.getName());
                }
            }
            // A spell that targets a spell, as a counterspell does: the name of what that item came from.
            if (si.getTargetStackItems() != null) {
                for (final StackItemView t : si.getTargetStackItems()) {
                    targets.add(t.getSourceCard() == null ? String.valueOf(t.getText()) : t.getSourceCard().getName());
                }
            }
            sm.put("targets", targets);
            stack.add(sm);
        }
        m.put("stack", stack);
        final CombatView combat = gv.getCombat();
        if (combat != null) {
            m.put("combat", combat(combat));
        }
        final Selection sel = gui.getSelection();
        if (sel != null) {
            putSelection(gui, m, sel);
        }
        return m;
    }

    private static List<Map<String, Object>> combat(final CombatView combat) {
        final List<Map<String, Object>> attacks = new ArrayList<>();
        for (final CardView a : combat.getAttackers()) {
            final Map<String, Object> am = new LinkedHashMap<>();
            am.put("attacker", a.getName());
            am.put("attackerId", a.getId());
            am.put("defender", combat.getDefender(a) == null ? null : combat.getDefender(a).getName());
            // The host lists blockers as declared only once the block is final. Until then they are planned.
            final FCollection<CardView> declared = combat.getBlockers(a);
            final FCollection<CardView> planned = combat.getPlannedBlockers(a);
            final List<String> blockers = new ArrayList<>();
            for (final CardView b : declared != null && !declared.isEmpty() ? declared : planned == null ? List.<CardView>of() : planned) {
                blockers.add(b.getName() + "#" + b.getId());
            }
            am.put("blockers", blockers);
            am.put("blocked", declared != null && !declared.isEmpty());
            attacks.add(am);
        }
        return attacks;
    }

    /**
     * What the current prompt accepts: cards, including ones in zones the state does not list (a library
     * search), players, and spells and abilities on the stack. "chosen" is what is picked so far, or the
     * attacker being blocked.
     */
    private static void putSelection(final AgentGuiGame gui, final Map<String, Object> m, final Selection sel) {
        final List<Map<String, Object>> offered = new ArrayList<>();
        for (final CardView cv : sel.cards()) {
            if (cv != null) {
                offered.add(describe(gui, cv));
            }
        }
        final List<Map<String, Object>> targetPlayers = entities(sel.targets(), true);
        // A counterspell's targets. The ids are those of the stack entries, which "stack" lists too.
        final List<Map<String, Object>> targetStack = new ArrayList<>();
        for (final TrackableObject o : sel.targets()) {
            if (o instanceof StackItemView) {
                final StackItemView si = (StackItemView) o;
                final Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("id", si.getId());
                tm.put("source", si.getSourceCard() == null ? null : si.getSourceCard().getName());
                tm.put("by", si.getActivatingPlayer() == null ? null : si.getActivatingPlayer().getName());
                tm.put("text", si.getText());
                targetStack.add(tm);
            }
        }
        final List<Map<String, Object>> chosen = entities(sel.chosen(), false);
        // The host's hint at what can act now: cards that can be played, attack, block or pay.
        final List<Map<String, Object>> actionable = new ArrayList<>();
        final Set<Integer> listed = new HashSet<>();
        for (final CardView cv : sel.actionable()) {
            if (cv != null && listed.add(cv.getId())) {
                final Map<String, Object> am = new LinkedHashMap<>();
                am.put("id", cv.getId());
                am.put("name", cv.getName());
                actionable.add(am);
            }
        }
        if (!actionable.isEmpty()) {
            m.put("actionable", actionable);
        }
        if (!offered.isEmpty() || !targetPlayers.isEmpty() || !targetStack.isEmpty() || !chosen.isEmpty()) {
            final Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("min", sel.min());
            sm.put("max", sel.max());
            sm.put("cards", offered);
            sm.put("players", targetPlayers);
            if (!targetStack.isEmpty()) {
                sm.put("stack", targetStack);
            }
            sm.put("chosen", chosen);
            m.put("selection", sm);
        }
    }

    /** A selection's objects as id, name and type: players, cards and stack entries, or players alone. A stack entry has its source's name. */
    private static List<Map<String, Object>> entities(final List<TrackableObject> objects, final boolean playersOnly) {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final TrackableObject o : objects) {
            final boolean player = o instanceof PlayerView;
            final boolean stack = o instanceof StackItemView;
            if (!player && (playersOnly || !(o instanceof CardView || stack))) {
                continue;
            }
            final Map<String, Object> em = new LinkedHashMap<>();
            em.put("type", player ? "player" : stack ? "stack" : "card");
            em.put("id", o.getId());
            if (player) {
                em.put("name", ((PlayerView) o).getName());
            } else if (stack) {
                final CardView source = ((StackItemView) o).getSourceCard();
                em.put("name", source == null ? ((StackItemView) o).getText() : source.getName());
            } else {
                em.put("name", ((CardView) o).getName());
            }
            out.add(em);
        }
        return out;
    }

    private static List<Map<String, Object>> cards(final AgentGuiGame gui, final FCollectionView<CardView> cards) {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (cards == null) {
            return out;
        }
        for (final CardView cv : cards) {
            out.add(describe(gui, cv));
        }
        return out;
    }

    /**
     * A card, and for a face-down one the seat may look at, the name of its other face. The host sends that
     * face for every face-down card. Forge's client shows it only where {@code mayFlip} allows, as here.
     */
    static Map<String, Object> describe(final AgentGuiGame gui, final CardView cv) {
        final Map<String, Object> m = describe(cv);
        final CardStateView face = cv.isFaceDown() ? cv.getAlternateState() : null;
        if (face != null && gui.getCurrentPlayer() != null && gui.mayFlip(cv)) {
            m.put("faceUpName", face.getName());
        }
        return m;
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
        if (cv.isPhasedOut()) {
            // Still listed in its zone, but treated as though it does not exist until it phases in.
            m.put("phasedOut", true);
        }
        // An Aura, Equipment or Fortification names what it is attached to, a card or a player.
        final GameEntityView host = cv.getEntityAttachedTo();
        final List<Map<String, Object>> attachedTo = host == null ? List.of() : entities(List.<TrackableObject>of(host), false);
        if (!attachedTo.isEmpty()) {
            m.put("attachedTo", attachedTo.get(0));
        }
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

    static <T> List<T> tail(final List<T> list, final int n) {
        final int size = list.size();
        return new ArrayList<>(list.subList(Math.max(0, size - n), size));
    }
}
