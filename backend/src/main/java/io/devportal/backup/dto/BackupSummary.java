package io.devportal.backup.dto;

import java.time.Instant;

public record BackupSummary(
    String stamp,                 // e.g. "20260505-191045"
    String dir,                   // absolute path to the backup folder
    int assetCount,
    int logCount,
    boolean includesSecrets,
    boolean includesLogs,
    String commitSha,             // null when not committed
    int prunedOldBackups,         // how many older folders were removed by retention
    Instant createdAt
) {}
