package io.devportal.asset.dto;

import io.devportal.asset.Dependency;

public record DependencyView(
    long id,
    String consumerId,
    String producerId,
    String versionRef,
    String kind
) {
    public static DependencyView of(Dependency d) {
        return new DependencyView(d.id(), d.consumerId(), d.producerId(), d.versionRef(), d.kind());
    }
}
