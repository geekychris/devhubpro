package io.devportal.manifest;

import java.util.List;

/**
 * Outcome of parsing + validating a devportal.yaml.
 *
 * <p>{@link #manifest()} is non-null when YAML deserialization succeeded, even if the
 * document failed schema validation — callers can choose to surface partial information.
 */
public record ManifestParseResult(
    Manifest manifest,
    boolean valid,
    List<ValidationError> errors,
    String rawYaml
) {
    public record ValidationError(String path, String message) {}
}
