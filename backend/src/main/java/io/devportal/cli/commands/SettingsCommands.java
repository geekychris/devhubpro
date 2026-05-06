package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.github.GitHubClient;
import io.devportal.secret.SecretService;
import io.devportal.settings.dto.GitHubTokenInfo;
import io.devportal.settings.dto.GitHubTokenTestResult;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "settings", description = "GitHub PAT and other settings.")
public class SettingsCommands {

    private final SecretService secrets;
    private final GitHubClient github;

    public SettingsCommands(SecretService secrets, GitHubClient github) {
        this.secrets = secrets;
        this.github = github;
    }

    @Command(name = "github-show", description = "Show whether a GitHub token is configured + its source.")
    public Integer githubShow() {
        SecretService.TokenInfo info = secrets.githubTokenInfo();
        GitHubTokenInfo view = new GitHubTokenInfo(info.hasToken(), info.preview(), info.source().name());
        System.out.println(Out.yaml(view));
        return 0;
    }

    @Command(name = "github-set", description = "Set the GitHub PAT (stored mode 0600 at ~/.devportal/secrets).")
    public Integer githubSet(@Parameters(paramLabel = "TOKEN") String token) throws Exception {
        secrets.setGithubToken(token);
        github.invalidate();
        SecretService.TokenInfo info = secrets.githubTokenInfo();
        System.out.println(Out.yaml(new GitHubTokenInfo(info.hasToken(), info.preview(), info.source().name())));
        return 0;
    }

    @Command(name = "github-clear", description = "Clear the stored token.")
    public Integer githubClear() throws Exception {
        secrets.clearGithubToken();
        github.invalidate();
        System.out.println("cleared");
        return 0;
    }

    @Command(name = "github-test", description = "Test the configured token against the GitHub API.")
    public Integer githubTest() {
        try {
            GitHubClient.ConnectionResult r = github.testConnection();
            System.out.println(Out.yaml(new GitHubTokenTestResult(r.ok(), r.authenticatedAs(), null, r.message())));
            return r.ok() ? 0 : 1;
        } catch (Exception e) {
            System.out.println(Out.yaml(new GitHubTokenTestResult(false, null, null, e.getMessage())));
            return 1;
        }
    }
}
