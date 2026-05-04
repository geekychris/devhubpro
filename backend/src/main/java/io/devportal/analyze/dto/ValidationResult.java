package io.devportal.analyze.dto;

public record ValidationResult(
    String repoUrl,
    String fullName,
    boolean reachable,
    boolean defaultBranchMatches,
    String defaultBranchRemote,
    boolean hasManifest,
    boolean hasPom,
    boolean hasGradle,
    boolean hasPackageJson,
    boolean hasDockerfile,
    boolean hasK8sDir,
    String error
) {}
