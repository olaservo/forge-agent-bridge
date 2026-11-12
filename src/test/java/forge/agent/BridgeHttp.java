package forge.agent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Drives an {@link AgentBridge} over HTTP the way the MCP server does, and records the largest
 * response seen per route as a JSON fixture. The fixtures are copied into the MCP server repo,
 * where a test validates every tool's output schema against them.
 */
final class BridgeHttp {

    private final String base;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();
    private final Map<String, JsonObject> fixtures = new TreeMap<>();

    BridgeHttp(final int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    JsonObject get(final String path) throws IOException, InterruptedException {
        return get(path, Map.of());
    }

    JsonObject get(final String path, final Map<String, String> query) throws IOException, InterruptedException {
        final StringBuilder url = new StringBuilder(base).append(path);
        String sep = "?";
        for (final Map.Entry<String, String> e : query.entrySet()) {
            url.append(sep).append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            sep = "&";
        }
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString())).GET().build();
        return send("GET " + path, req);
    }

    JsonObject post(final String path, final JsonObject body) throws IOException, InterruptedException {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        return send("POST " + path, req);
    }

    /** Like {@link #post} but returns the error body instead of throwing on a 4xx. */
    JsonObject postExpectingError(final String path, final JsonObject body) throws IOException, InterruptedException {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 400) {
            throw new AssertionError("expected an error from " + path + " but got " + res.statusCode() + ": " + res.body());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private JsonObject send(final String key, final HttpRequest req) throws IOException, InterruptedException {
        final HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        final JsonElement parsed = JsonParser.parseString(res.body());
        if (res.statusCode() != 200) {
            throw new IllegalStateException(key + " -> " + res.statusCode() + ": " + res.body());
        }
        final JsonObject obj = parsed.getAsJsonObject();
        record(key, obj);
        return obj;
    }

    /** Keeps the largest payload per key; the richest snapshot is the most useful fixture. */
    void record(final String key, final JsonObject obj) {
        final JsonObject prev = fixtures.get(key);
        if (prev == null || obj.toString().length() > prev.toString().length()) {
            fixtures.put(key, obj.deepCopy());
        }
    }

    static JsonObject body(final Object... kv) {
        final JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            final String k = String.valueOf(kv[i]);
            final Object v = kv[i + 1];
            if (v == null) {
                o.add(k, null);
            } else if (v instanceof Number n) {
                o.addProperty(k, n);
            } else if (v instanceof Boolean b) {
                o.addProperty(k, b);
            } else if (v instanceof JsonElement e) {
                o.add(k, e);
            } else {
                o.addProperty(k, String.valueOf(v));
            }
        }
        return o;
    }

    /** Writes one file per key plus an index, under {@code -Dagent.fixtures} or {@code target/agent-fixtures}. */
    void saveFixtures(final String prefix) throws IOException {
        final Path dir = Paths.get(System.getProperty("agent.fixtures", "target/agent-fixtures"));
        Files.createDirectories(dir);
        final Map<String, String> index = new LinkedHashMap<>();
        final Path indexPath = dir.resolve("index.json");
        if (Files.exists(indexPath)) {
            final JsonObject existing = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
            for (final String k : existing.keySet()) {
                index.put(k, existing.get(k).getAsString());
            }
        }
        for (final Map.Entry<String, JsonObject> e : fixtures.entrySet()) {
            final String file = prefix + "-" + e.getKey().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "") + ".json";
            Files.writeString(dir.resolve(file), gson.toJson(e.getValue()) + "\n");
            index.put(prefix + " " + e.getKey(), file);
        }
        Files.writeString(indexPath, gson.toJson(index) + "\n");
        System.out.println("[fixtures] " + fixtures.size() + " written to " + dir.toAbsolutePath());
    }
}
