package io.devportal.analyze.dto;

import java.util.List;

public record AnalyzeReport(
    String assetId,
    String flavor,
    boolean foundPom,
    List<MavenCoord> publishedArtifacts,   // freshly persisted
    List<DependencyMatch> dependencyMatches,
    List<String> warnings
) {
    public record DependencyMatch(
        MavenCoord coord,
        String matchedAssetId,            // null if no portal asset publishes this artifact
        String matchedRelativePath,
        boolean alreadyWired              // true if dep edge already exists
    ) {}
}
