package io.devportal.runtime.k8s.dto;

import java.time.Instant;
import java.util.List;

/**
 * Punch list of issues for an asset's k8s deployment. Designed to be queried by both the UI and
 * by REST/MCP callers (Claude included) to diagnose what's wrong without hunting through
 * {@code kubectl describe} output.
 */
public record K8sDiagnostics(
    String assetId,
    String namespace,
    Instant evaluatedAt,
    Summary summary,
    List<Finding> findings
) {
    public record Summary(
        int podsTotal,
        int podsRunning,
        int podsPending,
        int podsBroken,        // CrashLoopBackOff, Error, ImagePullBackOff, ErrImageNeverPull, etc.
        int findingsError,
        int findingsWarn
    ) {}

    public record Finding(
        // "error" / "warn" / "info"
        String severity,
        // Stable identifier so callers can suppress / group: image-pull-backoff,
        // err-image-never-pull, crash-loop, error-exit, pending-cpu, pending-pvc, pending-other,
        // image-pull-policy-mismatch, missing-image-locally, missing-dockerfile.
        String code,
        // Short human label, e.g. "Deployment/slash-commands" or "Pod/aoee-server-...".
        String resource,
        // The container/image involved when relevant.
        String pod,
        String image,
        // Plain-English description, one sentence.
        String message,
        // What to do. Also one sentence; can include a kubectl/curl invocation.
        String hint,
        // Last few container log lines, when the pod is crashing.
        List<String> logTail,
        // When the underlying object first appeared (pod creationTimestamp, etc.). Null for
        // manifest-level findings that aren't tied to a live object.
        Instant firstSeenAt,
        // Convenience: seconds elapsed between firstSeenAt and evaluatedAt.
        Long ageSeconds,
        // For crash-loop / error-exit: how many times the container has restarted. Null otherwise.
        Integer restartCount,
        // For container-terminated states: when the most recent termination happened. Null if
        // not applicable.
        Instant lastTransitionAt
    ) {}
}
