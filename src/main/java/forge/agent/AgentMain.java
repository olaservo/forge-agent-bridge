package forge.agent;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * A headless Forge network client behind a local HTTP JSON API, for forge-mcp-server. Runs until killed.
 * Start it with Forge's own jar on the classpath, from the folder that holds Forge's {@code res}:
 * {@code java -cp <forge jar><path separator><this jar> forge.agent.AgentMain [--port N] [--auto-pass off|opponent-turns|always]}.
 */
public final class AgentMain {

    private AgentMain() {
    }

    public static void main(final String[] args) {
        int port = 36744;
        String autoPass = AgentSession.AUTO_PASS_OPPONENT_TURNS;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--auto-pass".equals(args[i]) && i + 1 < args.length) {
                autoPass = args[++i];
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
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop, "Agent-Shutdown"));
        try {
            Thread.currentThread().join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
