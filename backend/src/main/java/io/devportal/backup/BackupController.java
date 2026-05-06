package io.devportal.backup;

import io.devportal.backup.dto.BackupSummary;
import io.devportal.backup.dto.CreateBackupRequest;
import io.devportal.backup.dto.RestoreBackupRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) { this.backups = backups; }

    @PostMapping
    public ResponseEntity<BackupSummary> create(@RequestBody(required = false) CreateBackupRequest req)
            throws IOException, GitAPIException {
        CreateBackupRequest r = req == null
            ? new CreateBackupRequest(false, false, null, null, null, null)
            : req;
        BackupService.CreateOptions opts = new BackupService.CreateOptions(
            Boolean.TRUE.equals(r.includeSecrets()),
            Boolean.TRUE.equals(r.includeLogs()),
            r.dir() == null || r.dir().isBlank() ? null : Path.of(r.dir()),
            Boolean.TRUE.equals(r.commit()),
            Boolean.TRUE.equals(r.push()),
            r.message()
        );
        BackupSummary s = backups.create(opts);
        return ResponseEntity.status(201).body(s);
    }

    @GetMapping
    public List<BackupSummary> list() throws IOException { return backups.list(); }

    @PostMapping("/restore")
    public BackupService.RestoreResult restore(@Valid @RequestBody RestoreBackupRequest req)
            throws IOException {
        BackupService.RestoreOptions opts = new BackupService.RestoreOptions(
            Path.of(req.source()),
            Boolean.TRUE.equals(req.includeSecrets()),
            Boolean.TRUE.equals(req.includeLogs())
        );
        return backups.restore(opts);
    }
}
