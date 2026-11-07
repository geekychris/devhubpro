package io.devportal.registry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response shapes of the deployments REST API. */
public final class RegistryDtos {

    private RegistryDtos() {}

    /**
     * Enough to create a minimal asset when a report names an asset the portal does not
     * know yet. Ignored when the asset already exists (curated assets are never overwritten).
     */
    public record AssetHint(
        String name,
        String repoUrl,
        String type,
        String description,
        List<String> tags
    ) {}

    /** One deployment as a reporter sees it. Natural key: (assetId, target, namespace, name). */
    public record DeploymentReport(
        String assetId,
        AssetHint asset,
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
        String status,
        Map<String, String> labels,
        String notes,
        String source
    ) {}

    /**
     * A batch of reports from one source. With {@code reconcile}, every live deployment of
     * {@code source} (only on {@code reconcileTargets}, when given) that is not in the batch
     * is retired — so a reporter that sends its complete view keeps the registry exact.
     */
    public record ReportRequest(
        String source,
        Boolean reconcile,
        List<String> reconcileTargets,
        List<DeploymentReport> deployments
    ) {}

    public record ReportResult(
        int created,
        int updated,
        int retired,
        List<String> assetsCreated,
        List<Deployment> deployments
    ) {}

    /** Per-target roll-up for the "what is running where" view. */
    public record TargetSummary(
        String target,
        String targetKind,
        int total,
        int healthy,
        int degraded,
        int down,
        int unknown,
        int reported,
        Instant lastReportedAt
    ) {}
}
