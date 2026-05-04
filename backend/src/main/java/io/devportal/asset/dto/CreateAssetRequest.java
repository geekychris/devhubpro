package io.devportal.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record CreateAssetRequest(
    @NotBlank
    @Pattern(regexp = "^[a-z][a-z0-9-]{1,62}[a-z0-9]$")
    String id,

    @NotBlank
    String name,

    String description,
    String owner,

    @NotBlank
    @Pattern(regexp = "^(library|service|meta-asset)$")
    String type,

    String language,

    @NotBlank
    String repoUrl,

    String repoDefaultBranch,
    List<String> tags,
    String lifecycle
) {}
