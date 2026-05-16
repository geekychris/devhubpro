package io.devportal.build.dto;

import jakarta.validation.constraints.Pattern;

/** Body for POST /api/assets/{id}/builds. */
public record KickBuildRequest(
    @Pattern(regexp = "^(shallow|deep)$") String mode,
    String commandName,    // key in spec.build.commands; defaults to "build"
    String commandLine,    // override raw command; takes precedence over commandName
    String ref             // git ref to build; defaults to asset's default branch
) {}
