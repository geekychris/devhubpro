package io.devportal.settings.dto;

import jakarta.validation.constraints.NotBlank;

public record SetGitHubTokenRequest(@NotBlank String token) {}
