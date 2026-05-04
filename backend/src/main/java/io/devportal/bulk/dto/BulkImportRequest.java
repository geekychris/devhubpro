package io.devportal.bulk.dto;

import java.util.List;

public record BulkImportRequest(
    /** Comma list of GitHub primary languages to include (case-insensitive). Empty = all. */
    List<String> languages,
    /** Regex strings; if any matches the repo name, include. Empty = all. */
    List<String> includePatterns,
    /** Regex strings; if any matches the repo name, exclude. */
    List<String> excludePatterns,
    /** Skip archived repos. Default true. */
    Boolean skipArchived,
    /** Skip forks. Default true. */
    Boolean skipForks,
    /** Clone each match into its workspace and run analysis (Maven, resource detection). */
    Boolean cloneAndAnalyze,
    /** After cloning all matches, run auto-wire for each Java asset to build the dep graph. */
    Boolean autoWireMavenDeps
) {}
