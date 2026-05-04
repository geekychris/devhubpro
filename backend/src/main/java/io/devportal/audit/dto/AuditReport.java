package io.devportal.audit.dto;

import java.time.Instant;
import java.util.List;

public record AuditReport(
    String assetId,
    int errors,
    int warnings,
    int info,
    List<AuditFinding> findings,
    Instant generatedAt
) {}
