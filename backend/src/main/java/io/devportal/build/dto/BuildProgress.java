package io.devportal.build.dto;

import java.time.Instant;
import java.util.List;

/**
 * Single round-trip view over a build chain (parent + children). Used by the UI to render live
 * progress and by REST/MCP callers (Claude included) to diagnose what's happening without
 * stitching together /builds/{id}, /chain, and /log themselves.
 */
public record BuildProgress(
    long buildId,
    String assetId,
    String commandName,
    String status,
    String mode,                  // "deep" or "shallow"
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,              // null while running
    Summary summary,
    String summaryText,           // null if no summary log; otherwise the parent log (image-build chains)
    List<ChildProgress> children
) {
    /** Aggregated counts so callers can show "5/9 done" without iterating children client-side. */
    public record Summary(
        int total,
        int succeeded,
        int failed,
        int running,
        int queued,
        int cancelled,
        int skipped              // entries reported as SKIPPED in the parent log (no child build row)
    ) {}

    public record ChildProgress(
        long id,
        String assetId,
        String commandName,
        String status,
        Integer exitCode,
        // Short human label parsed from the commandLine — e.g. the image tag for a docker build,
        // or the cargo subcommand. Falls back to commandName.
        String label,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        // Last few log lines, useful when status=running (live tail) or status=failed (root cause).
        // Empty list when log file isn't yet flushed or doesn't exist.
        List<String> tailLines,
        // For failed builds: a one-line distillation of the failure (first ERROR / Exception line),
        // null otherwise.
        String errorHint,
        String logPath
    ) {}
}
