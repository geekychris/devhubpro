package io.devportal.runtime.k8s.scaffold;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.runtime.k8s.K8sService;
import io.devportal.workspace.WorkspaceCommitService;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.eclipse.jgit.api.errors.GitAPIException;
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
    private final FrontendScaffolder frontendScaffolder;
    private final WorkspaceCommitService committer;

    public K8sCommitService(AssetRepository assets, WorkspaceService workspace,
                            K8sService k8s, K8sScaffolder scaffolder,
                            DockerfileScaffolder dockerfileScaffolder,
                            FrontendScaffolder frontendScaffolder,
                            WorkspaceCommitService committer) {
        this.assets = assets;
        this.workspace = workspace;
        this.k8s = k8s;
        this.scaffolder = scaffolder;
        this.dockerfileScaffolder = dockerfileScaffolder;
        this.frontendScaffolder = frontendScaffolder;
        this.committer = committer;
    }

    /**
     * Detect frontend tiers (React / Vite / Next / Vue / Angular) under this asset's workspace.
     * Read-only — surfaces what the {@link FrontendScaffolder} would act on.
     */
    public List<FrontendScaffolder.Tier> detectFrontendTiers(String assetId) throws IOException {
        Asset asset = loadAndEnsure(assetId);
        return frontendScaffolder.detectTiers(workspace.workspaceFor(asset.id()));
    }

    /**
     * Scaffold a single frontend tier. {@code path} is repo-relative (e.g.
     * {@code social-platform/social-frontend}); use {@link #detectFrontendTiers} to discover.
     * Allocates a NodePort from the 30100..30199 range that doesn't collide with existing
     * Service nodePorts in the asset's k8s/ tree.
     */
    public FrontendScaffolder.ScaffoldResult scaffoldFrontend(String assetId, String path)
            throws IOException {
        Asset asset = loadAndEnsure(assetId);
        Path ws = workspace.workspaceFor(asset.id());
        List<FrontendScaffolder.Tier> tiers = frontendScaffolder.detectTiers(ws);
        FrontendScaffolder.Tier tier = tiers.stream()
            .filter(t -> t.relPath().equals(path))
            .findFirst()
            .orElseThrow(() -> new io.devportal.asset.error.NotFoundException(
                "No frontend tier at '" + path + "' in workspace. Detected: "
                + tiers.stream().map(FrontendScaffolder.Tier::relPath).toList()));

        Path k8sDir = ws.resolve("k8s");
        int nodePort = pickFreeFrontendNodePort(k8sDir);
        return frontendScaffolder.scaffold(asset, ws, tier, k8sDir, nodePort);
    }

    /**
     * Pick a NodePort in the 30100..30199 range that isn't already used by another Service in
     * the asset's k8s/ tree. Keeps frontend tiers from clashing with each other or with the
     * asset's other Services. Falls back to 30100 if scan fails.
     */
    private int pickFreeFrontendNodePort(Path k8sDir) {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        if (java.nio.file.Files.isDirectory(k8sDir)) {
            try (var stream = java.nio.file.Files.walk(k8sDir)) {
                stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".yaml") || n.endsWith(".yml");
                    })
                    .forEach(p -> {
                        try {
                            for (String line : java.nio.file.Files.readAllLines(p)) {
                                int idx = line.indexOf("nodePort:");
                                if (idx < 0) continue;
                                String tail = line.substring(idx + "nodePort:".length()).trim();
                                try { used.add(Integer.parseInt(tail.split("\\s+")[0])); }
                                catch (NumberFormatException ignored) {}
                            }
                        } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }
        for (int p = 30100; p <= 30199; p++) if (!used.contains(p)) return p;
        return 30100;
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
        String msg = (message == null || message.isBlank())
            ? "devportal: update k8s manifests with allocated ports" : message;
        return committer.commit(asset.id(), branch, msg, List.of(relPath), push);
    }

    /**
     * Commit whatever lives under {@code k8s/} (e.g., after scaffold) on a branch. Other
     * workspace cruft is intentionally not staged — keeping commits surgical and reviewable.
     */
    public CommitResult commitWorkspace(String assetId, String branch, String message,
                                        boolean push) throws IOException, GitAPIException {
        Asset asset = loadAndEnsure(assetId);
        String msg = (message == null || message.isBlank())
            ? "devportal: scaffold k8s manifests + Dockerfile" : message;
        return committer.commit(asset.id(), branch, msg, List.of("k8s", "Dockerfile"), push);
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
