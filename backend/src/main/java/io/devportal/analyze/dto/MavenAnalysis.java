package io.devportal.analyze.dto;

import java.util.List;

public record MavenAnalysis(
    List<MavenCoord> publishedArtifacts,
    List<MavenCoord> declaredDependencies,
    List<String> warnings
) {}
