package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

/**
 * The top card of the seat's library is in the game while an effect lets the player look at it. The seat's
 * deck is 20 Mountain and 20 Experimental Frenzy ("You may look at the top card of your library any time").
 * Before the enchantment is on the battlefield no player has {@code libraryTop}. With it, the seat's player
 * has the card that is on top of its library on the host, and the host's player still has none.
 */
public class AgentLibraryTopTest {

    private static final String FRENZY = "Experimental Frenzy";
    private static final String DECK = String.join("\n",
            "[metadata]",
            "Name=Agent Library Top Test",
            "[Main]",
            "20 Mountain",
            "20 " + FRENZY);

    private RiggedMatch match;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (match != null) {
            match.close("librarytop");
        }
    }

    @Test(timeOut = 300_000)
    public void theTopOfTheSeatsLibraryShowsWhileAnEffectLetsItLook() throws Exception {
        match = new RiggedMatch(DECK, RiggedMatch.deck("Host lands", "40 Forest"));
        final long deadline = System.currentTimeMillis() + 240_000;
        boolean checkedWithout = false;
        while (true) {
            final JsonObject g = match.next();
            assertTrue(System.currentTimeMillis() < deadline, "no library top in time; last state " + g + "; the client's libraries " + clientLibraries());
            assertFalse(g.get("gameOver").getAsBoolean(), "the game ended early: " + g);
            final JsonObject me = RiggedMatch.me(g);
            final JsonObject host = RiggedMatch.player(g, false);
            if (me == null || host == null) {
                continue;
            }
            assertFalse(host.has("libraryTop"), "the opponent's library top is never shown: " + host);
            final boolean asked = g.get("priorityPrompt").getAsBoolean() && !g.get("waiting").getAsBoolean() && AgentGameTest.iHavePriority(g);
            if (RiggedMatch.card(me, "battlefield", FRENZY) == null) {
                assertFalse(me.has("libraryTop"), "without the effect the seat's library top is not shown: " + me);
                checkedWithout |= me.get("librarySize").getAsInt() > 0;
            } else if (asked && match.hostWaitsOnSeat() && me.has("libraryTop")) {
                match.http.record("GET /game#library-top", g);
                final JsonObject top = me.getAsJsonObject("libraryTop");
                final List<Integer> onHost = match.hostIds(RiggedMatch.ME, ZoneType.Library);
                assertEquals(top.get("id").getAsInt(), onHost.get(0).intValue(), "it is the card on top of the library on the host: " + top);
                assertEquals(top.get("name").getAsString(), match.hostCard(RiggedMatch.ME, ZoneType.Library).getName());
                assertEquals(top.get("zone").getAsString(), "Library");
                // What Forge's client holds: the seat's whole library in the host's order, top first.
                assertEquals(clientLibrary(true), onHost, "the client holds the seat's library in the host's order");
                assertTrue(checkedWithout, "the game was seen before the effect");
                break;
            }
            match.basics(g, "Mountain", FRENZY, 4);
        }
        match.finish();
    }

    /** Card ids of a library as Forge's client holds it, the seat's or the host player's. */
    private List<Integer> clientLibrary(final boolean mine) throws Exception {
        final AgentGuiGame gui = gui();
        final PlayerView me = gui.getCurrentPlayer();
        final List<Integer> ids = new ArrayList<>();
        for (final PlayerView p : gui.getGameView().getPlayers()) {
            if ((p.getId() == me.getId()) == mine) {
                for (final CardView c : p.getLibrary()) {
                    ids.add(c.getId());
                }
            }
        }
        return ids;
    }

    private String clientLibraries() throws Exception {
        final AgentGuiGame gui = gui();
        if (gui.getGameView() == null || gui.getGameView().getPlayers() == null || gui.getCurrentPlayer() == null) {
            return "none";
        }
        final StringBuilder out = new StringBuilder();
        for (final PlayerView p : gui.getGameView().getPlayers()) {
            out.append(p.getName()).append(' ').append(p.getZoneSize(ZoneType.Library)).append(" cards, held:");
            for (final CardView c : p.getLibrary()) {
                out.append(' ').append(c.getId()).append('=').append(c.getName()).append('/').append(c.getZone())
                        .append(c.mayPlayerLook(gui.getCurrentPlayer()) ? "/mayLook" : "");
            }
            out.append("; ");
        }
        return out.toString();
    }

    private AgentGuiGame gui() throws Exception {
        final Field session = AgentBridge.class.getDeclaredField("session");
        session.setAccessible(true);
        final Field gui = AgentSession.class.getDeclaredField("gui");
        gui.setAccessible(true);
        return (AgentGuiGame) gui.get(session.get(match.bridge));
    }
}
