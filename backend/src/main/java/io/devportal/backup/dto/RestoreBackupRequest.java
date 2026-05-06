package io.devportal.backup.dto;

import jakarta.validation.constraints.NotBlank;

public record RestoreBackupRequest(
    @NotBlank String source,      // path to the timestamped backup folder
    Boolean includeSecrets,
    Boolean includeLogs
) {}
