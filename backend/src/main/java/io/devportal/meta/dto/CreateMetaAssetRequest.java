package io.devportal.meta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

public record CreateMetaAssetRequest(
    @NotBlank
    @Pattern(regexp = "^[a-z][a-z0-9-]{1,62}[a-z0-9]$")
    String id,
    @NotBlank String name,
    @NotBlank String kind,
    Map<String, Object> config,
    Boolean provisionedByPortal
) {}
