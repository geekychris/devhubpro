package io.devportal.asset;

public record Dependency(
    long id,
    String consumerId,
    String producerId,
    String versionRef,
    String kind
) {}
