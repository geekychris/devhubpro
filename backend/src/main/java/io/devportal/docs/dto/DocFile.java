package io.devportal.docs.dto;

public record DocFile(
    String path,        // workspace-relative
    String name,
    long sizeBytes
) {}
