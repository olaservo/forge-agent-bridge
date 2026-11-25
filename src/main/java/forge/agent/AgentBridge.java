package forge.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import forge.deck.Deck;
import forge.game.phase.PhaseType;
import forge.deck.io.DeckSerializer;
import forge.model.FModel;

/**
 * Local HTTP JSON front for one {@link AgentSession}. Loopback only; the MCP server is the only
 * intended caller.
 */
public final class AgentBridge {

    private static final String DEFAULT_HOST = "127.0.0.1";
    /** Forge's default port for a hosted lobby. */
    private static final int DEFAULT_HOST_PORT = 36743;
    private static final String DEFAULT_NAME = "Agent";
    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");

    private final AgentSession session = new AgentSession();
    private final Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private HttpServer server;

    /** off, opponent-turns or always. */
    void setAutoPass(final String mode) {
        session.setAutoPass(mode);
    }

    /** default or all. */
    void setStops(final String mode) {
        session.setStops(mode);
    }

    /** Sits down at each lobby that appears on the host named in {@code seat}, a {@code POST /sit} body. */
    void autoJoin(final JsonObject seat, final long intervalMillis) {
        final Thread t = new Thread(new AutoJoin(this, session, seat, intervalMillis), "Agent-AutoJoin");
        t.setDaemon(true);
        t.start();
    }

    public void start(final int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "Agent-HTTP");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        session.disconnect();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(final HttpExchange ex) throws IOException {
        try {
            final String refusal = refusal(ex);
            if (refusal != null) {
                write(ex, 403, Map.of("error", refusal));
                return;
            }
            final URI uri = ex.getRequestURI();
            final String path = uri.getPath();
            final String method = ex.getRequestMethod();
            final Map<String, String> query = query(uri.getRawQuery());
            final JsonObject body = "POST".equals(method) ? readBody(ex.getRequestBody()) : new JsonObject();
            final Object result = route(method, path, query, body);
            write(ex, 200, result);
        } catch (final JsonParseException e) {
            write(ex, 400, Map.of("error", "the request body is not JSON"));
        } catch (final IllegalArgumentException | IllegalStateException e) {
            write(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (final Exception e) {
            e.printStackTrace();
            write(ex, 500, Map.of("error", e.toString()));
        }
    }

    /**
     * Why a request is not from a local program, or null. A browser sends Origin with a page's POST, and a
     * page that reaches this port through a DNS name of its own sends that name as Host.
     */
    private static String refusal(final HttpExchange ex) {
        if (ex.getRequestHeaders().getFirst("Origin") != null) {
            return "requests from a web page are refused";
        }
        final String host = ex.getRequestHeaders().getFirst("Host");
        final String name = host == null ? "" : host.replaceFirst(":\\d+$", "").toLowerCase();
        if (!LOCAL_HOSTS.contains(name)) {
            return "the Host header must name 127.0.0.1, localhost or [::1]";
        }
        return null;
    }

    private Object route(final String method, final String path, final Map<String, String> q, final JsonObject body) throws Exception {
        switch (method + " " + path) {
            case "GET /status":
                return session.status();
            case "GET /wait": {
                final long since = Long.parseLong(q.getOrDefault("since", "-1"));
                final long timeout = Long.parseLong(q.getOrDefault("timeout", "30")) * 1000L;
                session.awaitChange(since, Math.min(timeout, 120_000L));
                return session.status();
            }
            case "POST /connect":
                session.connect(str(body, "host", DEFAULT_HOST), hostPort(body), str(body, "name", DEFAULT_NAME));
                return session.status();
            case "POST /disconnect":
                session.disconnect();
                return session.status();
            case "POST /sit":
                return sit(body);
            case "POST /lobby/ready":
                session.setReady(!body.has("ready") || body.get("ready").getAsBoolean());
                return session.lobbySnapshot();
            case "POST /lobby/avatar":
                session.setAvatar(num(body, "index"));
                return session.lobbySnapshot();
            case "POST /lobby/name": {
                final String name = str(body, "name", "").trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("name is empty");
                }
                session.setName(name);
                return session.lobbySnapshot();
            }
            case "POST /lobby/deck": {
                final Deck deck = resolveDeck(optStr(body, "name"), optStr(body, "dck"));
                session.setDeck(deck);
                return Map.of("deck", deck.getName(), "size", deck.getMain().countAll());
            }
            case "POST /chat":
                session.sendChat(str(body, "text", ""));
                return Map.of("ok", true);
            case "GET /draft/pack":
                return session.draftSnapshot(true);
            case "POST /draft/pick":
                return session.pick(str(body, "card", null), optStr(body, "at"));
            case "GET /draft/pool":
                return session.poolSnapshot();
            case "POST /draft/deck": {
                final List<String> main = new ArrayList<>();
                if (body.has("main") && body.get("main").isJsonArray()) {
                    for (final JsonElement e : body.getAsJsonArray("main")) {
                        main.add(e.getAsString());
                    }
                }
                final Map<String, Integer> lands = new LinkedHashMap<>();
                if (body.has("lands") && body.get("lands").isJsonObject()) {
                    for (final Map.Entry<String, JsonElement> e : body.getAsJsonObject("lands").entrySet()) {
                        lands.put(e.getKey(), e.getValue().getAsInt());
                    }
                }
                return session.buildDeck(main, lands, optStr(body, "landSet"));
            }
            case "GET /game":
                return session.gameSnapshot(true);
            case "GET /game/log":
                return Map.of("log", session.gameLog(Integer.parseInt(q.getOrDefault("since", "0"))));
            case "POST /game/act": {
                final String pay = optStr(body, "pay");
                if (pay != null && !"auto".equals(pay)) {
                    throw new IllegalArgumentException("pay must be auto");
                }
                final AgentSession.Act opts = new AgentSession.Act(flag(body, "once"), flag(body, "endTurn"), pay != null,
                        flag(body, "pass"), optInt(body, "at"));
                final AgentSession.Acted acted = session.act(str(body, "action", null), optInt(body, "id"), optInt(body, "color"), opts);
                final Map<String, Object> game = session.gameSnapshot(false);
                if (acted == AgentSession.Acted.STALE) {
                    // The prompt the caller answered is no longer the one up. Nothing was sent.
                    game.put("stale", true);
                } else if (acted == AgentSession.Acted.UNCHANGED) {
                    // The click was sent and nothing in GET /game differs from before it.
                    game.put("unchanged", true);
                }
                return game;
            }
            case "GET /game/stops":
                return session.stopsSnapshot();
            case "POST /game/stops":
                return session.setStops(phases(body, "own"), phases(body, "opponent"));
            case "POST /game/yield":
                session.setAutoPass(str(body, "autoPass", null));
                return session.gameSnapshot(false);
            case "POST /game/decide": {
                final int id = num(body, "id");
                final List<Integer> indices = new ArrayList<>();
                if (body.has("choices") && body.get("choices").isJsonArray()) {
                    for (final JsonElement e : body.getAsJsonArray("choices")) {
                        indices.add(e.getAsInt());
                    }
                }
                String text = optStr(body, "text");
                if (body.has("amounts") && body.get("amounts").isJsonArray()) {
                    // An "assign" decision: one number per option. Carried as text, which works as well.
                    final List<String> amounts = new ArrayList<>();
                    for (final JsonElement e : body.getAsJsonArray("amounts")) {
                        amounts.add(String.valueOf(e.getAsInt()));
                    }
                    text = String.join(",", amounts);
                }
                if (!session.decide(id, indices, text)) {
                    throw new IllegalStateException("no pending decision with id " + id);
                }
                return session.gameSnapshot(false);
            }
            default:
                throw new IllegalArgumentException("unknown route " + method + " " + path);
        }
    }

    Map<String, Object> sit(final JsonObject body) throws InterruptedException {
        final String deckName = optStr(body, "deck");
        final String dck = optStr(body, "dck");
        final String deckFile = optStr(body, "deckFile");
        // A bad deck fails here, before a seat is taken
        final Deck deck;
        if (deckFile != null) {
            deck = readDeckFile(deckFile);
        } else {
            deck = deckName == null && dck == null ? null : resolveDeck(deckName, dck);
        }
        return session.sit(str(body, "host", DEFAULT_HOST), hostPort(body), str(body, "name", DEFAULT_NAME), deck);
    }

    private static int hostPort(final JsonObject body) {
        final Integer port = optInt(body, "port");
        return port == null ? DEFAULT_HOST_PORT : port;
    }

    /** A relative path is read from the working directory, the folder that holds Forge's res. */
    static Deck readDeckFile(final String path) {
        final File file = new File(path);
        final Deck deck = file.isFile() ? DeckSerializer.fromFile(file) : null;
        if (deck == null) {
            throw new IllegalArgumentException("no deck file at '" + file.getAbsolutePath() + "'");
        }
        return deck;
    }

    private Deck resolveDeck(final String name, final String text) {
        if (text != null) {
            final Map<String, List<String>> sections = new LinkedHashMap<>();
            String current = "metadata";
            for (final String line : text.split("\\r?\\n")) {
                final String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    current = t.substring(1, t.length() - 1).toLowerCase();
                    sections.computeIfAbsent(current, k -> new ArrayList<>());
                } else if (!t.isEmpty()) {
                    sections.computeIfAbsent(current, k -> new ArrayList<>()).add(t);
                }
            }
            return DeckSerializer.fromSections(sections);
        }
        if (name == null) {
            if (session.pool() != null) {
                return session.pool();
            }
            throw new IllegalArgumentException("give a deck name, dck text, or draft a pool first");
        }
        final Deck saved = savedDeck(name);
        if (saved == null) {
            throw new IllegalArgumentException("no deck named '" + name + "' in network-events or constructed");
        }
        return saved;
    }

    /** The deck of that name in network-events or constructed, or null. */
    static Deck savedDeck(final String name) {
        for (final Deck d : FModel.getDecks().getNetworkEventDecks()) {
            if (d.getName().equalsIgnoreCase(name)) {
                return d;
            }
        }
        for (final Deck d : FModel.getDecks().getConstructed()) {
            if (d.getName().equalsIgnoreCase(name)) {
                return d;
            }
        }
        return null;
    }

    private static String optStr(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** A list of phases by Forge's own names, as `phase` has them in a game answer. Null when the key is absent. */
    private static Set<PhaseType> phases(final JsonObject o, final String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        if (!o.get(key).isJsonArray()) {
            throw new IllegalArgumentException("'" + key + "' must be a list of phase names");
        }
        final Set<PhaseType> set = EnumSet.noneOf(PhaseType.class);
        for (final JsonElement e : o.getAsJsonArray(key)) {
            try {
                set.add(PhaseType.valueOf(e.getAsString()));
            } catch (final IllegalArgumentException unknown) {
                throw new IllegalArgumentException("unknown phase '" + e.getAsString() + "'. The phases are " + AgentGuiGame.STOPPABLE);
            }
        }
        return set;
    }

    private static boolean flag(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    private static Integer optInt(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : null;
    }

    private static int num(final JsonObject o, final String key) {
        final Integer value = optInt(o, key);
        if (value == null) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return value;
    }

    private static String str(final JsonObject o, final String key, final String dflt) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            return o.get(key).getAsString();
        }
        if (dflt == null) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return dflt;
    }

    private static JsonObject readBody(final InputStream in) throws IOException {
        final String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (s.isBlank()) {
            return new JsonObject();
        }
        final JsonElement e = JsonParser.parseString(s);
        return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static Map<String, String> query(final String raw) {
        final Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (final String part : raw.split("&")) {
            final int eq = part.indexOf('=');
            if (eq > 0) {
                m.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
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
