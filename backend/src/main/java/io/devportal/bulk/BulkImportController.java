package io.devportal.bulk;

import io.devportal.bulk.dto.BulkImportRequest;
import io.devportal.bulk.dto.ImportJob;
import io.devportal.github.GitHubClient;
import io.devportal.github.GitHubRepoSummary;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BulkImportController {

    private final BulkImportService bulk;
    private final GitHubClient github;

    public BulkImportController(BulkImportService bulk, GitHubClient github) {
        this.bulk = bulk;
        this.github = github;
    }

    @GetMapping("/api/orgs/{owner}/repos")
    public List<GitHubRepoSummary> previewRepos(@PathVariable String owner) throws IOException {
        return github.listOrgRepos(owner);
    }

    @PostMapping("/api/orgs/{owner}/import")
    public ResponseEntity<ImportJob> start(
        @PathVariable String owner, @RequestBody(required = false) BulkImportRequest body
    ) {
        BulkImportRequest req = body == null
            ? new BulkImportRequest(null, null, null, null, null, true, true)
            : body;
        return ResponseEntity.status(202).body(bulk.start(owner, req));
    }

    @GetMapping("/api/import-jobs")
    public List<ImportJob> list() {
        return bulk.list();
    }

    @GetMapping("/api/import-jobs/{id}")
    public ImportJob get(@PathVariable long id) {
        ImportJob j = bulk.get(id);
        if (j == null) {
            throw new io.devportal.asset.error.NotFoundException("Import job " + id + " not found");
        }
        return j;
    }

    @PostMapping("/api/import-jobs/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String id, @RequestParam(required = false) String reason) {
        // Cooperative cancellation not yet supported; placeholder for future iteration.
        return ResponseEntity.status(501).build();
    }
}
