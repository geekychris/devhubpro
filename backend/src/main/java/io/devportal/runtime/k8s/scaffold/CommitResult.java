package io.devportal.runtime.k8s.scaffold;

import java.util.List;

public record CommitResult(
    String assetId,
    String branch,
    String commit,                 // null if nothing to commit
    List<String> filesChanged,
    boolean pushed,
    String pushOutput,             // null if not pushed
    String prSuggestion            // hint URL/command for opening a PR
) {}
