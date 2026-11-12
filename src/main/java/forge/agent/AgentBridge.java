package forge.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.model.FModel;

/**
 * Local HTTP JSON front for one {@link AgentSession}. Loopback only; the MCP server is the only
 * intended caller.
 */
public final class AgentBridge {

    private final AgentSession session = new AgentSession();
    private final Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private HttpServer server;

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
            final URI uri = ex.getRequestURI();
            final String path = uri.getPath();
            final String method = ex.getRequestMethod();
            final Map<String, String> query = query(uri.getRawQuery());
            final JsonObject body = "POST".equals(method) ? readBody(ex.getRequestBody()) : new JsonObject();
            final Object result = route(method, path, query, body);
            write(ex, 200, result);
        } catch (final IllegalArgumentException | IllegalStateException e) {
            write(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (final Exception e) {
            e.printStackTrace();
            write(ex, 500, Map.of("error", e.toString()));
        }
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
            case "POST /connect": {
                final String host = str(body, "host", "127.0.0.1");
                final int port = body.has("port") ? body.get("port").getAsInt() : 36743;
                final String name = str(body, "name", "Agent");
                session.connect(host, port, name);
                return session.status();
            }
            case "POST /disconnect":
                session.disconnect();
                return session.status();
            case "POST /lobby/ready":
                session.setReady(!body.has("ready") || body.get("ready").getAsBoolean());
                return session.lobbySnapshot();
            case "POST /lobby/avatar":
                session.setAvatar(body.get("index").getAsInt());
                return session.lobbySnapshot();
            case "POST /lobby/deck": {
                final Deck deck = resolveDeck(body);
                session.setDeck(deck);
                return Map.of("deck", deck.getName(), "size", deck.getMain().countAll());
            }
            case "POST /chat":
                session.sendChat(str(body, "text", ""));
                return Map.of("ok", true);
            case "GET /draft/pack":
                return session.draftSnapshot(true);
            case "POST /draft/pick":
                return session.pick(str(body, "card", null));
            case "GET /draft/pool":
                return session.poolSnapshot();
            case "GET /game":
                return session.gameSnapshot(true);
            case "GET /game/log":
                return Map.of("log", session.gameLog(Integer.parseInt(q.getOrDefault("since", "0"))));
            case "POST /game/act": {
                final Integer id = body.has("id") && !body.get("id").isJsonNull() ? body.get("id").getAsInt() : null;
                final Integer color = body.has("color") && !body.get("color").isJsonNull() ? body.get("color").getAsInt() : null;
                session.act(str(body, "action", null), id, color);
                return session.gameSnapshot(false);
            }
            case "POST /game/decide": {
                final int id = body.get("id").getAsInt();
                final List<Integer> indices = new ArrayList<>();
                if (body.has("choices") && body.get("choices").isJsonArray()) {
                    for (final JsonElement e : body.getAsJsonArray("choices")) {
                        indices.add(e.getAsInt());
                    }
                }
                final String text = body.has("text") && !body.get("text").isJsonNull() ? body.get("text").getAsString() : null;
                if (!session.decide(id, indices, text)) {
                    throw new IllegalStateException("no pending decision with id " + id);
                }
                return session.gameSnapshot(false);
            }
            default:
                throw new IllegalArgumentException("unknown route " + method + " " + path);
        }
    }

    private Deck resolveDeck(final JsonObject body) {
        if (body.has("dck")) {
            final String text = body.get("dck").getAsString();
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
        if (!body.has("name") || body.get("name").isJsonNull()) {
            if (session.pool() != null) {
                return session.pool();
            }
            throw new IllegalArgumentException("give a deck name, dck text, or draft a pool first");
        }
        final String name = body.get("name").getAsString();
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
        throw new IllegalArgumentException("no deck named '" + name + "' in network-events or constructed");
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
                m.put(part.substring(0, eq), java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
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
