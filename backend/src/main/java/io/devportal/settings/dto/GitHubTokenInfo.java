package io.devportal.settings.dto;

public record GitHubTokenInfo(
    boolean hasToken,
    String preview,        // "ghp_••••abc1" or null
    String source          // "FILE" | "ENV" | "NONE"
) {}
