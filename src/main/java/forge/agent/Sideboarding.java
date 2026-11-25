package forge.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.Range;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;

/**
 * Moving cards between the main deck and the sideboard between the games of a match. From the second game
 * on, the host asks each player for its main deck as the game is set up (Match.prepareAllZones), sending
 * the main deck and the sideboard as they stand. It waits for the answer with no time limit, so the bridge
 * answers at once: with the change the seat gave when it continued, or with the main deck unchanged.
 */
final class Sideboarding {

    /** A seat's main deck and sideboard, one entry per copy. */
    record Decks(List<PaperCard> main, List<PaperCard> sideboard) {
    }

    /** Card names to move into the main deck from the sideboard, and out of it, one entry per copy. */
    record Change(List<String> in, List<String> out) {
    }

    private Sideboarding() {
    }

    static Decks of(final Deck deck) {
        if (deck == null) {
            return null;
        }
        return new Decks(deck.getMain().toFlatList(),
                deck.has(DeckSection.Sideboard) ? deck.get(DeckSection.Sideboard).toFlatList() : new ArrayList<>());
    }

    /** The fewest cards the main deck may have after a change. Forge takes the smaller of its size now and the format's minimum. */
    static int minMain(final Decks decks, final GameType type) {
        return Math.min(decks.main().size(), type.getDeckFormat().getMainRange().getMinimum());
    }

    /** The most cards the sideboard may have after a change. A Limited sideboard has no maximum. */
    static int maxSideboard(final Decks decks, final GameType type) {
        final Range<Integer> range = type.getDeckFormat().getSideRange();
        return range == null ? decks.main().size() + decks.sideboard().size() : range.getMaximum();
    }

    /** True when the host will ask about this deck before the next game: it does not when there is nothing to move. */
    static boolean possible(final Decks decks, final GameType type) {
        return decks != null && type != null && type.isSideboardingAllowed()
                && !(decks.sideboard().isEmpty() && decks.main().size() == minMain(decks, type));
    }

    /**
     * The decks after the change, by the limits Forge's host checks (PlayerControllerHuman.sideboard). The host
     * takes any card a client names, so the cards are checked here to be the seat's own.
     *
     * @throws IllegalArgumentException with what is wrong, when the change cannot be made
     */
    static Decks apply(final Decks decks, final Change change, final GameType type) {
        final List<PaperCard> main = new ArrayList<>(decks.main());
        final List<PaperCard> sideboard = new ArrayList<>(decks.sideboard());
        final List<PaperCard> in = take(sideboard, change.in(), "sideboard");
        final List<PaperCard> out = take(main, change.out(), "main deck");
        main.addAll(in);
        sideboard.addAll(out);
        final int minMain = minMain(decks, type);
        if (main.size() < minMain) {
            throw new IllegalArgumentException("the main deck would have " + main.size() + " cards and needs " + minMain + ".");
        }
        final int maxSideboard = maxSideboard(decks, type);
        if (sideboard.size() > maxSideboard) {
            throw new IllegalArgumentException("the sideboard would have " + sideboard.size() + " cards and holds " + maxSideboard + ".");
        }
        return new Decks(main, sideboard);
    }

    private static List<PaperCard> take(final List<PaperCard> from, final List<String> names, final String where) {
        final List<PaperCard> taken = new ArrayList<>();
        for (final String name : names) {
            PaperCard found = null;
            for (final PaperCard card : from) {
                if (card.getName().equalsIgnoreCase(name.trim())) {
                    found = card;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("there is no " + (taken.isEmpty() ? "" : "other ") + name + " in your " + where + ".");
            }
            from.remove(found);
            taken.add(found);
        }
        return taken;
    }

    /** {@code GET /game/sideboard}: the decks by card name with counts, and the limits a change has to keep. */
    static Map<String, Object> snapshot(final Decks decks, final GameType type, final boolean open) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", open);
        if (decks == null || type == null) {
            return m;
        }
        m.put("minMain", minMain(decks, type));
        m.put("maxSideboard", maxSideboard(decks, type));
        m.put("main", counted(decks.main(), false));
        m.put("sideboard", counted(decks.sideboard(), true));
        return m;
    }

    private static List<Map<String, Object>> counted(final List<PaperCard> cards, final boolean described) {
        final Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (final PaperCard card : cards) {
            final Map<String, Object> entry = byName.computeIfAbsent(card.getName(), name -> {
                final Map<String, Object> e = new LinkedHashMap<>();
                e.put("count", 0);
                e.put("name", name);
                if (described && card.getRules() != null) {
                    e.put("manaCost", String.valueOf(card.getRules().getManaCost()));
                    e.put("type", String.valueOf(card.getRules().getType()));
                    e.put("power", card.getRules().getPower());
                    e.put("toughness", card.getRules().getToughness());
                    e.put("text", card.getRules().getOracleText());
                }
                return e;
            });
            entry.put("count", (Integer) entry.get("count") + 1);
        }
        return new ArrayList<>(byName.values());
    }
}
