package io.devportal.analyze.dto;

public record MavenCoord(
    String groupId,
    String artifactId,
    String version,
    String relativePath
) {
    public String key() { return groupId + ":" + artifactId; }
}
