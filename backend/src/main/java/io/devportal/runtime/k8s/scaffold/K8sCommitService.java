package io.devportal.runtime.k8s.scaffold;

import io.devportal.analyze.GitHubUrlParser;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.runtime.k8s.K8sService;
import io.devportal.secret.SecretService;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes rendered or scaffolded k8s manifests into the asset's workspace, commits on a named
 * branch via JGit, and (when explicitly requested) pushes the branch back to GitHub.
 *
 * <p>Push uses the stored PAT from {@link SecretService}. The default is to <strong>not</strong>
 * push — the user reviews the local commit first.
 */
@Service
public class K8sCommitService {

    private static final Logger log = LoggerFactory.getLogger(K8sCommitService.class);

    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final K8sService k8s;
    private final K8sScaffolder scaffolder;
    private final DockerfileScaffolder dockerfileScaffolder;
    private final SecretService secrets;

    public K8sCommitService(AssetRepository assets, WorkspaceService workspace,
                            K8sService k8s, K8sScaffolder scaffolder,
                            DockerfileScaffolder dockerfileScaffolder, SecretService secrets) {
        this.assets = assets;
        this.workspace = workspace;
        this.k8s = k8s;
        this.scaffolder = scaffolder;
        this.dockerfileScaffolder = dockerfileScaffolder;
        this.secrets = secrets;
    }

    /**
     * Generate k8s manifests for an asset that doesn't have them yet. Writes into the workspace
     * (does not commit). Returns the relative paths of files written.
     */
    public List<String> scaffold(String assetId) throws IOException {
        Asset asset = loadAndEnsure(assetId);
        return scaffolder.scaffold(asset, workspace.workspaceFor(asset.id()));
    }

    /** Scaffold a Dockerfile + k8s manifests in one call. Idempotent for already-present files. */
    public ScaffoldFullResult scaffoldFull(String assetId) throws IOException {
        Asset asset = loadAndEnsure(assetId);
        Path ws = workspace.workspaceFor(asset.id());
        DockerfileScaffolder.Result df = dockerfileScaffolder.scaffold(asset, ws);
        // Re-run k8s scaffold idempotently; deployment.yaml/service.yaml just get rewritten with the
        // current allocations.
        List<String> k8sFiles = scaffolder.scaffold(asset, ws);
        return new ScaffoldFullResult(asset.id(), df.wrote(), df.message(), k8sFiles);
    }

    public record ScaffoldFullResult(String assetId, boolean dockerfileWritten,
                                     String dockerfileMessage, List<String> k8sFiles) {}

    /**
     * Apply rendered (port-patched) manifests as edits to the original repo files: copies the
     * rendered output over the source files in the workspace, commits on {@code branch}, and
     * optionally pushes.
     */
    public CommitResult commitRender(String assetId, String branch, String message,
                                     boolean push) throws IOException, GitAPIException {
        Asset asset = loadAndEnsure(assetId);
        Path ws = workspace.workspaceFor(asset.id());
        Path source = k8s.resolveK8sPath(assetId);                          // throws if no k8s/
        Path rendered = k8s.renderForApply(asset, source);

        copyTreeOver(rendered, source);
        // Stage only the k8s manifest path — never `target/` or other build cruft.
        String relPath = ws.relativize(source).toString();
        return commitChanges(asset, ws, branch, message, push, List.of(relPath));
    }

    /**
     * Commit whatever lives under {@code k8s/} (e.g., after scaffold) on a branch. Other
     * workspace cruft is intentionally not staged — keeping commits surgical and reviewable.
     */
    public CommitResult commitWorkspace(String assetId, String branch, String message,
                                        boolean push) throws IOException, GitAPIException {
        Asset asset = loadAndEnsure(assetId);
        // Stage k8s/ + Dockerfile (and Dockerfile.X variants).
        return commitChanges(asset, workspace.workspaceFor(asset.id()), branch, message, push,
            List.of("k8s", "Dockerfile"));
    }

    private CommitResult commitChanges(Asset asset, Path ws, String branch, String message,
                                       boolean push, List<String> stagePatterns)
            throws IOException, GitAPIException {
        if (branch == null || branch.isBlank()) branch = "devportal/k8s-" + Instant.now().getEpochSecond();
        if (message == null || message.isBlank()) {
            message = "devportal: update k8s manifests with allocated ports";
        }
        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build();
             Git git = new Git(repo)) {
            // Branch off current HEAD if it doesn't exist.
            String fullRef = "refs/heads/" + branch;
            Ref existing = repo.findRef(fullRef);
            if (existing == null) {
                git.checkout().setName(branch).setCreateBranch(true).call();
            } else {
                git.checkout().setName(branch).call();
            }

            // Stage only the requested paths. Two passes: one for new files, one for tracked-and-modified.
            var addCmd = git.add();
            for (String p : stagePatterns) addCmd.addFilepattern(p);
            addCmd.call();
            var addUpdate = git.add().setUpdate(true);
            for (String p : stagePatterns) addUpdate.addFilepattern(p);
            addUpdate.call();
            var status = git.status().call();
            // Only count what's actually staged; ignore untracked / unstaged-modified workspace cruft.
            List<String> files = new ArrayList<>();
            files.addAll(status.getAdded());
            files.addAll(status.getChanged());
            files.addAll(status.getRemoved());
            if (files.isEmpty()) {
                log.info("commitChanges: nothing to commit for {}", asset.id());
                return new CommitResult(asset.id(), branch, null, List.of(), false, null,
                    suggestPullRequest(asset, branch));
            }

            PersonIdent ident = new PersonIdent("dev_portal", "noreply@devportal.io",
                java.util.Date.from(Instant.now()), java.util.TimeZone.getDefault());
            var commit = git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call();
            String sha = commit.getId().getName();
            log.info("Committed {} on {} for {} ({} files)", sha, branch, asset.id(), files.size());

            String pushOut = null;
            boolean pushed = false;
            if (push) {
                String token = secrets.githubToken();
                if (token == null || token.isBlank()) {
                    throw new ConflictException("Push requires a GitHub token; set one in /settings.");
                }
                var pushCmd = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(branch + ":" + branch))
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token));
                StringBuilder out = new StringBuilder();
                pushCmd.call().forEach(r -> out.append(r.getMessages()).append('\n'));
                pushOut = out.toString();
                pushed = true;
                log.info("Pushed {} -> origin/{} for {}", sha, branch, asset.id());
            }
            return new CommitResult(asset.id(), branch, sha, files, pushed, pushOut,
                suggestPullRequest(asset, branch));
        }
    }

    private static String suggestPullRequest(Asset asset, String branch) {
        String fullName = GitHubUrlParser.fullName(asset.repoUrl());
        if (fullName == null) return null;
        return "https://github.com/" + fullName + "/pull/new/" + branch;
    }

    private static void copyTreeOver(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Files.createDirectories(target.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Files.copy(file, target.resolve(rel.toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Asset loadAndEnsure(String assetId) throws IOException {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));
        Path ws = workspace.workspaceFor(asset.id());
        if (!Files.isDirectory(ws.resolve(".git"))) {
            try {
                workspace.syncCheckout(asset.id(), asset.repoUrl(), asset.repoDefaultBranch());
            } catch (GitAPIException e) {
                throw new ConflictException("Could not clone " + asset.repoUrl() + ": " + e.getMessage());
            }
        }
        return asset;
    }
}
