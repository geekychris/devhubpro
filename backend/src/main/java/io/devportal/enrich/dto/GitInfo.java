package io.devportal.enrich.dto;

import java.time.Instant;
import java.util.List;

public record GitInfo(
    String fullName,
    String description,
    String defaultBranch,
    String homepage,
    String license,
    int stargazersCount,
    int forksCount,
    int openIssuesCount,
    Instant pushedAt,
    Instant updatedAt,
    List<Tag> tags,        // most recent first; empty if none
    List<String> topics
) {
    public record Tag(String name, String sha) {}
}
