package forge.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.Selection;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;

/**
 * The host's view of a game as one player may see it. The host's {@link GameView} holds every card by
 * name, so each card goes through {@link CardView#canBeShownTo} for the viewer before it is described.
 * Cards are described by {@link Snapshots#describe(CardView)}, as a seat's own snapshot describes them.
 */
final class CopilotView {

    private CopilotView() {
    }

    /** True when the viewer may see this card's face. */
    static boolean shown(final CardView cv, final PlayerView viewer) {
        return cv.canBeShownTo(viewer) && (!cv.isFaceDown() || cv.canFaceDownBeShownTo(viewer));
    }

    /** A card the viewer may see as a seat's snapshot describes it, and any other card as an id with no name. */
    static Map<String, Object> card(final CardView cv, final PlayerView viewer) {
        if (shown(cv, viewer)) {
            return Snapshots.describe(cv);
        }
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cv.getId());
        m.put("name", null);
        m.put("hidden", true);
        m.put("zone", cv.getZone() == null ? null : cv.getZone().name());
        m.put("controller", cv.getController() == null ? null : cv.getController().getName());
        m.put("faceDown", cv.isFaceDown());
        return m;
    }

    static String name(final CardView cv, final PlayerView viewer) {
        return cv == null || !shown(cv, viewer) ? null : cv.getName();
    }

    /** One option or answer of a dialog as text. A card the viewer may not see has no name. */
    static Object label(final Object o, final PlayerView viewer) {
        if (o == null) {
            return null;
        }
        if (o instanceof CardView cv) {
            return shown(cv, viewer) ? cv.toString() : "hidden card (" + cv.getId() + ")";
        }
        if (o instanceof Collection<?> c) {
            return labels(c, viewer);
        }
        return String.valueOf(o);
    }

    static List<Object> labels(final Iterable<?> items, final PlayerView viewer) {
        final List<Object> out = new ArrayList<>();
        if (items != null) {
            for (final Object o : items) {
                out.add(label(o, viewer));
            }
        }
        return out;
    }

    private static List<Map<String, Object>> cards(final Iterable<CardView> cards, final PlayerView viewer) {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (cards != null) {
            for (final CardView cv : cards) {
                if (cv != null) {
                    out.add(card(cv, viewer));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> entity(final Object o, final PlayerView viewer) {
        final Map<String, Object> m = new LinkedHashMap<>();
        if (o instanceof PlayerView p) {
            m.put("type", "player");
            m.put("id", p.getId());
            m.put("name", p.getName());
        } else if (o instanceof CardView cv) {
            m.put("type", "card");
            m.put("id", cv.getId());
            m.put("name", name(cv, viewer));
        } else if (o instanceof StackItemView si) {
            m.put("type", "stack");
            m.put("id", si.getId());
            m.put("source", name(si.getSourceCard(), viewer));
            m.put("by", si.getActivatingPlayer() == null ? null : si.getActivatingPlayer().getName());
            m.put("text", si.getText());
        } else {
            return null;
        }
        return m;
    }

    /** What a prompt accepts and what is picked so far, with the keys of a seat's snapshot. */
    static Map<String, Object> selection(final Selection sel, final PlayerView viewer) {
        final Map<String, Object> m = new LinkedHashMap<>();
        if (sel == null) {
            return m;
        }
        final List<Map<String, Object>> players = new ArrayList<>();
        final List<Map<String, Object>> stack = new ArrayList<>();
        for (final Object o : sel.targets()) {
            final Map<String, Object> e = entity(o, viewer);
            if (o instanceof PlayerView) {
                players.add(e);
            } else if (o instanceof StackItemView) {
                stack.add(e);
            }
        }
        final List<Map<String, Object>> chosen = new ArrayList<>();
        for (final Object o : sel.chosen()) {
            final Map<String, Object> e = entity(o, viewer);
            if (e != null) {
                chosen.add(e);
            }
        }
        final List<Map<String, Object>> actionable = new ArrayList<>();
        for (final CardView cv : sel.actionable()) {
            if (cv != null) {
                final Map<String, Object> am = new LinkedHashMap<>();
                am.put("id", cv.getId());
                am.put("name", name(cv, viewer));
                actionable.add(am);
            }
        }
        m.put("min", sel.min());
        m.put("max", sel.max());
        m.put("cards", cards(sel.cards(), viewer));
        m.put("players", players);
        m.put("stack", stack);
        m.put("chosen", chosen);
        m.put("actionable", actionable);
        return m;
    }

    /** Turn, players, zones, stack and combat. A hand is listed for the viewer alone. */
    static void board(final Map<String, Object> m, final GameView gv, final PlayerView viewer) {
        final boolean over = gv.isGameOver();
        m.put("gameOver", over);
        m.put("winner", gv.getWinningPlayerName());
        m.put("turn", gv.getTurn());
        m.put("phase", gv.getPhase() == null ? null : gv.getPhase().name());
        m.put("activePlayer", gv.getPlayerTurn() == null ? null : gv.getPlayerTurn().getName());
        m.put("me", viewer.getName());
        m.put("hasPriority", !over && viewer.getHasPriority());
        final List<Map<String, Object>> players = new ArrayList<>();
        final FCollectionView<PlayerView> seats = gv.getPlayers();
        for (final PlayerView p : seats == null ? List.<PlayerView>of() : seats) {
            final Map<String, Object> pm = new LinkedHashMap<>();
            final boolean isMe = p.getId() == viewer.getId();
            pm.put("id", p.getId());
            pm.put("name", p.getName());
            pm.put("life", p.getLife());
            pm.put("hasPriority", !over && p.getHasPriority());
            pm.put("isMe", isMe);
            pm.put("handSize", p.getZoneSize(ZoneType.Hand));
            pm.put("librarySize", p.getZoneSize(ZoneType.Library));
            pm.put("graveyardSize", p.getZoneSize(ZoneType.Graveyard));
            pm.put("exileSize", p.getZoneSize(ZoneType.Exile));
            pm.put("battlefield", cards(p.getBattlefield(), viewer));
            pm.put("graveyard", cards(p.getGraveyard(), viewer));
            if (isMe) {
                pm.put("hand", cards(p.getHand(), viewer));
            }
            players.add(pm);
        }
        m.put("players", players);
        final List<Map<String, Object>> stack = new ArrayList<>();
        final FCollectionView<StackItemView> items = gv.getStack();
        for (final StackItemView si : items == null ? List.<StackItemView>of() : items) {
            stack.add(entity(si, viewer));
        }
        m.put("stack", stack);
        final CombatView combat = gv.getCombat();
        if (combat != null) {
            final List<Map<String, Object>> attacks = new ArrayList<>();
            for (final CardView a : combat.getAttackers()) {
                final Map<String, Object> am = new LinkedHashMap<>();
                am.put("attacker", name(a, viewer));
                am.put("attackerId", a.getId());
                am.put("defender", combat.getDefender(a) == null ? null : combat.getDefender(a).getName());
                final FCollection<CardView> declared = combat.getBlockers(a);
                final List<Object> blockers = new ArrayList<>();
                for (final CardView b : declared == null ? List.<CardView>of() : declared) {
                    blockers.add(name(b, viewer) + "#" + b.getId());
                }
                am.put("blockers", blockers);
                attacks.add(am);
            }
            m.put("combat", attacks);
        }
    }
}
