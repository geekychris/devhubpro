package io.devportal.settings;

import io.devportal.github.GitHubClient;
import io.devportal.secret.SecretService;
import io.devportal.settings.dto.GitHubTokenInfo;
import io.devportal.settings.dto.GitHubTokenTestResult;
import io.devportal.settings.dto.SetGitHubTokenRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SecretService secrets;
    private final GitHubClient github;

    public SettingsController(SecretService secrets, GitHubClient github) {
        this.secrets = secrets;
        this.github = github;
    }

    @GetMapping("/github")
    public GitHubTokenInfo get() {
        SecretService.TokenInfo info = secrets.githubTokenInfo();
        return new GitHubTokenInfo(info.hasToken(), info.preview(), info.source().name());
    }

    @PutMapping("/github")
    public ResponseEntity<GitHubTokenInfo> set(@Valid @RequestBody SetGitHubTokenRequest req) throws IOException {
        secrets.setGithubToken(req.token());
        github.invalidate();
        return ResponseEntity.ok(get());
    }

    @DeleteMapping("/github")
    public ResponseEntity<GitHubTokenInfo> clear() throws IOException {
        secrets.clearGithubToken();
        github.invalidate();
        return ResponseEntity.ok(get());
    }

    @PostMapping("/github/test")
    public GitHubTokenTestResult test() {
        try {
            GitHubClient.ConnectionResult r = github.testConnection();
            return new GitHubTokenTestResult(r.ok(), r.authenticatedAs(), null, r.message());
        } catch (IOException e) {
            return new GitHubTokenTestResult(false, null, null, e.getMessage());
        }
    }
}
