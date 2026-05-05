package io.devportal.test;

import java.time.Instant;
import java.util.List;

/**
 * Result of running one test fixture. The structured fields ({@code credentials}, {@code links},
 * {@code summary}) are parsed from a {@code DEVPORTAL_FIXTURE: {json}} line in the command's
 * stdout. {@code logTail} carries the last lines of output even when no DEVPORTAL_FIXTURE line is
 * emitted (useful when the script just dumps human-readable output).
 */
public record FixtureResult(
    String name,
    long buildId,                 // underlying build row (command_name=test-fixture) for log lookup
    String status,                // succeeded | failed | running | queued
    Integer exitCode,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    String summary,               // from DEVPORTAL_FIXTURE.summary
    List<Credential> credentials,
    List<Link> links,
    List<String> logTail,         // last ~30 stdout lines, regardless of DEVPORTAL_FIXTURE presence
    String parseError             // null when DEVPORTAL_FIXTURE parsed cleanly or wasn't present
) {
    public record Credential(
        String label,             // e.g. "Tenant A admin"
        String username,
        String password,
        String role,              // optional, e.g. "Admin"
        String url                // optional landing URL
    ) {}

    public record Link(
        String label,
        String url
    ) {}
}
