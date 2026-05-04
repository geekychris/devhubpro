package io.devportal.discover.dto;

import java.util.List;

public record DiscoveredResources(
    List<String> dockerfiles,        // relative paths
    List<String> dockerComposeFiles,
    List<String> kubernetesPaths,    // dirs or files holding k8s manifests
    List<String> helmCharts,         // dirs containing Chart.yaml
    List<String> kustomizations,     // dirs/files with kustomization.yaml
    String detectedLanguage          // best-guess primary language for the repo
) {}
