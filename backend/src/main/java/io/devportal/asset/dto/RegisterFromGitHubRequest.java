package io.devportal.asset.dto;

import jakarta.validation.constraints.NotBlank;

/** Register a GitHub repo as an asset. If a devportal.yaml exists at HEAD, the manifest seeds the asset. */
public record RegisterFromGitHubRequest(
    @NotBlank String fullName,
    String overrideId
) {}
