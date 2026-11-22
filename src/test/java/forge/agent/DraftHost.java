package forge.agent;

import java.util.List;

import forge.deck.Deck;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.IBoosterDraft;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gamemodes.limited.LimitedPlayerAI;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.NetworkEvent;
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
 * [--port N] [--block NAME] [--set CODE] [--pod N] [--timer SECONDS] [--seats N] [--no-match]}.
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
        for (int i = 0; i < args.length; i++) {
            if ("--no-match".equals(args[i])) {
                match = false;
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

        // The host's own seat drafts and plays as Forge's AI.
        final LobbySlot hostSlot = lobby.getSlot(0);
        hostSlot.setType(LobbySlotType.AI);
        hostSlot.setName("HostAI");
        hostSlot.setIsReady(true);

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
        lobby.createEvent(EventFormat.BOOSTER_DRAFT);
        if (!lobby.configureEvent(LimitedPoolType.Block, draft, timer, 0)) {
            throw new IllegalStateException("the lobby refused the event");
        }
        say("hosting on port " + port + ": " + set + " draft, pod " + pod + ", pick timer " + timer + " s, waiting for " + seats + " seat(s)");

        final int remoteSeats = seats;
        waitFor(() -> remoteCount(lobby) >= remoteSeats && lobby.findFirstUnreadySlot() == null);
        final NetworkEvent event = lobby.getCurrentEvent();
        if (lobby.startDraftEvent() == null) {
            throw new IllegalStateException("the draft did not start");
        }
        say("draft started");
        waitFor(() -> lobby.getDraftHost().isFinished());
        say("draft finished, pools sent");
        // The desktop host does this when its own pool arrives. It tells the seats which event the match is for.
        lobby.selectEventForMatch(event.getEventId(), true);

        if (!match) {
            Thread.currentThread().join();
        }
        EventParticipant host = null;
        for (final EventParticipant p : event.getParticipants()) {
            if (p.getLobbySlotIndex() == 0) {
                host = p;
            }
        }
        final List<LimitedPlayer> players = draft.getAllPlayers();
        final LimitedPlayerAI ai = (LimitedPlayerAI) players.get(host.getSeatIndex());
        final Deck hostDeck = ai.buildDeck(block.getLandSet() == null ? null : block.getLandSet().getCode());
        hostSlot.setDeck(hostDeck);
        say("host deck built: " + hostDeck.getMain().countAll() + " cards. Waiting for every seat to bring a deck and ready up");

        // A seat stays ready from before the draft, so wait for its deck to change to one with a main deck.
        waitFor(() -> {
            for (int i = 1; i < lobby.getNumberOfSlots(); i++) {
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
