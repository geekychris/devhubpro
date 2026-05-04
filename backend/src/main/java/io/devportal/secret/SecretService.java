package io.devportal.secret;

import io.devportal.github.GitHubProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * File-backed secret store at {@code ~/.devportal/secrets/<key>} with mode 0600.
 *
 * <p>For GitHub, lookup order is:
 * <ol>
 *   <li>file at secrets dir</li>
 *   <li>{@code GITHUB_TOKEN} env (via {@link GitHubProperties#token()})</li>
 *   <li>nothing — anonymous access</li>
 * </ol>
 *
 * Never returns raw tokens over public surfaces; use {@link #preview(String)} for display.
 */
@Service
public class SecretService {

    private static final Logger log = LoggerFactory.getLogger(SecretService.class);

    public enum Source { FILE, ENV, NONE }

    private final Path secretsDir;
    private final GitHubProperties github;

    public SecretService(GitHubProperties github) {
        this.github = github;
        this.secretsDir = Path.of(System.getProperty("user.home"), ".devportal", "secrets");
    }

    /** Returns the active GitHub token, or null if none. */
    public String githubToken() {
        return resolvedToken().value();
    }

    public TokenInfo githubTokenInfo() {
        Resolved r = resolvedToken();
        return new TokenInfo(r.value() != null && !r.value().isBlank(), preview(r.value()), r.source());
    }

    public void setGithubToken(String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token must not be blank");
        }
        write("github-token", token);
        log.info("Stored GitHub token (preview={})", preview(token));
    }

    public void clearGithubToken() throws IOException {
        Path p = secretsDir.resolve("github-token");
        if (Files.deleteIfExists(p)) {
            log.info("Cleared file-stored GitHub token");
        }
    }

    private Resolved resolvedToken() {
        Path p = secretsDir.resolve("github-token");
        if (Files.exists(p)) {
            try {
                String t = Files.readString(p).trim();
                if (!t.isEmpty()) return new Resolved(t, Source.FILE);
            } catch (IOException e) {
                log.warn("Could not read {}: {}", p, e.getMessage());
            }
        }
        if (github.token() != null && !github.token().isBlank()) {
            return new Resolved(github.token(), Source.ENV);
        }
        return new Resolved(null, Source.NONE);
    }

    private void write(String key, String value) throws IOException {
        Files.createDirectories(secretsDir);
        Path p = secretsDir.resolve(key);
        Files.writeString(p, value);
        // Best-effort POSIX 0600 (no-op on Windows).
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException ignored) {}
    }

    public static String preview(String token) {
        if (token == null || token.isBlank()) return null;
        if (token.length() <= 8) return "••••";
        String prefix = token.substring(0, Math.min(4, token.length()));
        String suffix = token.substring(Math.max(0, token.length() - 4));
        return prefix + "••••" + suffix;
    }

    public record TokenInfo(boolean hasToken, String preview, Source source) {}

    private record Resolved(String value, Source source) {}
}
