package io.devportal.search.dto;

import io.devportal.asset.dto.AssetView;
import java.util.List;

public record SearchResult(
    String query,
    List<AssetView> assets,
    List<DocHit> docs
) {
    public record DocHit(
        String assetId,
        String path,
        int lineNumber,
        String snippet
    ) {}
}
