package forge.agent;

import java.util.HashMap;
import java.util.Map;

import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.game.GameType;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * A Forge host with no window and no seat of its own. Every slot is open, so the players are whoever
 * joins: agent seats through the bridge, people through a Forge client. It starts a match when every
 * slot is taken and each seat is ready with a legal deck, and waits for the next one when the match
 * ends. Run it like {@link AgentMain}, from the folder that holds Forge's {@code res}:
 * {@code java -cp <this jar><path separator><forge jar> forge.agent.MatchHost [--port N] [--seats N] [--limited]}.
 */
public final class MatchHost {

    private static final long POLL_MILLIS = 250;
    private static final long START_DELAY_MILLIS = 3000;

    private final int port;
    private final int seats;
    private final boolean limited;
    private final FServerManager server = FServerManager.getInstance();
    private final Map<Integer, Deck> refused = new HashMap<>();
    private ServerGameLobby lobby;
    private volatile boolean stopped;

    MatchHost(final int port, final int seats, final boolean limited) {
        this.port = port;
        this.seats = seats;
        this.limited = limited;
    }

    public static void main(final String[] args) throws Exception {
        int port = 36743;
        int seats = 2;
        boolean limited = false;
        for (int i = 0; i < args.length; i++) {
            if ("--limited".equals(args[i])) {
                limited = true;
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--seats".equals(args[i]) && i + 1 < args.length) {
                seats = Integer.parseInt(args[++i]);
            }
        }
        if (seats < 2) {
            System.err.println("[match-host] --seats takes 2 or more, got " + seats);
            System.exit(2);
        }

        GuiBase.setInterface(new AgentGuiBase());
        System.err.println("[match-host] loading card database");
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        FModel.getNetPreferences().setPref(FNetPref.UPnP, "NEVER");

        final MatchHost host = new MatchHost(port, seats, limited);
        host.open();
        Runtime.getRuntime().addShutdownHook(new Thread(host::close, "MatchHost-Shutdown"));
        System.out.println("MATCH_HOST_READY port=" + port);
        System.out.flush();
        host.run();
    }

    /** Starts the server and a lobby whose slots are all open. */
    ServerGameLobby open() {
        lobby = new ServerGameLobby();
        server.startServer(port);
        server.setLobby(lobby);
        lobby.setListener(new IUpdateable() {
            @Override
            public void update(final boolean fullUpdate) {
                server.updateLobbyState();
            }

            @Override
            public void update(final int slot, final LobbySlotType type) {
            }
        });
        // Forge gives the host the first slot. This host does not play, so the first player to join takes it.
        final LobbySlot first = lobby.getSlot(0);
        first.setType(LobbySlotType.OPEN);
        first.setName("");
        first.setIsReady(false);
        while (lobby.getNumberOfSlots() < seats) {
            lobby.addSlot();
        }
        lobby.setLimitedMode(limited);
        return lobby;
    }

    /** Starts a match each time the seats are ready, until {@link #close}. */
    void run() throws InterruptedException {
        // After a match, the seats still read as ready until they leave it, so the next match waits for one to unready.
        boolean played = false;
        while (!stopped) {
            final boolean ready = lobby.getHostedMatch() == null && seatsReady();
            if (played && lobby.getHostedMatch() == null && !ready) {
                played = false;
            }
            if (played || !ready) {
                Thread.sleep(POLL_MILLIS);
                continue;
            }
            say("Every seat is ready. The match starts in " + START_DELAY_MILLIS / 1000 + " seconds.");
            Thread.sleep(START_DELAY_MILLIS);
            if (stopped || !seatsReady()) {
                continue;
            }
            final Runnable start = lobby.startGame();
            if (start == null) {
                say("The lobby refused to start the match.");
                Thread.sleep(START_DELAY_MILLIS);
                continue;
            }
            start.run();
            played = true;
            log("match started");
        }
    }

    void close() {
        stopped = true;
        server.stopServer();
    }

    /**
     * True when every slot holds a ready player with a deck the lobby accepts. Forge asks about a deck that
     * breaks the rules in a dialog, so such a deck is refused in chat here and never reaches that check.
     */
    private boolean seatsReady() {
        final DeckFormat format = limited ? DeckFormat.Limited : GameType.Constructed.getDeckFormat();
        final boolean enforce = FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY);
        boolean ready = true;
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final LobbySlot slot = lobby.getSlot(i);
            if (slot.getType() != LobbySlotType.REMOTE) {
                refused.remove(i);
                ready = false;
                continue;
            }
            final Deck deck = slot.getDeck();
            if (!slot.isReady() || deck == null) {
                ready = false;
                continue;
            }
            final String problem = enforce ? format.getDeckConformanceProblem(deck) : null;
            if (problem != null) {
                if (refused.put(i, deck) != deck) {
                    say(slot.getName() + "'s deck " + deck.getName() + " is not legal here: " + problem + ". Choose another deck.");
                }
                ready = false;
            }
        }
        return ready;
    }

    private void say(final String text) {
        log(text);
        server.broadcast(new MessageEvent(text));
    }

    private static void log(final String line) {
        System.err.println("[match-host] " + line);
    }
}
