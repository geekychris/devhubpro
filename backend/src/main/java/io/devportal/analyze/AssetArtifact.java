package io.devportal.analyze;

import java.time.Instant;

public record AssetArtifact(
    long id,
    String assetId,
    String flavor,
    String groupId,
    String artifactId,
    String version,
    String relativePath,
    Instant detectedAt
) {}
