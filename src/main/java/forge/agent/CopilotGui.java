package forge.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.DelayedReveal;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.Selection;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.skin.FSkinProp;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

/**
 * The host's GUI object for one remote seat, keeping a copy of what the host shows that seat: the prompt,
 * the buttons, the selection, the dialog the game is waiting on and the last reveal. Every override
 * passes the call on unchanged. It sends nothing of its own and has no way to act for the seat.
 *
 * <p>The host calls it from its game thread, its GUI thread and pool threads, and an HTTP thread reads it.
 * Each kept value is an immutable copy in a volatile field, filtered for the seat when the call arrives.
 */
final class CopilotGui extends RemoteClientGuiGame {

    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong promptCount = new AtomicLong();
    private volatile PlayerView viewer;
    private volatile String prompt = "";
    private volatile String promptCard;
    private volatile boolean waiting;
    private volatile Map<String, Object> buttons = buttons("OK", "Cancel", false, false);
    private volatile Map<String, Object> selection = Map.of();
    private volatile Map<String, Object> dialog;
    private volatile Map<String, Object> lastAnswer;
    private volatile Map<String, Object> lastReveal;

    CopilotGui(final RemoteClient client) {
        super(client);
    }

    /** What the seat is shown now, and the game as that player may see it. */
    Map<String, Object> snapshot() {
        final Map<String, Object> m = new LinkedHashMap<>();
        final PlayerView me = viewer;
        final GameView gv = getGameView();
        m.put("inGame", gv != null && me != null && !gv.isGameOver());
        m.put("revision", revision.get());
        m.put("prompt", prompt);
        m.put("promptCount", promptCount.get());
        m.put("waiting", waiting);
        m.put("promptCard", promptCard);
        m.put("buttons", buttons);
        m.put("selection", selection);
        m.put("dialog", dialog);
        m.put("lastAnswer", lastAnswer);
        m.put("lastReveal", lastReveal);
        if (gv == null || me == null) {
            return m;
        }
        // The game thread changes the view while this reads it. A read that trips over a change is tried again.
        for (int attempt = 0;; attempt++) {
            try {
                final Map<String, Object> board = new LinkedHashMap<>();
                CopilotView.board(board, gv, me);
                CopilotView.log(board, gv);
                m.putAll(board);
                return m;
            } catch (final RuntimeException e) {
                if (attempt == 4) {
                    m.put("boardError", e.toString());
                    return m;
                }
            }
        }
    }

    long revision() {
        return revision.get();
    }

    private static Map<String, Object> buttons(final String ok, final String cancel, final boolean okEnabled, final boolean cancelEnabled) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("okEnabled", okEnabled);
        m.put("cancel", cancel);
        m.put("cancelEnabled", cancelEnabled);
        return m;
    }

    /** Runs a recording step. A fault in it must not reach the game. */
    private void keep(final Runnable step) {
        try {
            step.run();
            revision.incrementAndGet();
        } catch (final RuntimeException e) {
            System.err.println("[copilot] " + e);
        }
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        keep(() -> viewer = myPlayers == null || myPlayers.isEmpty() ? null : myPlayers.get(0));
        super.openView(myPlayers);
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        keep(() -> {
            prompt = message == null ? "" : message;
            promptCard = CopilotView.name(card, viewer);
            waiting = false;
            promptCount.incrementAndGet();
        });
        super.showPromptMessage(playerView, message, card);
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1, final boolean enable2, final boolean focus1) {
        keep(() -> buttons = buttons(label1, label2, enable1, enable2));
        super.updateButtons(owner, label1, label2, enable1, enable2, focus1);
    }

    @Override
    public void awaitNextInput() {
        keep(() -> waiting = true);
        super.awaitNextInput();
    }

    @Override
    public void setSelection(final Selection sel) {
        keep(() -> selection = CopilotView.selection(sel, viewer));
        super.setSelection(sel);
    }

    @Override
    public void showRevealedCards(final Iterable<CardView> cards) {
        // The host shows these cards to the seat, so they are listed whatever zone they are in.
        keep(() -> {
            final List<Object> names = new ArrayList<>();
            for (final CardView cv : cards) {
                names.add(String.valueOf(cv));
            }
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("cards", names);
            m.put("revision", revision.get() + 1);
            lastReveal = m;
        });
        super.showRevealedCards(cards);
    }

    // ---- calls that block the game on the seat's reply ----

    /** Keeps the dialog while the seat answers it, and the answer after. Without options the answer is kept as given. */
    private <T> T ask(final String kind, final String question, final Iterable<?> options, final int min, final int max, final boolean keepAnswer, final Supplier<T> call) {
        keep(() -> {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("prompt", question);
            m.put("options", CopilotView.labels(options, viewer));
            m.put("min", min);
            m.put("max", max);
            dialog = m;
        });
        T answer = null;
        try {
            answer = call.get();
            return answer;
        } finally {
            final T given = answer;
            keep(() -> {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", kind);
                m.put("prompt", question);
                m.put("answer", keepAnswer ? CopilotView.label(given, viewer) : null);
                lastAnswer = m;
                dialog = null;
            });
        }
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities, final ITriggerEvent triggerEvent) {
        return ask("ability", String.valueOf(CopilotView.label(hostCard, viewer)), abilities, 1, 1, true,
                () -> super.getAbilityToPlay(hostCard, abilities, triggerEvent));
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage, final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        return ask("assignCombatDamage", CopilotView.label(attacker, viewer) + " assigns " + damage, blockers, 0, damage, false,
                () -> super.assignCombatDamage(attacker, blockers, damage, defender, overrideOrder, maySkip));
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> targets, final int amount, final boolean atLeastOne, final String amountLabel) {
        return ask("assignGenericAmount", CopilotView.label(effectSource, viewer) + " assigns " + amount + " " + amountLabel,
                targets == null ? null : targets.keySet(), 0, amount, false,
                () -> super.assignGenericAmount(effectSource, targets, amount, atLeastOne, amountLabel));
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText, final String noButtonText, final boolean defaultYes) {
        return ask("confirm", message, List.of(String.valueOf(yesButtonText), String.valueOf(noButtonText)), 1, 1, true,
                () -> super.showConfirmDialog(message, title, yesButtonText, noButtonText, defaultYes));
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options, final int defaultOption) {
        return ask("option", message, options, 1, 1, true, () -> super.showOptionDialog(message, title, icon, options, defaultOption));
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        return ask("input", message, inputOptions, 1, 1, true, () -> super.showInputDialog(message, title, icon, initialInput, inputOptions, isNumeric));
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes, final List<String> options) {
        return ask("confirm", question, options, 1, 1, true, () -> super.confirm(c, question, defaultIsYes, options));
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices, final List<T> selected, final FSerializableFunction<T, String> display) {
        return ask("choices", message, choices, min, max, true, () -> super.getChoices(message, min, max, choices, selected, display));
    }

    @Override
    public <T> IGuiGame.OrderResult<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices, final CardView referenceCard, final boolean sideboardingMode, final boolean showRememberCheckbox) {
        return ask("order", title, sourceChoices, remainingObjectsMin, remainingObjectsMax, false,
                () -> super.order(title, top, remainingObjectsMin, remainingObjectsMax, sourceChoices, destChoices, referenceCard, sideboardingMode, showRememberCheckbox));
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList, final DelayedReveal delayedReveal, final boolean isOptional) {
        return ask("entity", title, optionList, isOptional ? 0 : 1, 1, true, () -> super.chooseSingleEntityForEffect(title, optionList, delayedReveal, isOptional));
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList, final int min, final int max, final DelayedReveal delayedReveal) {
        return ask("entities", title, optionList, min, max, true, () -> super.chooseEntitiesForEffect(title, optionList, min, max, delayedReveal));
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards, final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        // "cards" is the whole zone in order and so is the answer. The seat is shown the manipulable ones.
        return ask("manipulateCardList", title, manipulable, 0, -1, false, () -> super.manipulateCardList(title, cards, manipulable, toTop, toBottom, toAnywhere));
    }
}
