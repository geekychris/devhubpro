package io.devportal.build;

import java.time.Instant;

public record Build(
    long id,
    String assetId,
    Long parentBuildId,
    BuildMode mode,
    String commandName,
    String commandLine,
    String gitRef,
    String gitSha,
    String workspacePath,
    String logPath,
    BuildStatus status,
    Integer exitCode,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt
) {}
