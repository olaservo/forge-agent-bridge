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
import forge.gamemodes.match.Selection;
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

    /** Parks this thread until the seat answers. False when interrupted. */
    private boolean ask(final PendingDecision d) {
        pending = d;
        changed();
        try {
            d.await();
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (pending == d) {
                pending = null;
            }
            changed();
        }
    }

    private List<Integer> decide(final String kind, final String prompt, final List<String> options,
            final int min, final int max, final String defaultAnswer) {
        final PendingDecision d = new PendingDecision(decisionIds.incrementAndGet(), kind, prompt, options, min, max, defaultAnswer);
        return ask(d) ? d.indices() : List.of();
    }

    private static <T> List<String> labels(final List<T> choices, final FSerializableFunction<T, String> display) {
        final List<String> out = new ArrayList<>();
        if (choices != null) {
            for (final T c : choices) {
                out.add(display != null ? display.apply(c) : String.valueOf(c));
            }
        }
        return out;
    }

    private <T> List<T> pick(final String kind, final String prompt, final List<T> choices, final int min, final int max,
            final FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) {
            return new ArrayList<>();
        }
        final List<Integer> idx = decide(kind, prompt, labels(choices, display), min, Math.min(max, choices.size()), null);
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
            // A reveal: the host expects nothing back, and asking the seat would hold the host until it answered.
            messages.add("Revealed, " + message + ": " + String.join(", ", labels(choices, display)));
            changed();
            return new ArrayList<>();
        }
        return pick("choices", message, choices, min, max, display);
    }

    @Override
    public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax,
            final List<T> sourceChoices, final List<T> destChoices, final CardView referenceCard, final boolean sideboardingMode,
            final boolean showRememberCheckbox) {
        // The host says how many may be left unchosen, -1 for any number. Scry asks this way for the bottom cards.
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
        // A desktop host sends scry here: cards is the whole library, manipulable the cards looked at.
        // The answer is the library in its new order.
        final List<CardView> all = Lists.newArrayList(cards);
        final List<CardView> looked = Lists.newArrayList(manipulable);
        if (looked.isEmpty()) {
            return all;
        }
        final int n = looked.size();
        final List<CardView> top = pick("order", title + " / Choose the cards to keep on top, the first one topmost. The rest go to the bottom.",
                looked, toBottom ? 0 : n, toTop ? n : 0, null);
        final List<CardView> out = new ArrayList<>(top);
        for (final CardView c : all) {
            if (!looked.contains(c)) {
                out.add(c);
            }
        }
        for (final CardView c : looked) {
            if (!top.contains(c)) {
                out.add(c);
            }
        }
        return out;
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
        final String text = ask(d) ? d.text() : null;
        if (text != null) {
            return text;
        }
        return initialInput == null ? "" : initialInput;
    }

    /** Says why a split is not allowed, or null when it is. */
    private interface SplitRule {
        String refuse(int[] split);
    }

    /**
     * Asks the seat to split an amount among the options, as an "assign" decision answered with one number per
     * option. With one option there is nothing to choose and the fallback is used. An answer that does not add
     * up or breaks the rule is refused: the fallback is used and a message says why.
     */
    private int[] split(final String prompt, final List<String> options, final int amount, final int[] fallback, final SplitRule rule) {
        if (options.size() < 2 || amount <= 0) {
            return fallback;
        }
        final PendingDecision d = new PendingDecision(decisionIds.incrementAndGet(), "assign", prompt, options, 0, amount,
                join(fallback), amount);
        if (!ask(d)) {
            return fallback;
        }
        String why;
        final int[] answer = new int[options.size()];
        try {
            final String[] parts = d.text() == null ? new String[0] : d.text().trim().split("[,\\s]+");
            int sum = 0;
            why = parts.length == answer.length ? null : "expected " + answer.length + " numbers";
            for (int i = 0; why == null && i < parts.length; i++) {
                answer[i] = Integer.parseInt(parts[i]);
                sum += answer[i];
                if (answer[i] < 0) {
                    why = "a negative number";
                }
            }
            if (why == null && sum != amount) {
                why = "the numbers add up to " + sum + ", not " + amount;
            }
            if (why == null) {
                why = rule.refuse(answer);
            }
        } catch (final NumberFormatException e) {
            why = "not a list of numbers";
        }
        if (why == null) {
            return answer;
        }
        messages.add("Split refused (" + why + "), used " + join(fallback) + " for: " + prompt);
        changed();
        return fallback;
    }

    private static String join(final int[] numbers) {
        final StringBuilder sb = new StringBuilder();
        for (final int n : numbers) {
            sb.append(sb.length() > 0 ? "," : "").append(n);
        }
        return sb.toString();
    }

    /**
     * Asks the seat how an attacker's combat damage is split when it has more than one place to go: several
     * blockers, or a blocker and the defender for an attacker with trample. The default is lethal to each
     * blocker in order and the rest to the defender with trample, otherwise to the first blocker. Never
     * null: the host asks again on null, which stalls the game.
     */
    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage,
            final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        final CardStateView state = attacker.getCurrentState();
        final boolean deathtouch = state != null && state.hasDeathtouch();
        final boolean trample = state != null && state.hasTrample();
        final boolean toDefender = blockers.isEmpty() || (trample && defender != null);
        final int n = blockers.size();
        final int[] lethal = new int[n];
        final int[] fallback = new int[n + (toDefender ? 1 : 0)];
        final List<String> options = new ArrayList<>();
        int left = damage;
        for (int i = 0; i < n; i++) {
            lethal[i] = deathtouch ? 1 : Math.max(1, blockers.get(i).getLethalDamage());
            fallback[i] = Math.min(left, lethal[i]);
            left -= fallback[i];
            options.add(blockers.get(i) + ", lethal damage " + lethal[i]);
        }
        if (toDefender) {
            fallback[n] = left;
            options.add(defender == null ? "the defender" : String.valueOf(defender));
        } else if (left > 0) {
            fallback[0] += left;
        }
        final int[] chosen = split("Assign " + damage + " combat damage from " + attacker
                + (toDefender && n > 0 ? ". The defender can take damage only once every blocker has lethal damage." : "."),
                options, damage, fallback, s -> {
                    if (toDefender && n > 0 && s[n] > 0) {
                        for (int i = 0; i < n; i++) {
                            if (s[i] < lethal[i]) {
                                return "damage to the defender before lethal damage to every blocker";
                            }
                        }
                    }
                    return null;
                });
        final Map<CardView, Integer> assigned = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (chosen[i] > 0) {
                assigned.put(blockers.get(i), chosen[i]);
            }
        }
        if (toDefender && chosen[n] > 0) {
            assigned.put(null, chosen[n]); // a null key is the defender
        }
        return assigned;
    }

    /**
     * Asks the seat how to divide an amount (damage, counters, a shield) among targets. The default is one each
     * first when every target needs at least one, and the rest to the first target.
     */
    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target, final int amount,
            final boolean atLeastOne, final String amountLabel) {
        final Map<Object, Integer> assigned = new LinkedHashMap<>();
        if (target == null || target.isEmpty()) {
            return assigned;
        }
        final List<Object> targets = new ArrayList<>(target.keySet());
        final int n = targets.size();
        final int[] cap = new int[n];
        final int[] fallback = new int[n];
        final List<String> options = new ArrayList<>();
        int left = amount;
        for (int i = 0; i < n; i++) {
            final Integer c = target.get(targets.get(i));
            cap[i] = c == null || c <= 0 ? Integer.MAX_VALUE : c;
            fallback[i] = atLeastOne && left > 0 ? 1 : 0;
            left -= fallback[i];
            options.add(targets.get(i) + (cap[i] == Integer.MAX_VALUE ? "" : ", at most " + cap[i]));
        }
        fallback[0] += Math.min(left, cap[0] - fallback[0]);
        final int[] chosen = split("Divide " + amount + " " + amountLabel + " for " + effectSource
                + (atLeastOne ? ". Every target gets at least 1." : "."), options, amount, fallback, s -> {
                    for (int i = 0; i < n; i++) {
                        if (atLeastOne && s[i] < 1) {
                            return "every target needs at least 1";
                        }
                        if (s[i] > cap[i]) {
                            return "more than " + cap[i] + " for one target";
                        }
                    }
                    return null;
                });
        for (int i = 0; i < n; i++) {
            assigned.put(targets.get(i), chosen[i]);
        }
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
        // Cancels the host's 250 ms timer that would replace this prompt with "Waiting for ...", as the desktop does.
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
        // The host starts each game believing we hold no selection and sends one only when it changes, so what
        // a prompt offered when the last game ended would stay, with card ids the new game does not have.
        setSelection(Selection.NONE);
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
