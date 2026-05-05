package io.devportal.workspace;

import io.devportal.analyze.GitHubUrlParser;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.runtime.k8s.scaffold.CommitResult;
import io.devportal.secret.SecretService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generic commit/push for an asset's workspace. Stages the supplied paths, commits to a named
 * branch, and (when explicitly requested) pushes to {@code origin}. Default is do-not-push.
 *
 * <p>Push always uses the stored PAT from {@link SecretService}. Branches named {@code main} or
 * {@code master} are refused — fixes go on a side branch.
 */
@Service
public class WorkspaceCommitService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCommitService.class);

    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final SecretService secrets;

    public WorkspaceCommitService(AssetRepository assets, WorkspaceService workspace,
                                  SecretService secrets) {
        this.assets = assets;
        this.workspace = workspace;
        this.secrets = secrets;
    }

    public CommitResult commit(String assetId, String branch, String message,
                               List<String> stagePatterns, boolean push)
            throws IOException, GitAPIException {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));
        Path ws = workspace.workspaceFor(assetId);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace for '" + assetId + "' is not cloned yet.");
        }
        if (stagePatterns == null || stagePatterns.isEmpty()) {
            throw new ConflictException("At least one path must be staged.");
        }
        if (branch == null || branch.isBlank()) {
            branch = "devportal/fix-" + assetId + "-" + Instant.now().getEpochSecond();
        }
        if ("main".equals(branch) || "master".equals(branch)) {
            throw new ConflictException("Refusing to commit directly to '" + branch
                + "'. Use a side branch like 'devportal/fix-...'.");
        }
        if (message == null || message.isBlank()) {
            message = "devportal: workspace edit";
        }

        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build();
             Git git = new Git(repo)) {
            String fullRef = "refs/heads/" + branch;
            Ref existing = repo.findRef(fullRef);
            if (existing == null) {
                git.checkout().setName(branch).setCreateBranch(true).call();
            } else {
                git.checkout().setName(branch).call();
            }

            // Two passes: first picks up new files, second picks up tracked-and-modified.
            var addCmd = git.add();
            for (String p : stagePatterns) addCmd.addFilepattern(p);
            addCmd.call();
            var addUpdate = git.add().setUpdate(true);
            for (String p : stagePatterns) addUpdate.addFilepattern(p);
            addUpdate.call();

            var status = git.status().call();
            List<String> files = new ArrayList<>();
            files.addAll(status.getAdded());
            files.addAll(status.getChanged());
            files.addAll(status.getRemoved());
            if (files.isEmpty()) {
                log.info("commit: nothing staged for {}", assetId);
                return new CommitResult(assetId, branch, null, List.of(), false, null,
                    suggestPullRequest(asset, branch));
            }

            PersonIdent ident = new PersonIdent("dev_portal", "noreply@devportal.io",
                java.util.Date.from(Instant.now()), java.util.TimeZone.getDefault());
            var commit = git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call();
            String sha = commit.getId().getName();
            log.info("Committed {} on {} for {} ({} files)", sha, branch, assetId, files.size());

            String pushOut = null;
            boolean pushed = false;
            if (push) {
                pushOut = pushBranch(git, branch);
                pushed = true;
                log.info("Pushed {} -> origin/{} for {}", sha, branch, assetId);
            }
            return new CommitResult(assetId, branch, sha, files, pushed, pushOut,
                suggestPullRequest(asset, branch));
        }
    }

    /** Push an existing local branch (no commit). */
    public CommitResult push(String assetId, String branch) throws IOException, GitAPIException {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));
        Path ws = workspace.workspaceFor(assetId);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace for '" + assetId + "' is not cloned yet.");
        }
        if (branch == null || branch.isBlank()) {
            throw new ConflictException("branch is required");
        }
        if ("main".equals(branch) || "master".equals(branch)) {
            throw new ConflictException("Refusing to push directly to '" + branch + "'.");
        }
        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build();
             Git git = new Git(repo)) {
            if (repo.findRef("refs/heads/" + branch) == null) {
                throw new ConflictException("No local branch '" + branch + "'.");
            }
            String pushOut = pushBranch(git, branch);
            String sha = repo.resolve("refs/heads/" + branch).name();
            return new CommitResult(assetId, branch, sha, List.of(), true, pushOut,
                suggestPullRequest(asset, branch));
        }
    }

    private String pushBranch(Git git, String branch) throws GitAPIException {
        String token = secrets.githubToken();
        if (token == null || token.isBlank()) {
            throw new ConflictException("Push requires a GitHub token; set one in /settings.");
        }
        StringBuilder out = new StringBuilder();
        git.push()
            .setRemote("origin")
            .setRefSpecs(new RefSpec(branch + ":" + branch))
            .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
            .call()
            .forEach(r -> out.append(r.getMessages()).append('\n'));
        return out.toString();
    }

    private static String suggestPullRequest(Asset asset, String branch) {
        String fullName = GitHubUrlParser.fullName(asset.repoUrl());
        if (fullName == null) return null;
        return "https://github.com/" + fullName + "/pull/new/" + branch;
    }
}
