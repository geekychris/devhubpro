package io.devportal.llm;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings endpoints for the local Ollama integration. */
@RestController
@RequestMapping("/api/settings/ollama")
public class OllamaSettingsController {

    private final OllamaSettingsService settings;
    private final OllamaClient client;

    public OllamaSettingsController(OllamaSettingsService settings, OllamaClient client) {
        this.settings = settings;
        this.client = client;
    }

    @GetMapping
    public Map<String, Object> get() {
        var r = settings.resolve();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", r.endpoint());
        out.put("model", r.model());
        out.put("source", r.source().name());
        return out;
    }

    public record SetRequest(String endpoint, String model) {}

    @PutMapping
    public Map<String, Object> set(@RequestBody SetRequest req) throws IOException {
        var r = settings.set(req.endpoint(), req.model());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", r.endpoint());
        out.put("model", r.model());
        out.put("source", r.source().name());
        return out;
    }

    @PostMapping("/test")
    public OllamaClient.Health test() {
        return client.testConnection();
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        try {
            List<String> models = client.listModels();
            return Map.of("ok", true, "models", models);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "models", List.of());
        }
    }
}
