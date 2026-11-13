package forge.agent;

import com.google.gson.JsonObject;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * A headless Forge network client behind a local HTTP JSON API, for forge-mcp-server. Runs until killed.
 * Start it with Forge's own jar on the classpath, from the folder that holds Forge's {@code res}:
 * {@code java -cp <this jar><path separator><forge jar> forge.agent.AgentMain [--port N] [--auto-pass off|opponent-turns|always]
 * [--auto-join host:port] [--auto-join-name NAME] [--auto-join-deck DECK]}.
 */
public final class AgentMain {

    private static final long AUTO_JOIN_INTERVAL_MILLIS = 5000;

    private AgentMain() {
    }

    public static void main(final String[] args) {
        int port = 36744;
        String autoPass = AgentSession.AUTO_PASS_OPPONENT_TURNS;
        String autoJoin = null;
        String autoJoinName = "Agent";
        String autoJoinDeck = null;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--auto-pass".equals(args[i]) && i + 1 < args.length) {
                autoPass = args[++i];
            } else if ("--auto-join".equals(args[i]) && i + 1 < args.length) {
                autoJoin = args[++i];
            } else if ("--auto-join-name".equals(args[i]) && i + 1 < args.length) {
                autoJoinName = args[++i];
            } else if ("--auto-join-deck".equals(args[i]) && i + 1 < args.length) {
                autoJoinDeck = args[++i];
            }
        }

        GuiBase.setInterface(new AgentGuiBase());
        System.err.println("[agent] loading card database");
        final long t0 = System.currentTimeMillis();
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        System.err.println("[agent] card database loaded in " + (System.currentTimeMillis() - t0) + " ms");

        JsonObject seat = null;
        if (autoJoin != null) {
            final int colon = autoJoin.lastIndexOf(':');
            if (colon < 1) {
                System.err.println("[agent] --auto-join takes host:port, got " + autoJoin);
                System.exit(2);
            }
            seat = new JsonObject();
            seat.addProperty("host", autoJoin.substring(0, colon));
            seat.addProperty("port", Integer.parseInt(autoJoin.substring(colon + 1)));
            seat.addProperty("name", autoJoinName);
            seat.addProperty("deck", autoJoinDeck);
        }

        final AgentBridge bridge = new AgentBridge();
        bridge.setAutoPass(autoPass);
        try {
            bridge.start(port);
        } catch (final Exception e) {
            System.err.println("[agent] could not bind 127.0.0.1:" + port + ": " + e);
            System.exit(2);
        }
        System.out.println("AGENT_BRIDGE_READY port=" + bridge.port());
        System.out.flush();
        if (seat != null) {
            bridge.autoJoin(seat, AUTO_JOIN_INTERVAL_MILLIS);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop, "Agent-Shutdown"));
        try {
            Thread.currentThread().join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
