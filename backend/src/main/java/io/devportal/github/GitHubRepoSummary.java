package io.devportal.github;

import java.time.Instant;
import java.util.List;

public record GitHubRepoSummary(
    String fullName,
    String name,
    String owner,
    String description,
    String defaultBranch,
    String htmlUrl,
    String cloneUrl,
    boolean fork,
    boolean archived,
    boolean privateRepo,
    List<String> topics,
    Instant pushedAt,
    String primaryLanguage  // GitHub's detected primary language; nullable
) {}
