package io.devportal.spinup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a "spin up" macro job: a single click that walks an asset from registered →
 * running in k8s by chaining build-images → ensure-namespace → kubectl-apply → run-on-apply
 * hooks → endpoint health probe. Polled by the frontend; pattern mirrors {@code ImportJob}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpinupJob(
    long id,
    String assetId,
    String status,           // queued | running | succeeded | failed
    String currentStep,      // human-readable label of the current step
    Instant startedAt,
    Instant finishedAt,
    List<SpinupStep> steps,
    List<String> log,
    String error,
    String entryUrl          // best-guess host-reachable URL once the deploy is healthy
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpinupStep(
        String name,           // BUILD_IMAGES | ENSURE_NAMESPACE | APPLY | RUN_HOOKS | PROBE_ENDPOINTS
        String status,         // pending | running | succeeded | failed | skipped
        Instant startedAt,
        Instant finishedAt,
        String message         // one-line summary for the UI
    ) {}
}
