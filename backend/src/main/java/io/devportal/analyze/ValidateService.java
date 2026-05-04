package io.devportal.analyze;

import io.devportal.analyze.dto.ValidationResult;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.github.GitHubClient;
import io.devportal.github.GitHubRepoSummary;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Validates the GitHub link of a registered asset and surfaces what's present in the workspace. */
@Service
public class ValidateService {

    private static final Logger log = LoggerFactory.getLogger(ValidateService.class);

    private final AssetRepository assets;
    private final GitHubClient github;
    private final WorkspaceService workspace;

    public ValidateService(AssetRepository assets, GitHubClient github, WorkspaceService workspace) {
        this.assets = assets;
        this.github = github;
        this.workspace = workspace;
    }

    public ValidationResult validate(String assetId) {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));

        String fullName = GitHubUrlParser.fullName(asset.repoUrl());
        boolean reachable = false;
        boolean branchMatches = false;
        String remoteBranch = null;
        String error = null;

        if (fullName == null) {
            error = "repoUrl is not a recognizable GitHub URL";
        } else {
            try {
                GitHubRepoSummary summary = github.getRepo(fullName);
                reachable = true;
                remoteBranch = summary.defaultBranch();
                branchMatches = remoteBranch != null && remoteBranch.equals(asset.repoDefaultBranch());
            } catch (IOException e) {
                error = e.getMessage();
                log.warn("GitHub validate failed for {}: {}", fullName, error);
            }
        }

        Path ws = workspace.workspaceFor(assetId);
        boolean hasManifest = Files.exists(ws.resolve("devportal.yaml"));
        boolean hasPom = Files.exists(ws.resolve("pom.xml"));
        boolean hasGradle = Files.exists(ws.resolve("build.gradle")) || Files.exists(ws.resolve("build.gradle.kts"));
        boolean hasPackageJson = Files.exists(ws.resolve("package.json"));
        boolean hasDockerfile = Files.exists(ws.resolve("Dockerfile"));
        boolean hasK8sDir = Files.isDirectory(ws.resolve("k8s")) || Files.isDirectory(ws.resolve("deploy"));

        return new ValidationResult(
            asset.repoUrl(), fullName, reachable, branchMatches, remoteBranch,
            hasManifest, hasPom, hasGradle, hasPackageJson, hasDockerfile, hasK8sDir,
            error
        );
    }
}
