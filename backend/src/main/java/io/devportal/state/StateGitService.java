package io.devportal.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Thin JGit wrapper that init/commits the state directory as a local repo. */
@Service
public class StateGitService {

    private static final Logger log = LoggerFactory.getLogger(StateGitService.class);

    /** Ensure the dir is a git repo; init if not. Returns true if we just initialized. */
    public boolean ensureRepo(Path dir) throws IOException, GitAPIException {
        Files.createDirectories(dir);
        Path gitDir = dir.resolve(".git");
        if (Files.isDirectory(gitDir)) return false;
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            log.info("Initialized state repo at {}", dir);
        }
        return true;
    }

    /** Commit all current changes under {@code dir}. Returns the commit SHA, or null if nothing to commit. */
    public String commitAll(Path dir, String message) throws IOException, GitAPIException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(dir.resolve(".git").toFile()).build();
             Git git = new Git(repo)) {
            git.add().addFilepattern(".").call();
            var status = git.status().call();
            if (status.isClean()) {
                log.info("No changes to commit in {}", dir);
                return null;
            }
            PersonIdent ident = new PersonIdent("dev_portal", "noreply@devportal.io",
                java.util.Date.from(Instant.now()), java.util.TimeZone.getDefault());
            var commit = git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call();
            String sha = commit.getId().getName();
            log.info("Committed {} ({}) in {}", sha, message, dir);
            return sha;
        }
    }
}
