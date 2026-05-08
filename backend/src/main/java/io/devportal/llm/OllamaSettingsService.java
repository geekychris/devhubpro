package io.devportal.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Persists and resolves the Ollama endpoint + model. Resolution order:
 *
 * <ol>
 *   <li>JSON file at {@code ~/.devportal/settings/ollama.json}</li>
 *   <li>{@link OllamaProperties} (env / application.yml)</li>
 *   <li>built-in defaults (localhost:11434, llama3.2)</li>
 * </ol>
 */
@Service
@EnableConfigurationProperties(OllamaProperties.class)
public class OllamaSettingsService {

    private static final Logger log = LoggerFactory.getLogger(OllamaSettingsService.class);

    public enum Source { FILE, BOOTSTRAP, DEFAULT }

    private final OllamaProperties bootstrap;
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    public OllamaSettingsService(OllamaProperties bootstrap) {
        this.bootstrap = bootstrap;
        this.file = Path.of(System.getProperty("user.home"), ".devportal", "settings", "ollama.json");
    }

    public Resolved resolve() {
        if (Files.exists(file)) {
            try {
                JsonNode n = json.readTree(Files.readString(file));
                String endpoint = n.path("endpoint").asText(bootstrap.endpoint());
                String model = n.path("model").asText(bootstrap.model());
                return new Resolved(endpoint, model, Source.FILE);
            } catch (IOException e) {
                log.warn("Could not read {}: {}", file, e.getMessage());
            }
        }
        if (bootstrap.endpoint() != null && !bootstrap.endpoint().isBlank()) {
            return new Resolved(bootstrap.endpoint(), bootstrap.model(), Source.BOOTSTRAP);
        }
        return new Resolved("http://localhost:11434", "llama3.2", Source.DEFAULT);
    }

    public Resolved set(String endpoint, String model) throws IOException {
        Resolved cur = resolve();
        String newEndpoint = (endpoint != null && !endpoint.isBlank()) ? endpoint.trim() : cur.endpoint();
        String newModel    = (model    != null && !model.isBlank())    ? model.trim()    : cur.model();
        ObjectNode out = json.createObjectNode();
        out.put("endpoint", newEndpoint);
        out.put("model", newModel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        return new Resolved(newEndpoint, newModel, Source.FILE);
    }

    public record Resolved(String endpoint, String model, Source source) {}
}
