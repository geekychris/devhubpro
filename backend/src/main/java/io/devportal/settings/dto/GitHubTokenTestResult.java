package io.devportal.settings.dto;

public record GitHubTokenTestResult(
    boolean ok,
    String authenticatedAs,   // null if anonymous
    String scopes,            // header echo if available
    String message
) {}
