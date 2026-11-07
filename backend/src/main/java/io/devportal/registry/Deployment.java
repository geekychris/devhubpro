package io.devportal.registry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One observed instance of an asset on a target ("what is running where").
 * Row mirror of the {@code deployment} table plus the owning asset's name.
 *
 * <p>{@code key} is the natural key {@code assetId|target|namespace|name} that report
 * upserts are idempotent on. {@code status} is the effective status: what the health
 * poller last saw when there is something to poll, otherwise what the reporter said
 * ({@code reportedStatus}, or {@code reported} when it said nothing).
 */
public record Deployment(
    long id,
    String key,
    String assetId,
    String assetName,
    String target,
    String targetKind,
    String namespace,
    String name,
    String version,
    String commit,
    String ref,
    String phase,
    List<DeploymentUrl> urls,
    String healthUrl,
    String source,
    Map<String, String> labels,
    String notes,
    String status,
    String reportedStatus,
    Instant lastCheckedAt,
    String lastError,
    Integer lastLatencyMs,
    Instant firstSeenAt,
    Instant lastReportedAt,
    Instant retiredAt
) {

    /** The URL the health poller probes: the health URL, else the primary URL of a running deployment. */
    public String probeUrl() {
        return Deployments.probeUrl(healthUrl, phase, urls);
    }
}
