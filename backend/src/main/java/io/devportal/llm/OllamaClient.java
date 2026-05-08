package io.devportal.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the Ollama REST API. Reads the configured endpoint per call so
 * settings changes take effect without restart.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration GENERATE_TIMEOUT = Duration.ofMinutes(5);

    private final OllamaSettingsService settings;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public OllamaClient(OllamaSettingsService settings) {
        this.settings = settings;
    }

    /** Result of a {@code GET /api/version} probe — used by the settings test button. */
    public record Health(boolean ok, String endpoint, String version, String error) {}

    public Health testConnection() {
        var s = settings.resolve();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(s.endpoint() + "/api/version"))
                    .timeout(HEALTH_TIMEOUT).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return new Health(false, s.endpoint(), null, "HTTP " + r.statusCode() + ": " + r.body());
            }
            JsonNode body = json.readTree(r.body());
            return new Health(true, s.endpoint(), body.path("version").asText(null), null);
        } catch (Exception e) {
            return new Health(false, s.endpoint(), null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public List<String> listModels() throws IOException, InterruptedException {
        var s = settings.resolve();
        HttpRequest req = HttpRequest.newBuilder(URI.create(s.endpoint() + "/api/tags"))
                .timeout(HEALTH_TIMEOUT).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("ollama /api/tags HTTP " + r.statusCode() + ": " + r.body());
        }
        JsonNode body = json.readTree(r.body());
        List<String> out = new ArrayList<>();
        for (JsonNode m : body.path("models")) {
            String name = m.path("name").asText(null);
            if (name != null) out.add(name);
        }
        return out;
    }

    /**
     * Non-streaming completion. {@code prompt} is sent as the user message; we ask for JSON
     * mode when {@code expectJson} so the model returns parseable output.
     */
    public String generate(String model, String prompt, boolean expectJson) throws IOException, InterruptedException {
        var s = settings.resolve();
        String useModel = (model == null || model.isBlank()) ? s.model() : model;

        ObjectNode body = json.createObjectNode();
        body.put("model", useModel);
        body.put("prompt", prompt);
        body.put("stream", false);
        if (expectJson) body.put("format", "json");
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0.2);

        HttpRequest req = HttpRequest.newBuilder(URI.create(s.endpoint() + "/api/generate"))
                .timeout(GENERATE_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("ollama /api/generate HTTP " + r.statusCode() + ": " + r.body());
        }
        JsonNode parsed = json.readTree(r.body());
        return parsed.path("response").asText("");
    }

    @SuppressWarnings("unused")
    private static String join(ArrayNode arr) { return arr.toString(); }
}
