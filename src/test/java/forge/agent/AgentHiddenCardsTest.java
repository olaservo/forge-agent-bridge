package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Nothing the seat may not see is in the game the bridge answers with. Forge 2.0.16 sends a client every card
 * with its name, the opponent's hand and both libraries included, and its own client decides what to show.
 *
 * The host AI's deck has card names the seat's deck does not. While the host waits on the seat, the whole
 * {@code GET /game} body and the game log are searched for the name of every card that is only in a hidden
 * place on the host: the host player's hand, its library, and cards that are face down. The ids of those
 * cards, of a face-down card in exile, and of the seat's own library below a top card it may look at, are
 * not the id of any card in the body. The test puts one of the host's cards onto the battlefield face down
 * and exiles another face down, as a morph and a face-down exile do, since the AI cannot be made to.
 */
public class AgentHiddenCardsTest {

    private static final String FRENZY = "Experimental Frenzy";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Hidden Cards Test",
            "[Main]",
            "20 Mountain",
            "20 " + FRENZY);
    /** Creatures the host AI cannot cast in the turns this game lasts, so their names stay hidden. */
    private static final String[] HOST_DECK = {"16 Forest", "4 Enormous Baloth", "4 Scaled Wurm", "4 Plated Slagwurm",
        "4 Pelakka Wurm", "4 Axebane Stag", "4 Duskdale Wurm"};
    private static final Pattern TARGET_ID = Pattern.compile("#(\\d+)\"");

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("hidden");
        }
    }

    @Test(timeOut = 300_000)
    public void noHiddenCardIsInTheGameTheSeatReads() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host hidden cards", HOST_DECK));
        final long deadline = System.currentTimeMillis() + 240_000;
        Card faceDown = null;
        Card exiled = null;
        boolean sawHand = false;
        boolean sawFaceDown = false;
        boolean sawExiled = false;
        boolean sawBelowTop = false;
        while (!(sawHand && sawFaceDown && sawExiled && sawBelowTop)) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "not every case was seen in time: hand " + sawHand + ", face down " + sawFaceDown
                    + ", exiled " + sawExiled + ", below the top " + sawBelowTop + "; last state " + g);
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final JsonObject host = RiggedMatch.player(g, false);
            final boolean asked = me != null && g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g);
            if (asked && match.hostWaitsOnSeat()) {
                // The host's game does not move now, and nothing in this deck returns a public card to a hidden place.
                final Game game = match.lobby.getHostedMatch().getGame();
                final Player hostPlayer = player(game, RiggedMatch.HOST);
                final String body = g.toString();
                final String log = match.http.get("/game/log", Map.of("since", "0")).toString();
                final Set<Integer> shown = new HashSet<>();
                cardIds(g, shown);
                final Matcher targets = TARGET_ID.matcher(body);
                while (targets.find()) {
                    shown.add(Integer.parseInt(targets.group(1)));
                }

                final Set<String> open = new HashSet<>(Set.of("Mountain", FRENZY));
                for (final ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Stack, ZoneType.Exile)) {
                    for (final Card c : game.getCardsIn(zone)) {
                        if (!c.isFaceDown()) {
                            open.add(c.getPaperCard().getName());
                        }
                    }
                }
                int searched = 0;
                for (final ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library, ZoneType.Battlefield, ZoneType.Exile)) {
                    for (final Card c : hostPlayer.getCardsIn(zone)) {
                        final boolean hiddenZone = zone == ZoneType.Hand || zone == ZoneType.Library;
                        if (!hiddenZone && !c.isFaceDown()) {
                            continue;
                        }
                        final String name = c.getPaperCard().getName();
                        if (!open.contains(name)) {
                            searched++;
                            assertFalse(body.contains(name), "a card in the host's " + zone + " is named in the game: " + name + " in " + body);
                            assertFalse(log.contains(name), "a card in the host's " + zone + " is named in the game log: " + name + " in " + log);
                        }
                        if (zone != ZoneType.Battlefield) {
                            assertFalse(shown.contains(c.getId()), "a card in the host's " + zone + " is in the game by its id " + c.getId() + ": " + body);
                        }
                    }
                }
                sawHand |= searched > 0 && !hostPlayer.getCardsIn(ZoneType.Hand).isEmpty();
                assertFalse(host.has("hand") || host.has("libraryTop"), "the host player has no hand and no library top: " + host);

                // The seat's own library: only a top card it may look at.
                final List<Integer> library = match.hostIds(RiggedMatch.ME, ZoneType.Library);
                final boolean topShown = me.has("libraryTop") && me.getAsJsonObject("libraryTop").get("id").getAsInt() == library.get(0);
                for (int i = topShown ? 1 : 0; i < library.size(); i++) {
                    assertFalse(shown.contains(library.get(i)), "a card of the seat's library is in the game, at " + i + " from the top: " + body);
                }
                if (RiggedMatch.card(me, "battlefield", FRENZY) == null) {
                    assertFalse(me.has("libraryTop"), "no library top without the effect: " + me);
                }
                sawBelowTop |= topShown && library.size() > 1;

                if (faceDown != null) {
                    for (final JsonElement c : host.getAsJsonArray("battlefield")) {
                        final JsonObject card = c.getAsJsonObject();
                        if (card.get("id").getAsInt() == faceDown.getId()) {
                            assertTrue(card.get("faceDown").getAsBoolean(), "the host's card is face down: " + card);
                            assertEquals(card.get("name").getAsString(), "", "it has no name: " + card);
                            assertFalse(card.has("faceUpName"), "and no other face for the seat: " + card);
                            sawFaceDown = true;
                            match.http.record("GET /game#opponent-face-down", g);
                        }
                    }
                    sawExiled |= host.get("exileSize").getAsInt() > 0 && exiled.isFaceDown();
                } else if (hostPlayer.getCardsIn(ZoneType.Library).size() > 1) {
                    for (final Card c : hostPlayer.getCardsIn(ZoneType.Hand)) {
                        if (!c.isLand()) {
                            faceDown = c;
                        }
                    }
                    if (faceDown != null) {
                        faceDown.turnFaceDown(true);
                        faceDown = game.getAction().moveToPlay(faceDown, hostPlayer, null, AbilityKey.newMap());
                        exiled = game.getAction().exile(hostPlayer.getCardsIn(ZoneType.Library).getFirst(), null, AbilityKey.newMap());
                        exiled.turnFaceDown(true);
                        assertTrue(faceDown.isFaceDown() && exiled.isFaceDown(), "the host has a face-down permanent and a face-down card in exile");
                    }
                }
            }
            match.basics(g, "Mountain", FRENZY, 4);
        }
        match.finish();
    }

    private static Player player(final Game game, final String name) {
        for (final Player p : game.getPlayers()) {
            if (name.equals(p.getName())) {
                return p;
            }
        }
        throw new IllegalStateException("no player " + name);
    }

    /** The ids of everything in the game that is a card: an object with an id and a name that is not a player or a stack entry. */
    private static void cardIds(final JsonElement e, final Set<Integer> into) {
        if (e.isJsonArray()) {
            for (final JsonElement child : e.getAsJsonArray()) {
                cardIds(child, into);
            }
        } else if (e.isJsonObject()) {
            final JsonObject o = e.getAsJsonObject();
            final String type = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
            if (o.has("id") && o.has("name") && !o.has("isMe") && !"player".equals(type) && !"stack".equals(type)) {
                into.add(o.get("id").getAsInt());
            }
            for (final String key : o.keySet()) {
                cardIds(o.get(key), into);
            }
        }
    }
}
