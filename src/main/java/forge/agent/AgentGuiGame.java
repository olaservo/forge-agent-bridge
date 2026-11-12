package forge.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.card.CardView;
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
        return pick("choices", message, choices, min, max, display);
    }

    @Override
    public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax,
            final List<T> sourceChoices, final List<T> destChoices, final CardView referenceCard, final boolean sideboardingMode,
            final boolean showRememberCheckbox) {
        final List<T> ordered = pick("order", title + (top == null ? "" : " / " + top), sourceChoices,
                sourceChoices.size(), sourceChoices.size(), null);
        if (ordered.size() != sourceChoices.size()) {
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

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage,
            final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        return null; // host assigns in damage order
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target, final int amount,
            final boolean atLeastOne, final String amountLabel) {
        return null; // host distributes
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
