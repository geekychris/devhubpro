package io.devportal.runtime.forward;

import java.time.Instant;

public record PortForwardSession(
    long id,
    String assetId,
    String namespace,
    String podName,
    int containerPort,
    int hostPort,
    String status,           // "running" | "stopped" | "failed"
    String error,            // null on success
    Instant startedAt,
    String host              // honours devportal.urls.host; "localhost" in direct-mode
) {
    public String url() { return "http://" + host + ":" + hostPort + "/"; }
}
