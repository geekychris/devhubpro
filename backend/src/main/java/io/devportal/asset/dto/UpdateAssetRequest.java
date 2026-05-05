package io.devportal.asset.dto;

import java.util.List;

/** All fields optional — null means "leave unchanged". */
public record UpdateAssetRequest(
    String name,
    String description,
    String owner,
    String type,
    String language,
    String repoUrl,
    String repoDefaultBranch,
    List<String> tags,
    String lifecycle,
    String k8sNamespace
) {}
