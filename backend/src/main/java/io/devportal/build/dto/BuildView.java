package io.devportal.build.dto;

import io.devportal.build.Build;
import java.time.Instant;

public record BuildView(
    long id,
    String assetId,
    Long parentBuildId,
    String mode,
    String commandName,
    String commandLine,
    String gitRef,
    String gitSha,
    String workspacePath,
    String logPath,
    String status,
    Integer exitCode,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt
) {
    public static BuildView of(Build b) {
        return new BuildView(b.id(), b.assetId(), b.parentBuildId(),
            b.mode().dbValue(), b.commandName(), b.commandLine(), b.gitRef(), b.gitSha(),
            b.workspacePath(), b.logPath(), b.status().dbValue(), b.exitCode(),
            b.startedAt(), b.finishedAt(), b.createdAt());
    }
}
