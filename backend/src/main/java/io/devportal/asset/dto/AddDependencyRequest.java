package io.devportal.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AddDependencyRequest(
    @NotBlank String producerId,
    String versionRef,
    @Pattern(regexp = "^(build|runtime)$") String kind
) {}
