package forge.agent;

import java.util.List;

import forge.deck.Deck;
import forge.item.PaperCard;

/**
 * Where the seat is in a draft: the pack in front of it and, once the draft is over, the pool. Replaced
 * whole on each draft event, so a reader sees a pack with its own pack and pick numbers.
 */
record DraftState(List<PaperCard> pack, int seatIndex, int packNumber, int pickNumber, int timerSeconds, long packArrivedAt,
        int[] seatQueueDepths, boolean pickPending, Deck pool, String poolEventId, String poolPath) {

    static final DraftState NONE = new DraftState(null, -1, 0, 0, 0, 0, new int[0], false, null, null, null);

    /** {@code pickNo} counts from 1. A pack that arrives after a pool belongs to a new draft, so the old pool goes. */
    DraftState packArrived(final int seat, final List<PaperCard> cards, final int packNo, final int pickNo, final int timer) {
        return new DraftState(List.copyOf(cards), seat, packNo, pickNo, timer, System.currentTimeMillis(), seatQueueDepths, true,
                null, null, null);
    }

    /** Seconds left on the pick clock, or -1 when no pick is waiting or the host set no clock. */
    int secondsLeft() {
        if (!pickPending || timerSeconds <= 0) {
            return -1;
        }
        return (int) Math.max(0, timerSeconds - (System.currentTimeMillis() - packArrivedAt) / 1000);
    }

    DraftState queueDepths(final int[] depths) {
        return new DraftState(pack, seatIndex, packNumber, pickNumber, timerSeconds, packArrivedAt, depths == null ? new int[0] : depths.clone(),
                pickPending, pool, poolEventId, poolPath);
    }

    /** The pack is still shown until the next one arrives. */
    DraftState picked() {
        return new DraftState(pack, seatIndex, packNumber, pickNumber, timerSeconds, packArrivedAt, seatQueueDepths, false, pool, poolEventId, poolPath);
    }

    DraftState poolReceived(final Deck deck, final String eventId, final String path) {
        return new DraftState(null, seatIndex, packNumber, pickNumber, timerSeconds, packArrivedAt, seatQueueDepths, false, deck, eventId, path);
    }
}
