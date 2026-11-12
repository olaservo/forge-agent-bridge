package forge.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.net.NetworkGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

/**
 * Headless client GUI. Display calls are recorded for the bridge to report; blocking prompts become
 * {@link PendingDecision}s that park the worker thread until the bridge answers.
 */
final class AgentGuiGame extends NetworkGuiGame {

    private final Runnable onChange;
    private final AtomicInteger decisionIds = new AtomicInteger();

    volatile String promptMessage = "";
    volatile CardView promptCard;
    volatile String okLabel = "OK";
    volatile String cancelLabel = "Cancel";
    volatile boolean okEnabled;
    volatile boolean cancelEnabled;
    volatile boolean gameFinished;
    volatile int incorrectActions;
    volatile TrackableCollection<PlayerView> myPlayers;
    volatile PendingDecision pending;
    /** Counts games opened, so the session can tell a new game from the one it already set up. */
    volatile int gamesOpened;
    final List<String> messages = new CopyOnWriteArrayList<>();

    AgentGuiGame(final Runnable onChange) {
        this.onChange = onChange;
    }

    private void changed() {
        onChange.run();
    }

    // ---- decisions ----

    private <T> List<Integer> decide(final String kind, final String prompt, final List<String> options,
            final int min, final int max, final String defaultAnswer) {
        final PendingDecision d = new PendingDecision(decisionIds.incrementAndGet(), kind, prompt, options, min, max, defaultAnswer);
        pending = d;
        changed();
        try {
            return d.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            if (pending == d) {
                pending = null;
            }
            changed();
        }
    }

    private <T> List<T> pick(final String kind, final String prompt, final List<T> choices, final int min, final int max,
            final FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) {
            return new ArrayList<>();
        }
        final List<String> labels = new ArrayList<>(choices.size());
        for (final T c : choices) {
            labels.add(display != null ? display.apply(c) : String.valueOf(c));
        }
        final List<Integer> idx = decide(kind, prompt, labels, min, Math.min(max, choices.size()), null);
        final List<T> out = new ArrayList<>();
        if (idx != null) {
            for (final int i : idx) {
                if (i >= 0 && i < choices.size()) {
                    out.add(choices.get(i));
                }
            }
        }
        return out;
    }

    boolean answer(final int id, final List<Integer> indices, final String text) {
        final PendingDecision d = pending;
        if (d == null || d.id != id || d.isAnswered()) {
            return false;
        }
        d.answer(indices, text);
        return true;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices,
            final List<T> selected, final FSerializableFunction<T, String> display) {
        if (min < 0 && max < 0) {
            // A reveal: the host shows a list and expects nothing back. Asking the seat would park the host
            // until it answered, and a reveal sent while the seat concedes kept the game from ending.
            final List<String> shown = new ArrayList<>();
            if (choices != null) {
                for (final T c : choices) {
                    shown.add(display != null ? display.apply(c) : String.valueOf(c));
                }
            }
            messages.add("Revealed, " + message + ": " + String.join(", ", shown));
            changed();
            return new ArrayList<>();
        }
        return pick("choices", message, choices, min, max, display);
    }

    @Override
    public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax,
            final List<T> sourceChoices, final List<T> destChoices, final CardView referenceCard, final boolean sideboardingMode,
            final boolean showRememberCheckbox) {
        // The host says how many may be left unchosen, -1 for any number. Scry asks this way for the cards to
        // put on the bottom, so asking for all of them every time sent the whole scry to the bottom.
        final int n = sourceChoices.size();
        final int min = remainingObjectsMax < 0 ? 0 : Math.max(0, n - remainingObjectsMax);
        final int max = remainingObjectsMin < 0 ? n : Math.max(min, n - remainingObjectsMin);
        final List<T> ordered = pick("order", title + (top == null ? "" : " / " + top), sourceChoices, min, max, null);
        if (ordered.size() < min) {
            return new OrderResult<>(Lists.newArrayList(sourceChoices), false);
        }
        return new OrderResult<>(ordered, false);
    }

    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        return main.toFlatList();
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList,
            final DelayedReveal delayedReveal, final boolean isOptional) {
        final List<GameEntityView> list = new ArrayList<>(optionList);
        final List<GameEntityView> chosen = pick("entity", title, list, isOptional ? 0 : 1, 1, null);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList,
            final int min, final int max, final DelayedReveal delayedReveal) {
        return pick("entities", title, new ArrayList<GameEntityView>(optionList), min, max, null);
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards, final Iterable<CardView> manipulable,
            final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        return Lists.newArrayList(cards);
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes, final List<String> options) {
        final List<Integer> idx = decide("confirm", question, options, 1, 1, defaultIsYes ? "0" : "1");
        return idx != null && !idx.isEmpty() && idx.get(0) == 0;
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText, final String noButtonText,
            final boolean defaultYes) {
        final List<Integer> idx = decide("confirm", title + ": " + message, List.of(yesButtonText, noButtonText), 1, 1, defaultYes ? "0" : "1");
        return idx != null && !idx.isEmpty() && idx.get(0) == 0;
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options,
            final int defaultOption) {
        final List<Integer> idx = decide("option", title + ": " + message, options, 1, 1, String.valueOf(defaultOption));
        return idx == null || idx.isEmpty() ? defaultOption : idx.get(0);
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput,
            final List<String> inputOptions, final boolean isNumeric) {
        final PendingDecision d = new PendingDecision(decisionIds.incrementAndGet(), isNumeric ? "number" : "text",
                title + ": " + message, inputOptions == null ? List.of() : inputOptions, 0, 0, initialInput);
        pending = d;
        changed();
        try {
            final String text = d.awaitText();
            return text == null ? (initialInput == null ? "" : initialInput) : text;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return initialInput == null ? "" : initialInput;
        } finally {
            if (pending == d) {
                pending = null;
            }
            changed();
        }
    }

    /**
     * Divides combat damage without asking: lethal damage to each creature in the order given, and what is left
     * to the defending player if the assigning creature has trample, otherwise to the first creature. The host
     * treats a null answer as "assign another creature first" and asks again, so null stalls the game when this
     * is the only creature left to assign.
     */
    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage,
            final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        final Map<CardView, Integer> assigned = new LinkedHashMap<>();
        final CardStateView state = attacker.getCurrentState();
        final boolean deathtouch = state != null && state.hasDeathtouch();
        final boolean trample = state != null && state.hasTrample();
        int left = damage;
        for (final CardView blocker : blockers) {
            if (left <= 0) {
                break;
            }
            final int lethal = deathtouch ? 1 : Math.max(1, blocker.getLethalDamage());
            final int given = Math.min(left, lethal);
            assigned.put(blocker, given);
            left -= given;
        }
        if (left > 0) {
            if (blockers.isEmpty() || (trample && defender != null)) {
                assigned.put(null, left); // a null key is the defender
            } else {
                assigned.merge(blockers.get(0), left, Integer::sum);
            }
        }
        messages.add("Combat damage assigned for " + attacker + ": " + describe(assigned));
        changed();
        return assigned;
    }

    private static String describe(final Map<CardView, Integer> assigned) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<CardView, Integer> e : assigned.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getValue()).append(" to ").append(e.getKey() == null ? "the defender" : e.getKey().toString());
        }
        return sb.toString();
    }

    /**
     * Divides an amount (damage, counters, a shield) among targets without asking: one each first when every
     * target needs at least one, and the rest to the first target.
     */
    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target, final int amount,
            final boolean atLeastOne, final String amountLabel) {
        final Map<Object, Integer> assigned = new LinkedHashMap<>();
        if (target == null || target.isEmpty()) {
            return assigned;
        }
        int left = amount;
        for (final Object t : target.keySet()) {
            final int given = atLeastOne && left > 0 ? 1 : 0;
            assigned.put(t, given);
            left -= given;
        }
        if (left > 0) {
            final Object first = target.keySet().iterator().next();
            final Integer cap = target.get(first);
            final int extra = cap == null || cap <= 0 ? left : Math.min(left, Math.max(0, cap - assigned.get(first)));
            assigned.merge(first, extra, Integer::sum);
        }
        messages.add("Divided " + amount + " " + amountLabel + " for " + effectSource + " without asking");
        changed();
        return assigned;
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities, final ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            return abilities.get(0);
        }
        final List<SpellAbilityView> chosen = pick("ability", "Choose ability of " + hostCard, abilities, 0, 1, SpellAbilityView::toString);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    // ---- display ----

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
    }

    @Override
    public void setCard(final CardView card) {
    }

    @Override
    public void setPanelSelection(final CardView hostCard) {
    }

    @Override
    public void showCombat() {
        changed();
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        // The host's awaitNextInput arms a 250 ms timer that replaces the prompt with "Waiting for ..." and
        // disables the buttons; the desktop cancels it when the real prompt arrives, and so must we.
        cancelAwaitNextInput();
        promptMessage = message == null ? "" : message;
        promptCard = card;
        changed();
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1,
            final boolean enable2, final boolean focus1) {
        cancelAwaitNextInput();
        okLabel = label1;
        cancelLabel = label2;
        okEnabled = enable1;
        cancelEnabled = enable2;
        changed();
    }

    @Override
    public void flashIncorrectAction() {
        incorrectActions++;
        changed();
    }

    @Override
    public void alertUser() {
    }

    @Override
    public void finishGame() {
        gameFinished = true;
        changed();
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        this.myPlayers = myPlayers;
        gameFinished = false;
        messages.clear();
        gamesOpened++;
        changed();
    }

    @Override
    public void message(final String message, final String title) {
        messages.add(title + ": " + message);
        changed();
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        messages.add("ERROR " + title + ": " + message);
        changed();
    }

    @Override
    public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) {
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        return false;
    }
}
