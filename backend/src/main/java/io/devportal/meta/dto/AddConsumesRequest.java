package io.devportal.meta.dto;

import jakarta.validation.constraints.NotBlank;

public record AddConsumesRequest(
    @NotBlank String metaAssetId,
    String role
) {}
