package io.devportal.audit.dto;

public record AuditFinding(
    String code,        // stable machine-readable id, e.g. "manifest.missing"
    String severity,    // "info" | "warn" | "error"
    String area,        // "manifest" | "docs" | "docker" | "k8s" | "ports" | "workspace"
    String message,     // human-readable summary
    String fixHint      // one-line "how to fix" hint
) {}
