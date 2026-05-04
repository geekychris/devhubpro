package io.devportal.meta.dto;

import io.devportal.meta.MetaAsset;
import java.time.Instant;
import java.util.Map;

public record MetaAssetView(
    String id,
    String name,
    String kind,
    Map<String, Object> config,
    boolean provisionedByPortal,
    Instant createdAt,
    Instant updatedAt
) {
    public static MetaAssetView of(MetaAsset m) {
        return new MetaAssetView(m.id(), m.name(), m.kind(), m.config(),
            m.provisionedByPortal(), m.createdAt(), m.updatedAt());
    }
}
