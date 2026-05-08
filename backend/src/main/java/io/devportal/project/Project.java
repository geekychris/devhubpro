package io.devportal.project;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * A manually-curated container for assets (and other projects). Forms a tree via
 * {@code parentId}; the root level is {@code parentId == null}. {@code metadata} is an
 * opaque JSONB blob admins can use for arbitrary fields (owner, link, notes, etc.).
 */
public record Project(
    long id,
    Long parentId,
    String name,
    String description,
    JsonNode metadata,
    int sortOrder,
    Instant createdAt,
    Instant updatedAt
) {}
