package io.devportal.workspace;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.runtime.k8s.scaffold.CommitResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets/{id}/workspace")
public class WorkspaceChangesController {

    private final WorkspaceStatusService status;
    private final WorkspaceCommitService committer;
    private final WorkspaceService workspace;
    private final AssetRepository assets;

    public WorkspaceChangesController(WorkspaceStatusService status, WorkspaceCommitService committer,
                                      WorkspaceService workspace, AssetRepository assets) {
        this.status = status;
        this.committer = committer;
        this.workspace = workspace;
        this.assets = assets;
    }

    @GetMapping("/status")
    public WorkspaceStatus status(@PathVariable String id) throws IOException, GitAPIException {
        return status.status(id);
    }

    @GetMapping(value = "/diff", produces = MediaType.TEXT_PLAIN_VALUE)
    public String diff(@PathVariable String id, @RequestParam("path") String path) throws IOException {
        return status.diff(id, path);
    }

    public record CommitRequest(String branch, String message, List<String> paths, Boolean push) {}

    @PostMapping("/commit")
    public CommitResult commit(@PathVariable String id, @RequestBody CommitRequest req)
            throws IOException, GitAPIException {
        return committer.commit(id, req.branch(), req.message(),
            req.paths() == null ? List.of() : req.paths(),
            req.push() != null && req.push());
    }

    @PostMapping("/push")
    public CommitResult push(@PathVariable String id, @RequestParam("branch") String branch)
            throws IOException, GitAPIException {
        return committer.push(id, branch);
    }

    public record SyncResult(String head, String ref, boolean discardedLocal) {}

    /**
     * Fetch + reset the workspace to the asset's default branch (or {@code ref}, if given).
     * With {@code discardLocal=true}, skip the dirty-tree check and force-reset to the target —
     * needed for assets whose dev_portal-injected {@code devportal.yaml} is permanently untracked
     * (the repo upstream doesn't carry it), since otherwise the dirty check perma-fails.
     */
    @PostMapping("/sync")
    public SyncResult sync(
        @PathVariable String id,
        @RequestParam(name = "ref", required = false) String ref,
        @RequestParam(name = "discardLocal", required = false, defaultValue = "false") boolean discardLocal
    ) throws IOException, GitAPIException {
        Asset asset = assets.findById(id).orElseThrow(
            () -> new NotFoundException("Asset '" + id + "' not found"));
        String target = ref == null || ref.isBlank() ? asset.repoDefaultBranch() : ref;
        Path ws = workspace.syncCheckout(asset.id(), asset.repoUrl(), target, discardLocal);
        String head;
        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build();
             Git ignored = new Git(repo)) {
            head = repo.resolve("HEAD").name();
        }
        return new SyncResult(head, target, discardLocal);
    }
}
