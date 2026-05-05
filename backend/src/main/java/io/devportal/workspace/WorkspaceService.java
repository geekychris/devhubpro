package io.devportal.workspace;

import io.devportal.secret.SecretService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Manages local persistent checkouts of asset repos under
 * {@code ${devportal.workspace.dir}/<assetId>}.
 *
 * <p>Per-asset locks ensure two concurrent builds of the same asset don't race the working tree.
 */
@Service
@EnableConfigurationProperties(WorkspaceProperties.class)
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceProperties props;
    private final SecretService secrets;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public WorkspaceService(WorkspaceProperties props, SecretService secrets) {
        this.props = props;
        this.secrets = secrets;
    }

    public Path rootDir() {
        return Path.of(props.dir());
    }

    public Path workspaceFor(String assetId) {
        return rootDir().resolve(assetId);
    }

    /**
     * Ensure the workspace for {@code assetId} contains a clean checkout of {@code repoUrl} at
     * {@code ref}. Returns the workspace path. Operation is serialized per asset.
     *
     * <p>Refuses to overwrite uncommitted local edits: if the working tree is dirty, throws so the
     * user is forced to commit (Changes tab) or explicitly opt in to discarding via the
     * {@link #syncCheckout(String, String, String, boolean)} overload.
     */
    public Path syncCheckout(String assetId, String repoUrl, String ref) throws GitAPIException, IOException {
        return syncCheckout(assetId, repoUrl, ref, false);
    }

    /**
     * Variant that lets callers opt in to discarding local edits during the {@code reset --hard}.
     * Used by explicit "discard local" actions; never silently from build / analyze paths.
     */
    public Path syncCheckout(String assetId, String repoUrl, String ref, boolean discardLocal)
            throws GitAPIException, IOException {
        Object lock = locks.computeIfAbsent(assetId, k -> new Object());
        synchronized (lock) {
            Path ws = workspaceFor(assetId);
            Files.createDirectories(rootDir());

            if (!Files.isDirectory(ws.resolve(".git"))) {
                cloneFresh(ws, repoUrl, ref);
            } else {
                if (!discardLocal && isDirty(ws)) {
                    throw new io.devportal.asset.error.ConflictException(
                        "Workspace for '" + assetId + "' has uncommitted local changes. "
                        + "Commit them via the Changes tab (or POST /api/assets/" + assetId
                        + "/workspace/commit) before running another build / analyze. "
                        + "To discard, repeat the action with discardLocal=true.");
                }
                fetchAndCheckout(ws, ref);
            }
            return ws;
        }
    }

    private static boolean isDirty(Path ws) throws IOException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build();
             Git g = new Git(repo)) {
            var s = g.status().call();
            return s.hasUncommittedChanges() || !s.getUntracked().isEmpty();
        } catch (GitAPIException e) {
            // If status itself fails, treat as dirty rather than risk silently wiping.
            return true;
        }
    }

    private void cloneFresh(Path ws, String repoUrl, String ref) throws GitAPIException, IOException {
        if (Files.isDirectory(ws)) {
            // dir present but not a git repo — wipe to avoid clone errors
            deleteRecursively(ws);
        }
        Files.createDirectories(ws.getParent());
        log.info("Cloning {} into {} at ref {}", repoUrl, ws, ref);

        var clone = Git.cloneRepository().setURI(repoUrl).setDirectory(ws.toFile());
        if (ref != null && !ref.isBlank()) {
            clone.setBranch(ref);
        }
        applyAuth(clone::setCredentialsProvider);
        try (Git g = clone.call()) {
            log.info("Cloned {} -> HEAD={}", assetIdFromPath(ws), g.getRepository().resolve("HEAD").name());
        }
    }

    private void fetchAndCheckout(Path ws, String ref) throws GitAPIException, IOException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build();
             Git g = new Git(repo)) {
            log.info("Fetching {} (current ref={}, target={})", ws, repo.getBranch(), ref);
            var fetch = g.fetch();
            applyAuth(fetch::setCredentialsProvider);
            fetch.call();

            String target = (ref == null || ref.isBlank()) ? repo.getBranch() : ref;
            // Try local branch, then remote-tracking, then tag/commit-ish.
            Ref local = repo.findRef(target);
            if (local == null) {
                Ref remote = repo.findRef("refs/remotes/origin/" + target);
                if (remote != null) {
                    g.checkout()
                        .setName(target)
                        .setCreateBranch(true)
                        .setStartPoint("origin/" + target)
                        .call();
                } else {
                    g.checkout().setName(target).call(); // tag or sha
                }
            } else {
                g.checkout().setName(target).call();
                // fast-forward to remote tip if it's a branch
                Ref remote = repo.findRef("refs/remotes/origin/" + target);
                if (remote != null) {
                    g.reset()
                        .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                        .setRef("origin/" + target)
                        .call();
                }
            }
        }
    }

    @FunctionalInterface
    private interface AuthSetter {
        void set(UsernamePasswordCredentialsProvider provider);
    }

    private void applyAuth(AuthSetter setter) {
        String token = secrets.githubToken();
        if (token != null && !token.isBlank()) {
            // GitHub PAT-over-HTTPS: token goes in the username field, password is empty.
            // Use "x-access-token" as username (GitHub also accepts the token there).
            setter.set(new UsernamePasswordCredentialsProvider("x-access-token", token));
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                });
        }
    }

    private static String assetIdFromPath(Path ws) {
        return ws.getFileName().toString();
    }
}
