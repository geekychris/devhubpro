package io.devportal.backup.dto;

public record CreateBackupRequest(
    Boolean includeSecrets,
    Boolean includeLogs,
    String dir,                   // override the default backup root for this call
    Boolean commit,
    Boolean push,
    String message
) {}
