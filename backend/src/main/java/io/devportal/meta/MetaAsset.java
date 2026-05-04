package io.devportal.meta;

import java.time.Instant;
import java.util.Map;

public record MetaAsset(
    String id,
    String name,
    String kind,                  // "postgres" | "redis" | "memcache" | "opensearch" | "minio" | ...
    Map<String, Object> config,   // host, port, credentials_secret_ref, ...
    boolean provisionedByPortal,
    Instant createdAt,
    Instant updatedAt
) {}
