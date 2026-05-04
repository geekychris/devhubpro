package io.devportal.analyze.dto;

import java.util.List;

public record AutoWireResult(
    String assetId,
    int added,
    int alreadyPresent,
    int unmatched,
    List<String> wiredProducers
) {}
