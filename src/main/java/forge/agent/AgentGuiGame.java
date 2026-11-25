package forge.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventGameOutcome;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.Selection;
import forge.gamemodes.net.NetworkGuiGame;
import forge.gamemodes.net.client.FGameClient;
import forge.gui.FThreads;
import forge.interfaces.IGameController;
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
    private final Consumer<FGameClient.ReconnectState> onReconnectState;
    private final AtomicInteger decisionIds = new AtomicInteger();

    /** How long a closed selection may be followed by nothing before the seat is shown as waiting. */
    private static final long CLOSED_SELECTION_MS = 500;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "Agent-selection-timer");
        t.setDaemon(true);
        return t;
    });
    /** Counts selections, prompts and buttons, so the timer can tell whether anything followed a closed selection. */
    private final AtomicInteger displayChanges = new AtomicInteger();

    /**
     * The prompt and its buttons, replaced whole so a reader sees them together. {@code count} counts the
     * prompts the host has sent, so a caller can tell a new prompt from the one it has seen, whatever its
     * text. A prompt the host shows again keeps its count, see {@link #shownAgain}. {@code waiting} is true
     * from the end of one of the seat's inputs until the host's next prompt or buttons.
     */
    record Prompt(String message, CardView card, int count, boolean waiting, String okLabel, String cancelLabel,
            boolean okEnabled, boolean cancelEnabled) {
    }

    volatile Prompt prompt = new Prompt("", null, 0, false, "OK", "Cancel", false, false);

    /** Names for the two sets of stops the bridge starts with: Forge's desktop defaults, or every phase. */
    static final String STOPS_DEFAULT = "default";
    static final String STOPS_ALL = "all";
    static final String STOPS_CUSTOM = "custom";
    /** The phases with a label on Forge's desktop, where a player sets a stop. The untap step has none. */
    static final Set<PhaseType> STOPPABLE = Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(PhaseType.UNTAP)));
    static final Set<PhaseType> OWN_STOPS = Collections.unmodifiableSet(
            EnumSet.of(PhaseType.MAIN1, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.MAIN2));
    static final Set<PhaseType> OPPONENT_STOPS = Collections.unmodifiableSet(EnumSet.of(PhaseType.COMBAT_BEGIN,
            PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.END_OF_TURN));
    /** Where the host stops for the seat on an empty stack, in its own turn and in an opponent's. Replaced whole. */
    volatile Set<PhaseType> ownStops = OWN_STOPS;
    volatile Set<PhaseType> opponentStops = OPPONENT_STOPS;

    /** {@code default}, {@code all} or {@code custom}. */
    String stops() {
        final Set<PhaseType> own = ownStops;
        final Set<PhaseType> opponent = opponentStops;
        if (own.equals(OWN_STOPS) && opponent.equals(OPPONENT_STOPS)) {
            return STOPS_DEFAULT;
        }
        return own.equals(STOPPABLE) && opponent.equals(STOPPABLE) ? STOPS_ALL : STOPS_CUSTOM;
    }

    /** How long the bridge may take to answer a prompt for the seat before the seat is asked after all. */
    private static final long ANSWER_MS = 2500;
    /** Says whether the bridge answers a prompt for the seat. Asked on the Agent-EDT thread as the prompt arrives. */
    volatile java.util.function.Predicate<Prompt> answersItself = p -> false;
    /** The count of a prompt the bridge answers for the seat, or -1. The seat is shown as waiting on it. */
    volatile int answering = -1;
    /** The count of that prompt once its buttons have arrived with OK enabled, or -1. */
    volatile int answerReady = -1;
    /** How long a prompt that no button answers is kept back for its selection. */
    private static final long SELECTION_WAIT_MS = 250;
    /** The prompt being built. Written on the Agent-EDT thread only, like every display call. */
    private Prompt next = prompt;
    /** Counts the clicks and answers sent to the host. */
    private final AtomicInteger sends = new AtomicInteger();
    /** {@code sends} when the prompt with the current count arrived. Agent-EDT thread only. */
    private int sendsAtPrompt;
    /** The count of a prompt kept back for its selection, and of one shown without it when the wait ran out. Agent-EDT only. */
    private int held = -1;
    private int released = -1;
    private boolean batching;
    volatile boolean gameFinished;
    volatile int incorrectActions;
    volatile TrackableCollection<PlayerView> myPlayers;
    volatile PendingDecision pending;
    /** Counts games opened, so the session can tell a new game from the one it already set up. */
    volatile int gamesOpened;

    /**
     * The games won in the match by player name, as the host counted them when a game ended. {@code games} is
     * the number of games the count covers.
     */
    record Score(int games, Map<String, Integer> wins) {
    }

    /** Null until the first game of a match ends. */
    volatile Score score;
    /**
     * The lines the game log held when this game opened. Forge's client keeps one log through the games of a
     * match, so the lines before this are from earlier games.
     */
    volatile int logStart;
    /** True from the end of a connection until the next game opens. A prompt still queued from that connection is not asked. */
    private volatile boolean left;
    final List<String> messages = new CopyOnWriteArrayList<>();
    /** The players whose blocks the host has declared in the combat of turn {@code blocksTurn}. */
    private final Set<Integer> blocksDeclared = ConcurrentHashMap.newKeySet();
    private volatile int blocksTurn = -1;

    AgentGuiGame(final Runnable onChange, final Consumer<FGameClient.ReconnectState> onReconnectState) {
        this.onChange = onChange;
        this.onReconnectState = onReconnectState;
    }

    private void changed() {
        onChange.run();
    }

    private void publish() {
        if (batching) {
            return;
        }
        if (awaitsSelection()) {
            if (held != next.count) {
                held = next.count;
                final int count = next.count;
                TIMER.schedule(() -> FThreads.invokeInEdtNowOrLater(() -> {
                    if (held == count) {
                        released = count;
                        publish();
                    }
                }), SELECTION_WAIT_MS, TimeUnit.MILLISECONDS);
            }
            return;
        }
        held = -1;
        prompt = next;
        changed();
    }

    /**
     * True for a prompt the seat has not been shown that no button answers, while nothing is offered to pick.
     * Forge sends an input's prompt and buttons and then its selection (InputBase.showMessageInitial), so a
     * target prompt read between the two named no target. It is kept back until the selection arrives or
     * {@link #SELECTION_WAIT_MS} has passed.
     */
    private boolean awaitsSelection() {
        return next.count != prompt.count && released != next.count && !next.waiting && !next.okEnabled && !next.cancelEnabled
                && !offers(getSelection()) && !gameFinished && !left;
    }

    // ---- decisions ----

    /** Parks this thread until the seat answers. False when interrupted, or when the connection ended first. */
    private boolean ask(final PendingDecision d) {
        pending = d;
        if (left) {
            d.drop();
        }
        changed();
        try {
            d.await();
            return !d.isDropped();
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

    /** Called before a click goes to the host. */
    void sent() {
        sends.incrementAndGet();
    }

    boolean answer(final int id, final List<Integer> indices, final String text) {
        final PendingDecision d = pending;
        if (d == null || d.id != id || d.isAnswered()) {
            return false;
        }
        sent();
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

    /** The desktop waits 250 ms before it shows "Waiting for ..." so its buttons do not flicker. Nothing is drawn here. */
    @Override
    public void awaitNextInput() {
        // Forge writes "Waiting for ..." as a prompt and then its buttons. Published as one change, and not
        // counted, since the host sent no prompt.
        final int prompts = next.count;
        batching = true;
        try {
            updatePromptForAwait(getCurrentPlayer());
        } finally {
            batching = false;
        }
        next = new Prompt(next.message, next.card, prompts, true, next.okLabel, next.cancelLabel, next.okEnabled, next.cancelEnabled);
        publish();
    }

    /**
     * A search of the library ends without the host sending "Waiting for ..." (Forge's InputSelectEntitiesFromList
     * does not ask for it), so the search prompt stayed while the host waited on the other player (#19). When a
     * selection that offered cards or players closes and no prompt, buttons or selection follow, the seat is
     * shown as waiting, as awaitNextInput would.
     */
    @Override
    public void setSelection(final Selection sel) {
        final Selection prev = getSelection();
        super.setSelection(sel);
        if (held == next.count && offers(sel)) {
            publish();
        }
        final int stamp = displayChanges.incrementAndGet();
        if (offers(prev) && !offers(sel)) {
            TIMER.schedule(() -> FThreads.invokeInEdtNowOrLater(() -> {
                if (displayChanges.get() == stamp && !next.waiting() && !left && !gameFinished) {
                    awaitNextInput();
                }
            }), CLOSED_SELECTION_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static boolean offers(final Selection sel) {
        return sel != null && (!sel.cards().isEmpty() || !sel.targets().isEmpty());
    }

    /**
     * Forge's InputAttack shows its prompt twice when it starts: showMessage reaches updateMessage through
     * setCurrentDefender and then calls it again. A seat that read the first one had its next action refused
     * as stale. The prompt that is up, shown again with the same text and card, is the same prompt when the
     * seat is being asked (OK is enabled) and the bridge has sent no click or answer since it arrived: only
     * the seat's own click ends or changes an input the host waits on. The priority prompt always counts,
     * because the host can pass it for the seat: on a yield or stop sent earlier, or when its timer runs out.
     */
    private boolean shownAgain(final String message, final CardView card) {
        return !next.waiting && next.okEnabled && sends.get() == sendsAtPrompt && message.equals(next.message)
                && Objects.equals(card, next.card) && !message.startsWith(AgentSession.priorityPrefix());
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        displayChanges.incrementAndGet();
        // Cancels the host's 250 ms timer that would replace this prompt with "Waiting for ...", as the desktop does.
        cancelAwaitNextInput();
        final String text = message == null ? "" : message;
        final boolean again = shownAgain(text, card);
        if (!again) {
            sendsAtPrompt = sends.get();
        }
        next = new Prompt(text, card, again ? next.count : next.count + 1, false, next.okLabel, next.cancelLabel,
                next.okEnabled, next.cancelEnabled);
        // The priority prompt arrives as its selection, its text and then its buttons (InputPassPriority).
        answerReady = -1;
        answering = answersItself.test(next) ? next.count : -1;
        if (answering >= 0) {
            final int count = next.count;
            TIMER.schedule(() -> FThreads.invokeInEdtNowOrLater(() -> {
                if (answering == count) {
                    answering = -1;
                    changed();
                }
            }), ANSWER_MS, TimeUnit.MILLISECONDS);
        }
        publish();
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1,
            final boolean enable2, final boolean focus1) {
        displayChanges.incrementAndGet();
        cancelAwaitNextInput();
        next = new Prompt(next.message, next.card, next.count, false, label1, label2, enable1, enable2);
        if (answering == next.count && enable1) {
            answerReady = next.count;
        }
        publish();
    }

    /**
     * The combat view does not tell an unblocked attacker from one whose blocks are still being chosen: it
     * lists no declared blockers for either. The host sends this event when a player's blocks are final.
     * With the result of a game the host sends the match score as text, which is all a client gets of it.
     */
    @Override
    public void handleGameEvent(final GameEvent event) {
        final GameView view = getGameView();
        if (event instanceof GameEventGameOutcome outcome && view != null) {
            final Map<String, Integer> wins = Snapshots.wins(outcome.matchSummary(), view);
            score = wins == null ? null : new Score(view.getNumPlayedGamesInMatch() + 1, wins);
            changed();
        }
        if (event instanceof GameEventAttackersDeclared) {
            blocksDeclared.clear();
        } else if (event instanceof GameEventBlockersDeclared && ((GameEventBlockersDeclared) event).defendingPlayer() != null) {
            final GameView gv = getGameView();
            final int turn = gv == null ? -1 : gv.getTurn();
            if (turn != blocksTurn) {
                blocksDeclared.clear();
                blocksTurn = turn;
            }
            blocksDeclared.add(((GameEventBlockersDeclared) event).defendingPlayer().getId());
            changed();
        }
        super.handleGameEvent(event);
    }

    /** True once the host has said this player's blocks are final in this turn's current combat. */
    boolean blocksDeclared(final int turn, final int playerId) {
        return turn == blocksTurn && blocksDeclared.contains(playerId);
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

    /**
     * Forgets the game when its connection ends. Forge's client keeps one tracker per connection and merges a
     * new game view into the one held here. A view kept from an earlier connection is then split between two
     * trackers. Once the host had sent its whole view again, the zones here stopped following the host.
     */
    void leaveGame() {
        left = true;
        final PendingDecision d = pending;
        if (d != null) {
            d.drop();
        }
        FThreads.invokeInEdtAndWait(() -> {
            setGameView(null);
            resetForNewMatch();
            afterGameEnd();
            myPlayers = null;
            logStart = 0;
            gameFinished = false;
            blocksDeclared.clear();
            next = new Prompt("", null, next.count, false, "OK", "Cancel", false, false);
            prompt = next;
        });
        messages.clear();
        changed();
    }

    /** Forge's client reconnects on a new connection, and the host then sends the game again. */
    @Override
    public void onReconnectStateChanged(final FGameClient.ReconnectState state, final int attemptIndex, final int nextDelaySeconds) {
        if (state != FGameClient.ReconnectState.CONNECTED) {
            leaveGame();
        } else {
            changed();
        }
        onReconnectState.accept(state);
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        left = false;
        this.myPlayers = myPlayers;
        gameFinished = false;
        // The host starts each game believing we hold no selection and sends one only when it changes, so what
        // a prompt offered when the last game ended would stay, with card ids the new game does not have.
        setSelection(Selection.NONE);
        messages.clear();
        blocksDeclared.clear();
        final GameView gv = getGameView();
        if (gv != null && gv.getNumPlayedGamesInMatch() == 0) {
            score = null; // the first game of a match
        }
        logStart = gv == null || gv.getGameLog() == null ? 0 : gv.getGameLog().getAllEntries().size();
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

    /**
     * The desktop answers this from its phase labels, which start at Forge's preferences PHASE_HUMAN_* for the
     * player's own turn and PHASE_AI_* for an opponent's. The untap step has no label there.
     */
    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        final PlayerView me = getCurrentPlayer();
        if (playerTurn == null || phase == null || phase == PhaseType.UNTAP || me == null) {
            return false;
        }
        return !(me.getId() == playerTurn.getId() ? ownStops : opponentStops).contains(phase);
    }

    /**
     * Sends the host the phases to skip for the seat, as the desktop does when a game opens. The host keeps
     * them per game and has none until a client sends them. False while the game view has no players yet.
     */
    boolean seedStops() {
        final GameView gv = getGameView();
        final IGameController c = getGameController();
        if (gv == null || gv.getPlayers() == null || getCurrentPlayer() == null || c == null) {
            return false;
        }
        // The seed carries the auto-yields and trigger answers saved in this machine's Forge profile. Switched
        // off first, so the seed says so and the host asks the seat. The bridge's own yields do not read this.
        c.setDisableAutoYields(true);
        c.setDisableAutoTriggers(true);
        seedYieldStateOnHost();
        return true;
    }
}
