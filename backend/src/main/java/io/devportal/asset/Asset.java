package io.devportal.asset;

import java.time.Instant;
import java.util.List;

/** Row mirror of the {@code asset} table. */
public record Asset(
    String id,
    String name,
    String description,
    String owner,
    String type,
    String language,
    String repoUrl,
    String repoDefaultBranch,
    List<String> tags,
    String lifecycle,
    String k8sNamespace,    // nullable; null means "use asset id" or fall through to manifest/default
    boolean favorite,
    Integer rating,         // nullable; null = unrated, otherwise 1..5
    Instant createdAt,
    Instant updatedAt
) {}
