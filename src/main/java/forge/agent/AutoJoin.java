package forge.agent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import com.google.gson.JsonObject;

/**
 * Takes a seat when a host's lobby appears. It tries once per lobby: after a seat, or a refusal, it waits
 * until the host port has been seen closed before it tries again.
 */
final class AutoJoin implements Runnable {

    private static final int PROBE_TIMEOUT_MILLIS = 1000;

    private final AgentBridge bridge;
    private final AgentSession session;
    private final JsonObject seat;
    private final String host;
    private final int port;
    private final long intervalMillis;

    AutoJoin(final AgentBridge bridge, final AgentSession session, final JsonObject seat, final long intervalMillis) {
        this.bridge = bridge;
        this.session = session;
        this.seat = seat;
        this.host = seat.get("host").getAsString();
        this.port = seat.get("port").getAsInt();
        this.intervalMillis = intervalMillis;
    }

    @Override
    public void run() {
        boolean armed = true;
        while (true) {
            try {
                if (session.seatAlive()) {
                    armed = false;
                } else if (!hostListening()) {
                    armed = true;
                } else if (armed) {
                    armed = false;
                    bridge.sit(seat);
                    System.err.println("[agent] auto-join: seated at " + host + ":" + port);
                }
            } catch (final InterruptedException e) {
                return;
            } catch (final Exception e) {
                System.err.println("[agent] auto-join: " + e.getMessage());
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (final InterruptedException e) {
                return;
            }
        }
    }

    private boolean hostListening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (final IOException e) {
            return false;
        }
    }
}
