package forge.agent;

import java.util.List;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.IBoosterDraft;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gamemodes.limited.LimitedPlayerAI;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedDeckBuilder;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.NetworkEvent;
import forge.gamemodes.net.event.ReceiveEventPoolEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.CardBlock;
import forge.model.FModel;

/**
 * A Forge host with no window, for drafting against agent seats without the desktop client. It hosts a
 * Limited lobby with a booster draft, starts the draft once the expected remote seats are ready, and then
 * starts the match with the deck its own AI seat drafted. Run it from the folder that holds Forge's
 * {@code res}: {@code java -cp <test classes><sep><bridge jar><sep><forge jar> forge.agent.DraftHost
 * [--port N] [--block NAME] [--set CODE] [--pod N] [--timer SECONDS] [--seats N] [--no-match] [--no-host-seat] [--sealed]}.
 * With {@code --no-host-seat} every lobby slot is open, Forge's AI drafters fill the pod, and the match is between
 * the seats that joined. With {@code --sealed} there are no picks: each seat is sent a pool of six boosters.
 */
public final class DraftHost {

    private DraftHost() {
    }

    public static void main(final String[] args) throws Exception {
        int port = 36743;
        String blockName = "Magic 2011";
        String set = "M11";
        int pod = 2;
        int timer = 0;
        int seats = 1;
        boolean match = true;
        boolean hostSeat = true;
        boolean sealed = false;
        for (int i = 0; i < args.length; i++) {
            if ("--no-match".equals(args[i])) {
                match = false;
            } else if ("--no-host-seat".equals(args[i])) {
                hostSeat = false;
            } else if ("--sealed".equals(args[i])) {
                sealed = true;
            } else if (i + 1 < args.length) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--block" -> blockName = args[++i];
                    case "--set" -> set = args[++i];
                    case "--pod" -> pod = Integer.parseInt(args[++i]);
                    case "--timer" -> timer = Integer.parseInt(args[++i]);
                    case "--seats" -> seats = Integer.parseInt(args[++i]);
                    default -> { }
                }
            }
        }

        GuiBase.setInterface(new AgentGuiBase());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        FModel.getNetPreferences().setPref(FNetPref.UPnP, "NEVER");

        final FServerManager server = FServerManager.getInstance();
        final ServerGameLobby lobby = new ServerGameLobby();
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

        final LobbySlot hostSlot = lobby.getSlot(0);
        if (hostSeat) {
            // The host's own seat drafts and plays as Forge's AI.
            hostSlot.setType(LobbySlotType.AI);
            hostSlot.setName("HostAI");
            hostSlot.setIsReady(true);
        } else {
            // As MatchHost does: the first player to join takes the host's slot.
            hostSlot.setType(LobbySlotType.OPEN);
            hostSlot.setName("");
            hostSlot.setIsReady(false);
        }
        while (lobby.getNumberOfSlots() < seats + (hostSeat ? 1 : 0)) {
            lobby.addSlot();
        }

        final CardBlock block = FModel.getBlocks().get(blockName);
        if (block == null) {
            throw new IllegalArgumentException("no block named " + blockName + " in res/blockdata");
        }
        final String booster = set;
        final BoosterDraft draft = new BoosterDraft(LimitedPoolType.Block, pod) {
            {
                for (int i = 0; i < 3; i++) {
                    product.add(block.getBooster(booster));
                }
            }
        };
        IBoosterDraft.LAND_SET_CODE[0] = block.getLandSet();
        IBoosterDraft.CUSTOM_RANKINGS_FILE[0] = null;

        lobby.setLimitedMode(true);
        final int remoteSeats = seats;
        final String landSet = block.getLandSet() == null ? null : block.getLandSet().getCode();
        final NetworkEvent event;
        Deck hostDeck = null;
        if (sealed) {
            // Forge's SealedCardPoolGenerator asks for its product in a dialog, so the pools are opened here
            // and sent the way ServerGameLobby.generateAndDistributeSealedPools sends them.
            lobby.createEvent(EventFormat.SEALED);
            event = lobby.getCurrentEvent();
            event.setPoolType(LimitedPoolType.Block);
            event.setProductDescription(LimitedPoolType.Block + ": " + set);
            server.updateLobbyState();
            say("hosting on port " + port + ": " + set + " sealed, waiting for " + seats + " seat(s)");
            waitFor(() -> remoteCount(lobby) >= remoteSeats && lobby.findFirstUnreadySlot() == null);
            lobby.populateParticipants();
            event.setPhase(EventPhase.POOL_DISTRIBUTION);
            server.updateLobbyState();
            for (final EventParticipant p : event.getParticipants()) {
                final CardPool pool = new CardPool();
                for (int i = 0; i < 6; i++) {
                    pool.addAllFlat(block.getBooster(booster).get());
                }
                if (p.isAI()) {
                    hostDeck = new SealedDeckBuilder(pool.toFlatList()).buildDeck(landSet);
                    continue;
                }
                final Deck deck = new Deck(NetworkEvent.poolNameFor(event));
                deck.getOrCreate(DeckSection.Sideboard).addAll(pool);
                NetworkEvent.setEventTags(deck, event);
                server.sendToSlot(p.getLobbySlotIndex(), new ReceiveEventPoolEvent(event.getEventId(), deck));
                say("sealed pool of " + pool.countAll() + " sent to " + p.getName());
            }
        } else {
            lobby.createEvent(EventFormat.BOOSTER_DRAFT);
            if (!lobby.configureEvent(LimitedPoolType.Block, draft, timer, 0)) {
                throw new IllegalStateException("the lobby refused the event");
            }
            say("hosting on port " + port + ": " + set + " draft, pod " + pod + ", pick timer " + timer + " s, waiting for " + seats + " seat(s)");
            waitFor(() -> remoteCount(lobby) >= remoteSeats && lobby.findFirstUnreadySlot() == null);
            event = lobby.getCurrentEvent();
            if (lobby.startDraftEvent() == null) {
                throw new IllegalStateException("the draft did not start");
            }
            final StringBuilder seating = new StringBuilder();
            for (final EventParticipant p : event.getParticipants()) {
                seating.append(' ').append(p.getSeatIndex()).append('=').append(p.getName());
            }
            say("draft started, seats:" + seating);
            waitFor(() -> lobby.getDraftHost().isFinished());
            say("draft finished, pools sent");
            if (hostSeat) {
                EventParticipant host = null;
                for (final EventParticipant p : event.getParticipants()) {
                    if (p.getLobbySlotIndex() == 0) {
                        host = p;
                    }
                }
                final List<LimitedPlayer> players = draft.getAllPlayers();
                hostDeck = ((LimitedPlayerAI) players.get(host.getSeatIndex())).buildDeck(landSet);
            }
        }
        // The desktop host does this when its own pool arrives. It tells the seats which event the match is for.
        lobby.selectEventForMatch(event.getEventId(), true);

        if (!match) {
            Thread.currentThread().join();
        }
        if (hostSeat) {
            hostSlot.setDeck(hostDeck);
            say("host deck built: " + hostDeck.getMain().countAll() + " cards");
        }
        say("waiting for every seat to bring a deck and ready up");

        // A seat stays ready from before the draft, so wait for its deck to change to one with a main deck.
        waitFor(() -> {
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                final LobbySlot s = lobby.getSlot(i);
                if (s.getType() != LobbySlotType.REMOTE) {
                    continue;
                }
                if (!s.isReady() || s.getDeck() == null || s.getDeck().getMain().countAll() < 40) {
                    return false;
                }
            }
            return true;
        });
        final Runnable start = lobby.startGame();
        if (start == null) {
            throw new IllegalStateException("the lobby refused to start the match");
        }
        start.run();
        say("match started");
        Thread.currentThread().join();
    }

    private static int remoteCount(final ServerGameLobby lobby) {
        int n = 0;
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            if (lobby.getSlot(i).getType() == LobbySlotType.REMOTE) {
                n++;
            }
        }
        return n;
    }

    interface Check {
        boolean ok();
    }

    private static void waitFor(final Check cond) throws InterruptedException {
        while (!cond.ok()) {
            Thread.sleep(250);
        }
    }

    private static void say(final String line) {
        System.out.println("[draft-host] " + line);
        System.out.flush();
    }
}
