package io.devportal.workspace;

import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.springframework.stereotype.Service;

/** Read-only git inspection over a workspace: status, ahead/behind, unified diff for a path. */
@Service
public class WorkspaceStatusService {

    private final AssetRepository assets;
    private final WorkspaceService workspace;

    public WorkspaceStatusService(AssetRepository assets, WorkspaceService workspace) {
        this.assets = assets;
        this.workspace = workspace;
    }

    public WorkspaceStatus status(String assetId) throws IOException, GitAPIException {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        Path ws = workspace.workspaceFor(assetId);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace for '" + assetId + "' is not cloned yet.");
        }
        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build();
             Git git = new Git(repo)) {
            String branch = repo.getBranch();
            String head = repo.resolve("HEAD") == null ? null : repo.resolve("HEAD").name();

            int ahead = -1;
            int behind = -1;
            ObjectId upstream = repo.resolve("refs/remotes/origin/" + branch);
            ObjectId headId = repo.resolve("HEAD");
            if (upstream != null && headId != null) {
                try (RevWalk walk = new RevWalk(repo)) {
                    var headCommit = walk.parseCommit(headId);
                    var upstreamCommit = walk.parseCommit(upstream);
                    ahead = org.eclipse.jgit.revwalk.RevWalkUtils.count(walk, headCommit, upstreamCommit);
                    walk.reset();
                    behind = org.eclipse.jgit.revwalk.RevWalkUtils.count(walk, upstreamCommit, headCommit);
                }
            }

            var s = git.status().call();
            List<WorkspaceStatus.FileChange> files = new ArrayList<>();
            s.getModified().forEach(p -> files.add(new WorkspaceStatus.FileChange(p, "modified")));
            s.getAdded().forEach(p -> files.add(new WorkspaceStatus.FileChange(p, "added")));
            s.getChanged().forEach(p -> files.add(new WorkspaceStatus.FileChange(p, "modified")));
            s.getRemoved().forEach(p -> files.add(new WorkspaceStatus.FileChange(p, "deleted")));
            s.getMissing().forEach(p -> files.add(new WorkspaceStatus.FileChange(p, "missing")));
            s.getUntracked().forEach(p -> files.add(new WorkspaceStatus.FileChange(p, "untracked")));
            s.getConflicting().forEach(p -> files.add(new WorkspaceStatus.FileChange(p, "conflicting")));

            boolean clean = files.isEmpty();
            return new WorkspaceStatus(assetId, branch, head, ahead, behind, clean, files);
        }
    }

    /** Unified diff between HEAD and the working tree for one path. Empty string if no change. */
    public String diff(String assetId, String relPath) throws IOException {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        if (relPath == null || relPath.isBlank()) {
            throw new ConflictException("path is required");
        }
        if (relPath.contains("..")) {
            throw new ConflictException("path traversal rejected");
        }
        Path ws = workspace.workspaceFor(assetId);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace for '" + assetId + "' is not cloned yet.");
        }
        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build()) {
            ObjectId headTreeId = repo.resolve("HEAD^{tree}");
            try (RevWalk walk = new RevWalk(repo);
                 ByteArrayOutputStream out = new ByteArrayOutputStream();
                 DiffFormatter df = new DiffFormatter(out)) {
                df.setRepository(repo);
                df.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(relPath));
                CanonicalTreeParser headParser = new CanonicalTreeParser();
                if (headTreeId != null) {
                    try (var reader = repo.newObjectReader()) {
                        headParser.reset(reader, headTreeId);
                    }
                }
                FileTreeIterator wsIter = new FileTreeIterator(repo);
                List<DiffEntry> entries = df.scan(headParser, wsIter);
                for (DiffEntry e : entries) df.format(e);
                df.flush();
                return out.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
