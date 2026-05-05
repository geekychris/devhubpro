package io.devportal.runtime.endpoints;

import java.util.List;

public record AssetEndpoints(
    String assetId,
    List<Endpoint> endpoints
) {
    /**
     * One way to talk to the asset. {@link #scope} is high-level ("local-docker", "k8s-nodeport",
     * "external"); {@link #origin} is a short human-readable note explaining where the URL came
     * from (port allocation, GitHub, etc.).
     */
    public record Endpoint(
        String label,
        String url,
        String scope,
        String origin,
        boolean live,             // destination is currently listening
        boolean hostAccessible,   // reachable from the user's host (vs. cluster-only / external)
        ExposeHint exposeHint     // non-null when there's an action to take if not yet exposed
    ) {}

    /** Suggests how a non-host-accessible URL could be made reachable from the host. */
    public record ExposeHint(
        String kind,             // "port-forward"
        String podName,          // for port-forward
        Integer containerPort
    ) {}
}
