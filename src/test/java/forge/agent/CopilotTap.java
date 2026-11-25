package forge.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
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
 * Spike: the host's GUI object for one remote seat, recording what the host sends that seat and what
 * the seat answers. Every override calls super with the same arguments and returns its result.
 */
final class CopilotTap extends RemoteClientGuiGame {

    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final Map<String, Integer> counts = new ConcurrentHashMap<>();
    final Map<String, Integer> events = new ConcurrentHashMap<>();
    volatile String lastPrompt = "";
    volatile String lastButtons = "";

    CopilotTap(final RemoteClient client) {
        super(client);
    }

    private void rec(final String method, final Object detail) {
        try {
            counts.merge(method, 1, Integer::sum);
            String text = String.valueOf(detail).replace('\n', '|');
            if (text.length() > 240) {
                text = text.substring(0, 240) + "...";
            }
            final String line = method + " [" + Thread.currentThread().getName() + "] " + text;
            calls.add(line);
            System.out.println("[tap] " + line);
        } catch (final RuntimeException e) {
            // a tap must not throw on the game thread
        }
    }

    @Override
    public void setGameView(final GameView gameView) {
        rec("setGameView", gameView == null ? "null" : "GameView@" + Integer.toHexString(System.identityHashCode(gameView)));
        super.setGameView(gameView);
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        rec("openView", myPlayers);
        super.openView(myPlayers);
    }

    @Override
    public void updateGameView() {
        counts.merge("updateGameView", 1, Integer::sum);
        super.updateGameView();
    }

    @Override
    public void sendFullState() {
        rec("sendFullState", "");
        super.sendFullState();
    }

    @Override
    public void afterGameEnd() {
        rec("afterGameEnd", "");
        super.afterGameEnd();
    }

    @Override
    public void finishGame() {
        rec("finishGame", "");
        super.finishGame();
    }

    @Override
    public void showCombat() {
        rec("showCombat", "");
        super.showCombat();
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        lastPrompt = message;
        rec("showPromptMessage", "player=" + playerView + " card=" + card + " message=" + message);
        super.showPromptMessage(playerView, message, card);
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1, final boolean enable2, final boolean focus1) {
        lastButtons = label1 + "(" + enable1 + ")/" + label2 + "(" + enable2 + ")";
        rec("updateButtons", "owner=" + owner + " " + lastButtons);
        super.updateButtons(owner, label1, label2, enable1, enable2, focus1);
    }

    @Override
    public void awaitNextInput() {
        rec("awaitNextInput", "");
        super.awaitNextInput();
    }

    @Override
    public void updateAutoPassPrompt() {
        rec("updateAutoPassPrompt", "");
        super.updateAutoPassPrompt();
    }

    @Override
    public void flashIncorrectAction() {
        rec("flashIncorrectAction", "");
        super.flashIncorrectAction();
    }

    @Override
    public void setSelection(final Selection selection) {
        rec("setSelection", selection);
        super.setSelection(selection);
    }

    @Override
    public void setCard(final CardView card) {
        rec("setCard", card);
        super.setCard(card);
    }

    @Override
    public void showManaPool(final PlayerView player) {
        rec("showManaPool", player);
        super.showManaPool(player);
    }

    @Override
    public void showRevealedCards(final Iterable<CardView> cards) {
        rec("showRevealedCards", cards);
        super.showRevealedCards(cards);
    }

    @Override
    public void hideRevealedCards() {
        rec("hideRevealedCards", "");
        super.hideRevealedCards();
    }

    @Override
    public void message(final String message, final String title) {
        rec("message", title + ": " + message);
        super.message(message, title);
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        rec("showErrorDialog", title + ": " + message);
        super.showErrorDialog(message, title);
    }

    @Override
    public void handleGameEvents(final List<GameEvent> batch) {
        final List<String> names = new ArrayList<>();
        for (final GameEvent e : batch) {
            names.add(e.getClass().getSimpleName());
            events.merge(e.getClass().getSimpleName(), 1, Integer::sum);
        }
        rec("handleGameEvents", names);
        super.handleGameEvents(batch);
    }

    // ---- calls that block the game thread on the seat's reply ----

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities, final ITriggerEvent triggerEvent) {
        rec("getAbilityToPlay", hostCard + " " + abilities);
        final SpellAbilityView answer = super.getAbilityToPlay(hostCard, abilities, triggerEvent);
        rec("getAbilityToPlay.answer", answer);
        return answer;
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage, final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        rec("assignCombatDamage", attacker + " -> " + blockers + " damage=" + damage);
        final Map<CardView, Integer> answer = super.assignCombatDamage(attacker, blockers, damage, defender, overrideOrder, maySkip);
        rec("assignCombatDamage.answer", answer);
        return answer;
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText, final String noButtonText, final boolean defaultYes) {
        rec("showConfirmDialog", title + ": " + message + " [" + yesButtonText + "/" + noButtonText + "]");
        final boolean answer = super.showConfirmDialog(message, title, yesButtonText, noButtonText, defaultYes);
        rec("showConfirmDialog.answer", answer);
        return answer;
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options, final int defaultOption) {
        rec("showOptionDialog", title + ": " + message + " " + options);
        final int answer = super.showOptionDialog(message, title, icon, options, defaultOption);
        rec("showOptionDialog.answer", answer);
        return answer;
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        rec("showInputDialog", title + ": " + message + " " + inputOptions);
        final String answer = super.showInputDialog(message, title, icon, initialInput, inputOptions, isNumeric);
        rec("showInputDialog.answer", answer);
        return answer;
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes, final List<String> options) {
        rec("confirm", "card=" + c + " question=" + question + " options=" + options);
        final boolean answer = super.confirm(c, question, defaultIsYes, options);
        rec("confirm.answer", answer);
        return answer;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices, final List<T> selected, final FSerializableFunction<T, String> display) {
        rec("getChoices", message + " min=" + min + " max=" + max + " choices=" + choices);
        final List<T> answer = super.getChoices(message, min, max, choices, selected, display);
        rec("getChoices.answer", answer);
        return answer;
    }

    @Override
    public <T> IGuiGame.OrderResult<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices, final CardView referenceCard, final boolean sideboardingMode, final boolean showRememberCheckbox) {
        rec("order", title + " source=" + sourceChoices + " dest=" + destChoices);
        final IGuiGame.OrderResult<T> answer = super.order(title, top, remainingObjectsMin, remainingObjectsMax, sourceChoices, destChoices, referenceCard, sideboardingMode, showRememberCheckbox);
        rec("order.answer", answer);
        return answer;
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList, final DelayedReveal delayedReveal, final boolean isOptional) {
        rec("chooseSingleEntityForEffect", title + " " + optionList);
        final GameEntityView answer = super.chooseSingleEntityForEffect(title, optionList, delayedReveal, isOptional);
        rec("chooseSingleEntityForEffect.answer", answer);
        return answer;
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList, final int min, final int max, final DelayedReveal delayedReveal) {
        rec("chooseEntitiesForEffect", title + " " + optionList);
        final List<GameEntityView> answer = super.chooseEntitiesForEffect(title, optionList, min, max, delayedReveal);
        rec("chooseEntitiesForEffect.answer", answer);
        return answer;
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards, final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        rec("manipulateCardList", title + " cards=" + cards + " manipulable=" + manipulable);
        final List<CardView> answer = super.manipulateCardList(title, cards, manipulable, toTop, toBottom, toAnywhere);
        rec("manipulateCardList.answer", answer);
        return answer;
    }
}
