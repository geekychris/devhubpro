package io.devportal.port;

public record PortReservation(
    long id,
    String assetId,
    String slotName,
    String scope,         // "local" | "k8s-nodeport"
    int port,
    String protocol
) {}
