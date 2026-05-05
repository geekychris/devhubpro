package io.devportal.workspace;

import java.util.List;

/** Summary of an asset's workspace git state, used by the Changes tab. */
public record WorkspaceStatus(
    String assetId,
    String branch,             // current branch name (or "(detached)")
    String head,               // HEAD sha (full)
    int aheadCount,            // commits ahead of origin/<branch>; -1 if no upstream
    int behindCount,           // commits behind origin/<branch>; -1 if no upstream
    boolean clean,             // no modified, added, removed, or untracked files
    List<FileChange> files
) {
    public record FileChange(
        String path,
        String status            // "modified" | "added" | "deleted" | "untracked" | "missing" | "conflicting"
    ) {}
}
