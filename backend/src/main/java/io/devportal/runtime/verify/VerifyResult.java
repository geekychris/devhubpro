package io.devportal.runtime.verify;

import java.util.List;

public record VerifyResult(
    String assetId,
    String stage,            // "docker" | "k8s"
    boolean passed,
    String failedAt,         // null on success; "build" | "run" | "ready" | "probe"
    List<Step> steps,
    String summary
) {
    public record Step(String name, boolean ok, long durationMs, String detail) {}
}
