package io.devportal.github;

import io.devportal.secret.SecretService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final SecretService secrets;
    private volatile GitHub cached;
    private volatile String cachedToken;

    public GitHubClient(@Lazy SecretService secrets) {
        this.secrets = secrets;
    }

    /** Drop the cached client so the next call re-reads the token. Called when the token is changed via UI. */
    public synchronized void invalidate() {
        cached = null;
        cachedToken = null;
    }

    private GitHub gh() throws IOException {
        String token = secrets.githubToken();
        GitHub local = cached;
        if (local != null && java.util.Objects.equals(token, cachedToken)) return local;
        synchronized (this) {
            String current = secrets.githubToken();
            if (cached != null && java.util.Objects.equals(current, cachedToken)) return cached;
            GitHubBuilder b = new GitHubBuilder();
            if (current != null && !current.isBlank()) {
                b = b.withOAuthToken(current);
            } else {
                log.warn("No GitHub token configured — using anonymous access (rate-limited, public-only)");
            }
            cached = b.build();
            cachedToken = current;
            return cached;
        }
    }

    /** List repos visible to the authenticated user, or for {@code owner} if specified. */
    public List<GitHubRepoSummary> listRepos(String owner) throws IOException {
        List<GitHubRepoSummary> out = new ArrayList<>();
        Iterable<GHRepository> repos;
        if (owner == null || owner.isBlank()) {
            repos = gh().getMyself().listRepositories().withPageSize(50);
        } else {
            repos = gh().getUser(owner).listRepositories().withPageSize(50);
        }
        for (GHRepository r : repos) {
            out.add(toSummary(r));
        }
        return out;
    }

    /**
     * List repos visible under {@code owner}. Order of attempts:
     * <ol>
     *   <li>If {@code owner} matches the authenticated user, use {@code getMyself()} so private
     *       repos owned by that user are visible — the public {@code getUser(...)} listing hides them.</li>
     *   <li>Try the org endpoint (true orgs; org members see private repos there too).</li>
     *   <li>Fall back to {@code getUser(owner)} for other users (public repos only).</li>
     * </ol>
     */
    public List<GitHubRepoSummary> listOrgRepos(String owner) throws IOException {
        GitHub g = gh();
        List<GitHubRepoSummary> out = new ArrayList<>();

        String myLogin = null;
        try {
            myLogin = g.getMyself().getLogin();
        } catch (Exception ignored) {
            // anonymous or no user scope
        }

        if (owner != null && owner.equalsIgnoreCase(myLogin)) {
            for (GHRepository r : g.getMyself().listRepositories().withPageSize(50)) {
                out.add(toSummary(r));
            }
            return out;
        }

        try {
            for (GHRepository r : g.getOrganization(owner).listRepositories().withPageSize(50)) {
                out.add(toSummary(r));
            }
            return out;
        } catch (GHFileNotFoundException e) {
            return listRepos(owner);
        }
    }

    public GitHubRepoSummary getRepo(String fullName) throws IOException {
        return toSummary(gh().getRepository(fullName));
    }

    /** Direct access to the kohsuke GHRepository for callers that need fields beyond summary. */
    public GHRepository getRepoRaw(String fullName) throws IOException {
        return gh().getRepository(fullName);
    }

    /**
     * Lowercased set of every language GitHub detected in the repo (any non-zero byte share),
     * not just the byte-count-winning primary. Use this when {@link GitHubRepoSummary#primaryLanguage()}
     * lies because one big binary/notebook/CUDA file flipped the winner.
     */
    public java.util.Set<String> languagesOf(String fullName) throws IOException {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String lang : gh().getRepository(fullName).listLanguages().keySet()) {
            if (lang != null) out.add(lang.toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    /** Fetch a file. Returns empty if the file is missing at the given ref. */
    public Optional<GitHubFileContent> getFile(String fullName, String path, String ref) throws IOException {
        GHRepository repo = gh().getRepository(fullName);
        String resolvedRef = (ref == null || ref.isBlank()) ? repo.getDefaultBranch() : ref;
        try {
            GHContent c = repo.getFileContent(path, resolvedRef);
            String body;
            try (InputStream in = c.read()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return Optional.of(new GitHubFileContent(c.getPath(), resolvedRef, c.getSha(), body));
        } catch (GHFileNotFoundException e) {
            return Optional.empty();
        }
    }

    /** Convenience: fetch the devportal.yaml manifest at a given ref, if present. */
    public Optional<GitHubFileContent> getManifest(String fullName, String ref) throws IOException {
        return getFile(fullName, "devportal.yaml", ref);
    }

    /**
     * Lightweight connectivity probe for the settings UI. If a token is set, returns the
     * authenticated login; otherwise verifies anonymous access works against a public repo.
     */
    public ConnectionResult testConnection() throws IOException {
        boolean hasToken = cachedToken != null && !cachedToken.isBlank();
        // Force resolution against current secret store
        GitHub g = gh();
        boolean authedNow = cachedToken != null && !cachedToken.isBlank();
        if (authedNow) {
            String login = g.getMyself().getLogin();
            return new ConnectionResult(true, login, "Authenticated as " + login);
        }
        // Anonymous — verify reachability with a known public repo.
        g.getRepository("octocat/Hello-World");
        return new ConnectionResult(true, null,
            "Anonymous access works (rate-limited, public repos only). " + (hasToken ? "" : "No token set."));
    }

    public record ConnectionResult(boolean ok, String authenticatedAs, String message) {}

    private GitHubRepoSummary toSummary(GHRepository r) throws IOException {
        return new GitHubRepoSummary(
            r.getFullName(),
            r.getName(),
            r.getOwnerName(),
            r.getDescription(),
            r.getDefaultBranch(),
            r.getHtmlUrl().toString(),
            r.getHttpTransportUrl(),
            r.isFork(),
            r.isArchived(),
            r.isPrivate(),
            new ArrayList<>(r.listTopics()),
            r.getPushedAt() == null ? null : r.getPushedAt().toInstant(),
            r.getLanguage()
        );
    }
}
