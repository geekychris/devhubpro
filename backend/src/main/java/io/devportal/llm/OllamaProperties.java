package io.devportal.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap defaults for the local Ollama server. Runtime overrides come through
 * {@link OllamaSettingsService}, which writes to {@code ~/.devportal/settings/ollama.json}.
 */
@ConfigurationProperties(prefix = "devportal.ollama")
public record OllamaProperties(
    String endpoint,
    String model
) {
    public OllamaProperties {
        if (endpoint == null || endpoint.isBlank()) endpoint = "http://localhost:11434";
        if (model == null || model.isBlank()) model = "llama3.2";
    }
}
