package io.devportal.asset.dto;

import io.devportal.asset.Asset;
import java.time.Instant;
import java.util.List;

public record AssetView(
    String id,
    String name,
    String description,
    String owner,
    String type,
    String language,
    String repoUrl,
    String repoDefaultBranch,
    List<String> tags,
    String lifecycle,
    Instant createdAt,
    Instant updatedAt
) {
    public static AssetView of(Asset a) {
        return new AssetView(a.id(), a.name(), a.description(), a.owner(), a.type(),
            a.language(), a.repoUrl(), a.repoDefaultBranch(), a.tags(), a.lifecycle(),
            a.createdAt(), a.updatedAt());
    }
}
