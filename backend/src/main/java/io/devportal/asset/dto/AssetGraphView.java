package io.devportal.asset.dto;

import java.util.List;

public record AssetGraphView(
    String rootId,
    List<AssetView> nodes,
    List<DependencyView> edges
) {}
