package io.devportal.runtime.k8s.scaffold;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class K8sCommitController {

    private final K8sCommitService commit;

    public K8sCommitController(K8sCommitService commit) { this.commit = commit; }

    @PostMapping("/api/assets/{id}/k8s/scaffold")
    public Map<String, Object> scaffold(@PathVariable String id) throws IOException {
        List<String> files = commit.scaffold(id);
        return Map.of("asset", id, "files", files,
            "note", "Files written to workspace. Review then call /api/assets/" + id
                  + "/k8s/commit-workspace to commit on a branch.");
    }

    /** List React / Vite / Next / Vue / Angular tiers detected in the workspace. */
    @org.springframework.web.bind.annotation.GetMapping("/api/assets/{id}/frontend-tiers")
    public java.util.List<FrontendScaffolder.Tier> frontendTiers(@PathVariable String id) throws IOException {
        return commit.detectFrontendTiers(id);
    }

    /** Scaffold Dockerfile + nginx.conf + k8s manifest for one detected frontend tier. */
    @PostMapping("/api/assets/{id}/scaffold-frontend")
    public FrontendScaffolder.ScaffoldResult scaffoldFrontend(
        @PathVariable String id,
        @RequestParam("path") String path
    ) throws IOException {
        return commit.scaffoldFrontend(id, path);
    }

    /** Scaffold both Dockerfile and k8s manifests in one call. */
    @PostMapping("/api/assets/{id}/scaffold-runtime")
    public K8sCommitService.ScaffoldFullResult scaffoldFull(@PathVariable String id) throws IOException {
        return commit.scaffoldFull(id);
    }

    /** Apply rendered (port-patched) manifests as edits to the source repo, then commit on a branch. */
    @PostMapping("/api/assets/{id}/k8s/commit-render")
    public CommitResult commitRender(
        @PathVariable String id,
        @RequestParam(required = false) String branch,
        @RequestParam(required = false) String message,
        @RequestParam(required = false, defaultValue = "false") boolean push
    ) throws IOException, GitAPIException {
        return commit.commitRender(id, branch, message, push);
    }

    /** Commit whatever edits are currently in the workspace (e.g., after scaffold) on a branch. */
    @PostMapping("/api/assets/{id}/k8s/commit-workspace")
    public CommitResult commitWorkspace(
        @PathVariable String id,
        @RequestParam(required = false) String branch,
        @RequestParam(required = false) String message,
        @RequestParam(required = false, defaultValue = "false") boolean push
    ) throws IOException, GitAPIException {
        return commit.commitWorkspace(id, branch, message, push);
    }
}
