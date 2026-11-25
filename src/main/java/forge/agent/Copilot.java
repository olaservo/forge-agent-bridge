package forge.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;

/**
 * A read-only watch on one seat of a {@link MatchHost}, for an agent that advises the player in it.
 * The seat is named by its lobby name. Before each game the host's GUI object for that seat is replaced
 * with a {@link CopilotGui}, and {@code GET /copilot} on a loopback port returns what it holds. There is
 * no route that acts for the seat. A request needs the token in an {@code Authorization: Bearer} header.
 * The token comes from the environment variable {@value #TOKEN_ENV}. Without one the host makes a random
 * token and prints it nowhere, so nothing can read the port.
 * Each player in the lobby is told in chat that the seat is watched.
 */
final class Copilot {

    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");
    static final String TOKEN_ENV = "FORGE_COPILOT_TOKEN";
    private static final long POLL_MILLIS = 250;

    private final String seat;
    private final int requestedPort;
    private final byte[] token;
    private final Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private final FServerManager server = FServerManager.getInstance();
    private ServerGameLobby lobby;
    private HttpServer http;
    private volatile CopilotGui gui;
    private volatile boolean stopped;

    Copilot(final String seat, final int port, final String token) {
        this.seat = seat;
        this.requestedPort = port;
        this.token = ("Bearer " + (token == null || token.isBlank() ? randomToken() : token)).getBytes(StandardCharsets.UTF_8);
    }

    private static String randomToken() {
        final byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The copilot that {@code --copilot <seat name>} and {@code --copilot-port <n>} ask for, or null without the first. */
    static Copilot fromArgs(final String[] args) {
        String seat = null;
        int port = 0;
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--copilot".equals(args[i])) {
                seat = args[++i];
            } else if ("--copilot-port".equals(args[i])) {
                port = Integer.parseInt(args[++i]);
            }
        }
        return seat == null ? null : new Copilot(seat, port, System.getenv(TOKEN_ENV));
    }

    /** Opens the loopback port and starts watching the lobby for the seat. */
    void start(final ServerGameLobby hostLobby) throws IOException {
        lobby = hostLobby;
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "Copilot-HTTP");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/", this::handle);
        http.start();
        final Thread t = new Thread(this::announce, "Copilot-Lobby");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        stopped = true;
        if (http != null) {
            http.stop(0);
        }
    }

    int port() {
        return http.getAddress().getPort();
    }

    /** What the host's ready line says about the copilot. */
    String readyLine() {
        return " copilotPort=" + port() + " copilot=" + seat;
    }

    /** Puts the copilot's GUI on the seat's slot. The host clears a slot's GUI after each match, so this runs before each game. */
    void install() {
        final int slot = slot();
        final RemoteClient client = slot < 0 ? null : server.findClientByIndex(slot);
        if (client == null) {
            gui = null;
            System.err.println("[copilot] no seat named " + seat + " in this game");
            return;
        }
        gui = new CopilotGui(client);
        System.err.println("[copilot] watching " + seat + " in slot " + slot);
    }

    private int slot() {
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final LobbySlot s = lobby.getSlot(i);
            if (s.getType() == LobbySlotType.REMOTE && seat.equals(s.getName())) {
                return i;
            }
        }
        return -1;
    }

    /** Tells each player in the lobby once, in chat, that the seat has a copilot. A player who joins later is told then. */
    private void announce() {
        final Set<String> told = new HashSet<>();
        while (!stopped) {
            try {
                if (slot() < 0) {
                    told.clear();
                }
                for (int i = 0; slot() >= 0 && i < lobby.getNumberOfSlots(); i++) {
                    final LobbySlot s = lobby.getSlot(i);
                    if (s.getType() == LobbySlotType.REMOTE && server.findClientByIndex(i) != null && told.add(s.getName())) {
                        server.sendToSlot(i, new MessageEvent(seat + " has a copilot: an AI assistant that sees what " + seat + " sees and can advise."));
                    }
                }
                Thread.sleep(POLL_MILLIS);
            } catch (final InterruptedException e) {
                return;
            } catch (final RuntimeException e) {
                System.err.println("[copilot] " + e);
            }
        }
    }

    /** What the watched seat is shown, or only the seat's name while no game has it. */
    Map<String, Object> view() {
        final Map<String, Object> m = new LinkedHashMap<>();
        final CopilotGui g = gui;
        final boolean watching = g != null && g.getClient().getGui() == g;
        m.put("seat", seat);
        m.put("watching", watching);
        if (watching) {
            m.putAll(g.snapshot());
        }
        return m;
    }

    private void handle(final HttpExchange ex) throws IOException {
        try {
            final String host = ex.getRequestHeaders().getFirst("Host");
            final String name = host == null ? "" : host.replaceFirst(":\\d+$", "").toLowerCase();
            if (ex.getRequestHeaders().getFirst("Origin") != null || !LOCAL_HOSTS.contains(name)) {
                write(ex, 403, Map.of("error", "local programs only"));
            } else if (!authorised(ex)) {
                write(ex, 401, Map.of("error", "the Authorization header must carry the copilot token"));
            } else if (!"GET".equals(ex.getRequestMethod())) {
                write(ex, 405, Map.of("error", "the copilot port is read-only"));
            } else if ("/copilot".equals(ex.getRequestURI().getPath())) {
                write(ex, 200, view());
            } else {
                write(ex, 404, Map.of("error", "GET /copilot is the only route"));
            }
        } catch (final Exception e) {
            e.printStackTrace();
            write(ex, 500, Map.of("error", e.toString()));
        }
    }

    private boolean authorised(final HttpExchange ex) {
        final String given = ex.getRequestHeaders().getFirst("Authorization");
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token);
    }

    private void write(final HttpExchange ex, final int code, final Object payload) throws IOException {
        final byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
