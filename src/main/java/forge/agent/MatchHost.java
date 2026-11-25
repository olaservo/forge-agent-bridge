package forge.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.AiProfileUtil;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.game.GameType;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gamemodes.quest.QuestController;
import forge.gui.GuiBase;
import forge.interfaces.IUpdateable;
import forge.item.PreconDeck;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;
import forge.util.NameGenerator;

/**
 * A Forge host with no window and no seat of its own. Every slot is open, so the players are whoever
 * joins: agent seats through the bridge, people through a Forge client. It starts a match when every
 * slot is taken and each seat is ready with a legal deck, and waits for the next one when the match
 * ends. A match is one game, or the best of three or five with {@code --games}. With {@code --ai} the
 * last slots are played by Forge's AI. Run it like {@link AgentMain}, from the folder that holds Forge's
 * {@code res}:
 * {@code java -cp <this jar><path separator><forge jar> forge.agent.MatchHost [--port N] [--seats N] [--games N] [--ai N] [--ai-deck <file or name>]... [--limited]
 * [--copilot <seat name>] [--copilot-port N] [--exit-with-parent]}.
 */
public final class MatchHost {

    private static final long POLL_MILLIS = 250;
    private static final long START_DELAY_MILLIS = 3000;

    private final int port;
    private final int seats;
    private final boolean limited;
    private final int games;
    private final int ai;
    private final List<String> aiDecks;
    private final FServerManager server = FServerManager.getInstance();
    private final Map<Integer, Deck> refused = new HashMap<>();
    private final Set<Integer> told = new HashSet<>();
    private ServerGameLobby lobby;
    private volatile boolean stopped;
    /** Watches one seat for an advising agent, or null. */
    Copilot copilot;

    MatchHost(final int port, final int seats, final boolean limited) {
        this(port, seats, limited, 1);
    }

    MatchHost(final int port, final int seats, final boolean limited, final int games) {
        this(port, seats, limited, games, 0, List.of());
    }

    /**
     * @param ai how many of the seats Forge's AI plays
     * @param aiDecks a deck for each AI seat in slot order, as a deck file or a deck name. An AI seat with none gets a precon.
     */
    MatchHost(final int port, final int seats, final boolean limited, final int games, final int ai, final List<String> aiDecks) {
        final String problem = aiProblem(seats, limited, ai, aiDecks.size());
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        this.port = port;
        this.seats = seats;
        this.limited = limited;
        this.games = games;
        this.ai = ai;
        this.aiDecks = List.copyOf(aiDecks);
    }

    /** Why these AI seats cannot be hosted, or null. */
    static String aiProblem(final int seats, final boolean limited, final int ai, final int aiDecks) {
        if (ai < 0 || ai >= seats) {
            return "--ai takes 0 to " + (seats - 1) + " with " + seats + " seats, so that one seat is left for a player, got " + ai;
        }
        if (aiDecks > ai) {
            return "--ai-deck was given " + aiDecks + " times for " + ai + " AI seats";
        }
        if (ai > 0 && limited) {
            return "--ai is not available with --limited: an AI seat has no pool to build a Limited deck from";
        }
        return null;
    }

    public static void main(final String[] args) throws Exception {
        int port = 36743;
        int seats = 2;
        boolean limited = false;
        int games = 1;
        int ai = 0;
        final List<String> aiDecks = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--limited".equals(args[i])) {
                limited = true;
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--seats".equals(args[i]) && i + 1 < args.length) {
                seats = Integer.parseInt(args[++i]);
            } else if ("--games".equals(args[i]) && i + 1 < args.length) {
                games = Integer.parseInt(args[++i]);
            } else if ("--ai".equals(args[i]) && i + 1 < args.length) {
                ai = Integer.parseInt(args[++i]);
            } else if ("--ai-deck".equals(args[i]) && i + 1 < args.length) {
                aiDecks.add(args[++i]);
            } else if (ParentWatch.FLAG.equals(args[i])) {
                ParentWatch.exitWithParent("[match-host]");
            }
        }
        if (seats < 2) {
            System.err.println("[match-host] --seats takes 2 or more, got " + seats);
            System.exit(2);
        }
        if (games != 1 && games != 3 && games != 5) {
            System.err.println("[match-host] --games takes 1, 3 or 5, got " + games);
            System.exit(2);
        }
        final String aiProblem = aiProblem(seats, limited, ai, aiDecks.size());
        if (aiProblem != null) {
            System.err.println("[match-host] " + aiProblem);
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

        final MatchHost host = new MatchHost(port, seats, limited, games, ai, aiDecks);
        final ServerGameLobby lobby;
        try {
            lobby = host.open();
        } catch (final IllegalArgumentException e) {
            System.err.println("[match-host] " + e.getMessage());
            System.exit(2);
            return;
        }
        host.copilot = Copilot.fromArgs(args);
        if (host.copilot != null) {
            host.copilot.start(host.lobby);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(host::close, "MatchHost-Shutdown"));
        for (int i = seats - ai; i < seats; i++) {
            System.out.println("MATCH_HOST_AI slot=" + i + " name=" + lobby.getSlot(i).getName() + " deck=" + lobby.getSlot(i).getDeckName());
        }
        System.out.println("MATCH_HOST_READY port=" + port + " games=" + games + (ai > 0 ? " ai=" + ai : "")
                + (host.copilot == null ? "" : host.copilot.readyLine()));
        System.out.flush();
        host.run();
    }

    /**
     * Starts the server and a lobby whose slots are open, but for the AI seats, which are the last slots.
     * An AI deck that cannot be found or is not legal here is an IllegalArgumentException, before the server starts.
     */
    ServerGameLobby open() {
        final List<Deck> decks = new ArrayList<>();
        final List<String> sources = new ArrayList<>();
        for (int i = 0; i < ai; i++) {
            final Deck deck = i < aiDecks.size() ? aiDeck(aiDecks.get(i)) : randomPrecon();
            decks.add(deck);
            sources.add(i < aiDecks.size() ? "" : ", picked at random from Forge's precons");
        }
        // Forge takes the games per match from this preference when a match starts (HostedMatch.getDefaultRules).
        // It is set for this process only. Nothing here saves the preferences file.
        FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, String.valueOf(games));
        // A match with one human in it asks for the player name in a dialog when this preference is blank, and
        // saves the preferences file (HostedMatch.startGame). The host has no player of its own to name.
        if (ai > 0 && FModel.getPreferences().getPref(FPref.PLAYER_NAME).isBlank()) {
            FModel.getPreferences().setPref(FPref.PLAYER_NAME, "Host");
        }
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
        // What the desktop lobby sends for a slot switched to AI: the type, a generated name and the deck
        // (PlayerPanel.radioMouseAdapter, VLobby.fireDeckChangeListener). Forge reads an AI slot as ready.
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < ai; i++) {
            final int index = seats - ai + i;
            final LobbySlot slot = lobby.getSlot(index);
            final Deck deck = decks.get(i);
            final String name = NameGenerator.getRandomName("Any", "Any", names);
            names.add(name);
            slot.setType(LobbySlotType.AI);
            slot.setName(name);
            slot.setDeck(deck);
            slot.setDeckName(deck.getName());
            // With no profile on the slot Forge uses the AI profile preference, and saves the preferences
            // file when that names no profile (GamePlayerUtil.createAiPlayer). The file is not this host's to write.
            final String profile = FModel.getPreferences().getPref(FPref.UI_CURRENT_AI_PROFILE);
            if (!AiProfileUtil.getProfilesDisplayList().contains(profile)) {
                slot.setAiProfile(FPref.UI_CURRENT_AI_PROFILE.getDefault());
            }
            log("slot " + index + " is Forge AI " + name + " with the deck " + deck.getName() + sources.get(i)
                    + ", AI profile " + (slot.getAiProfile() == null ? profile : slot.getAiProfile()));
        }
        lobby.setLimitedMode(limited);
        return lobby;
    }

    /** The deck in a file, as the bridge's {@code deckFile} reads it, or the saved deck or precon of that name. */
    Deck aiDeck(final String given) {
        Deck deck = new java.io.File(given).isFile() ? AgentBridge.readDeckFile(given) : AgentBridge.savedDeck(given);
        if (deck == null) {
            for (final PreconDeck precon : QuestController.getPrecons()) {
                if (precon.getName().equalsIgnoreCase(given)) {
                    deck = precon.getDeck();
                    break;
                }
            }
        }
        if (deck == null) {
            throw new IllegalArgumentException("--ai-deck: no deck file at '" + given + "' and no deck of that name in network-events, constructed or Forge's precons");
        }
        final String problem = FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY) ? format().getDeckConformanceProblem(deck) : null;
        if (problem != null) {
            throw new IllegalArgumentException("--ai-deck: " + deck.getName() + " is not legal here: " + problem);
        }
        return deck;
    }

    private Deck randomPrecon() {
        final List<Deck> legal = new ArrayList<>();
        for (final PreconDeck precon : QuestController.getPrecons()) {
            if (format().getDeckConformanceProblem(precon.getDeck()) == null) {
                legal.add(precon.getDeck());
            }
        }
        if (legal.isEmpty()) {
            throw new IllegalArgumentException("--ai: Forge's precons have no deck that is legal here. Give one with --ai-deck");
        }
        return legal.get(MyRandom.getRandom().nextInt(legal.size()));
    }

    private DeckFormat format() {
        return limited ? DeckFormat.Limited : GameType.Constructed.getDeckFormat();
    }

    /** Starts a match each time the seats are ready, until {@link #close}. */
    void run() throws InterruptedException {
        // After a match, the seats still read as ready until they leave it, so the next match waits for one to unready.
        boolean played = false;
        while (!stopped) {
            tellJoiners();
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
            if (copilot != null) {
                copilot.install();
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
        if (copilot != null) {
            copilot.stop();
        }
        server.stopServer();
    }

    /**
     * True when every slot holds a ready player with a deck the lobby accepts. Forge asks about a deck that
     * breaks the rules in a dialog, so such a deck is refused in chat here and never reaches that check.
     */
    private boolean seatsReady() {
        final DeckFormat format = format();
        final boolean enforce = FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY);
        boolean ready = true;
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final LobbySlot slot = lobby.getSlot(i);
            if (slot.getType() == LobbySlotType.AI) {
                // Its deck was checked when the lobby opened.
                continue;
            }
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

    /** Tells each player who joins, in chat, which slots Forge's AI plays and with which deck. */
    private void tellJoiners() {
        for (int i = 0; ai > 0 && i < lobby.getNumberOfSlots(); i++) {
            if (lobby.getSlot(i).getType() != LobbySlotType.REMOTE) {
                told.remove(i);
            } else if (told.add(i)) {
                for (int j = 0; j < lobby.getNumberOfSlots(); j++) {
                    final LobbySlot slot = lobby.getSlot(j);
                    if (slot.getType() == LobbySlotType.AI) {
                        server.sendToSlot(i, new MessageEvent(slot.getName() + " is Forge's AI and plays the deck " + slot.getDeckName() + "."));
                    }
                }
            }
        }
    }

    private void say(final String text) {
        log(text);
        server.broadcast(new MessageEvent(text));
    }

    private static void log(final String line) {
        System.err.println("[match-host] " + line);
    }
}
