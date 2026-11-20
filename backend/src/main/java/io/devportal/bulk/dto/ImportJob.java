package io.devportal.bulk.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ImportJob(
    long id,
    String owner,
    BulkImportRequest request,
    String status,            // "queued" | "running" | "succeeded" | "failed" | "cancelled"
    String phase,             // what it is doing: "listing (200 so far)", "filtering: languages 40/120", "importing 3/58"…
    Instant startedAt,
    Instant finishedAt,
    String currentRepo,       // what we're processing right now
    int totalMatched,
    int processed,
    int registered,
    int alreadyRegistered,
    int analyzed,
    int autoWired,
    List<String> log,
    List<Map<String, Object>> results,  // per-repo summary entries
    String error
) {}
