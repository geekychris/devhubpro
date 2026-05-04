package io.devportal.github;

public record GitHubFileContent(
    String path,
    String ref,
    String sha,
    String content
) {}
