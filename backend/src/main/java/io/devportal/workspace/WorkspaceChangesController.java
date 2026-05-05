package io.devportal.workspace;

import io.devportal.runtime.k8s.scaffold.CommitResult;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.api.errors.GitAPIException;
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

    public WorkspaceChangesController(WorkspaceStatusService status, WorkspaceCommitService committer) {
        this.status = status;
        this.committer = committer;
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
}
