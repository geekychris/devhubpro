package io.devportal.runtime.k8s.cluster.dto;

public record PodEvent(
    String type,            // Normal | Warning
    String reason,
    String message,
    String source,
    String firstSeen,
    String lastSeen,
    int count,
    String involvedObject    // "Pod/<name>"
) {}
