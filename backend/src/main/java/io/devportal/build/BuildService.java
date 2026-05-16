package io.devportal.build;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.build.dto.BuildView;
import io.devportal.build.dto.KickBuildRequest;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class BuildService {

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);

    private final AssetRepository assets;
    private final BuildRepository builds;
    private final BuildRunner runner;
    private final DependencyWalker walker;
    private final WorkspaceService workspace;
    private final ManifestParser manifestParser;
    private final ExecutorService deepBuildExecutor;

    public BuildService(AssetRepository assets, BuildRepository builds, BuildRunner runner,
                        DependencyWalker walker, WorkspaceService workspace,
                        ManifestParser manifestParser,
                        @Qualifier("buildExecutor") java.util.concurrent.Executor buildExec) {
        this.assets = assets;
        this.builds = builds;
        this.runner = runner;
        this.walker = walker;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        // For deep mode we need a single thread that walks the dep chain serially.
        // We dispatch to this dedicated executor so the request returns immediately.
        this.deepBuildExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "devportal-deep-build");
            t.setDaemon(true);
            return t;
        });
    }

    public BuildView kick(String assetId, KickBuildRequest req) throws IOException, GitAPIException {
        Asset root = assets.findById(assetId)
            .orElseThrow(() -> new NotFoundException("Asset '" + assetId + "' not found"));
        BuildMode mode = req.mode() == null ? BuildMode.SHALLOW : BuildMode.fromDb(req.mode());
        String commandName = req.commandName() == null ? "build" : req.commandName();

        if (mode == BuildMode.SHALLOW) {
            Prepared p = prepare(root, commandName, req.commandLine(), req.ref(), null, BuildMode.SHALLOW);
            runner.runAsync(p.build.id(), p.workspace, p.commandLine, p.gitSha, p.logPath);
            return BuildView.of(p.build);
        }

        // Deep: the root build represents the user-requested asset itself (mode=DEEP, parent=null)
        // so it appears immediately in that asset's build list. Producers run as siblings (parent=root)
        // in topo order; the root runs last, after all producers succeed. Any producer failure
        // aborts the chain and marks root as FAILED.
        List<Asset> ordered = walker.buildOrder(assetId);
        Asset rootAsset = ordered.get(ordered.size() - 1);  // walker returns leaf-first, root-last
        Prepared rootPrep = prepare(rootAsset, commandName, req.commandLine(), req.ref(), null, BuildMode.DEEP);
        List<Asset> producers = ordered.subList(0, ordered.size() - 1);
        deepBuildExecutor.submit(() -> runDeepChain(rootPrep, producers, commandName, req.ref()));
        return BuildView.of(rootPrep.build);
    }

    /** Runs producers serially as children of the root build, then runs the root itself. */
    private void runDeepChain(Prepared root, List<Asset> producers, String commandName, String ref) {
        try {
            for (Asset producer : producers) {
                Prepared child;
                try {
                    child = prepare(producer, commandName, null, ref, root.build.id(), BuildMode.SHALLOW);
                } catch (Exception e) {
                    log.error("Deep chain: failed to queue producer {} for root build {}",
                        producer.id(), root.build.id(), e);
                    failRoot(root, "Failed to queue producer '" + producer.id() + "': " + e.getMessage());
                    return;
                }
                BuildStatus s = runner.run(child.build.id(), child.workspace,
                    child.commandLine, child.gitSha, child.logPath);
                if (s != BuildStatus.SUCCEEDED) {
                    log.warn("Deep chain aborted: producer {} build {} -> {}",
                        producer.id(), child.build.id(), s);
                    failRoot(root, "Producer '" + producer.id() + "' build " + child.build.id()
                        + " " + s.dbValue() + ". See its log for details. Chain aborted "
                        + "without running remaining producers or root.");
                    return;
                }
            }
            runner.run(root.build.id(), root.workspace, root.commandLine, root.gitSha, root.logPath);
        } catch (Exception e) {
            log.error("Deep chain crashed for root build {}", root.build.id(), e);
            failRoot(root, "Chain crashed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void failRoot(Prepared root, String reason) {
        try {
            Files.createDirectories(root.logPath.getParent());
            Files.writeString(root.logPath,
                "# Deep build aborted before this asset's command ran.\n# " + reason + "\n");
        } catch (IOException ignored) {}
        builds.markFinished(root.build.id(), BuildStatus.FAILED, null);
    }

    /** Bundle of resolved parameters for a queued build, ready to hand to BuildRunner. */
    private record Prepared(Build build, Path workspace, String commandLine, String gitSha, Path logPath) {}

    private Prepared prepare(Asset asset, String commandName, String commandLineOverride,
                             String refOverride, Long parentBuildId, BuildMode rowMode)
                             throws IOException, GitAPIException {
        // Default ref priority: explicit override > current workspace branch > asset default branch.
        // Picking up the current branch makes side-branch fixes (devportal/fix-...) get used
        // automatically without the user re-typing the branch on every build.
        String ref;
        if (refOverride != null && !refOverride.isBlank()) {
            ref = refOverride;
        } else {
            ref = currentWorkspaceBranch(workspace.workspaceFor(asset.id()))
                .orElse(asset.repoDefaultBranch());
        }
        Path ws = workspace.syncCheckout(asset.id(), asset.repoUrl(), ref);
        String gitSha = readHeadSha(ws);

        String commandLine = commandLineOverride;
        if (commandLine == null || commandLine.isBlank()) {
            commandLine = lookupCommand(ws, commandName)
                .or(() -> defaultCommand(ws, commandName))
                .orElseThrow(() -> new IllegalStateException(
                    "No build command '" + commandName + "' for " + asset.id() + ". "
                    + describeWorkspace(ws)
                    + " Either add a devportal.yaml with spec.build.commands."
                    + commandName + ", or pass a commandLine override."));
        }

        Path logPath = Path.of(System.getProperty("user.home"), ".devportal", "logs",
            asset.id() + "-" + System.currentTimeMillis() + ".log");

        long buildId = builds.insertQueued(asset.id(), parentBuildId,
            rowMode, commandName, commandLine, ref, ws.toString(), logPath.toString());

        return new Prepared(builds.findById(buildId).orElseThrow(), ws, commandLine, gitSha, logPath);
    }

    public List<BuildView> listFor(String assetId, int limit) {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        return builds.findByAsset(assetId, limit).stream().map(BuildView::of).toList();
    }

    public BuildView get(long id) {
        return BuildView.of(builds.findById(id)
            .orElseThrow(() -> new NotFoundException("Build " + id + " not found")));
    }

    public List<BuildView> recent(int limit) {
        return builds.findRecent(limit).stream().map(BuildView::of).toList();
    }

    /** Delete a single build record + its log file. Refuses if the build is still running. */
    public void delete(long id) {
        Build b = builds.findById(id).orElseThrow(
            () -> new NotFoundException("Build " + id + " not found"));
        if (b.status() == BuildStatus.RUNNING || b.status() == BuildStatus.QUEUED) {
            throw new io.devportal.asset.error.ConflictException(
                "Build " + id + " is still " + b.status().dbValue() + "; cannot delete.");
        }
        try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(b.logPath())); }
        catch (java.io.IOException ignored) {}
        builds.deleteById(id);
        log.info("Deleted build {} ({})", id, b.assetId());
    }

    /** Returns every build in the chain that contains this build, in chronological order. */
    public List<BuildView> chain(long buildId) {
        if (builds.findById(buildId).isEmpty()) {
            throw new NotFoundException("Build " + buildId + " not found");
        }
        return builds.findChain(buildId).stream().map(BuildView::of).toList();
    }

    /**
     * Rich progress view for a chain: parent + children with parsed labels, durations, tail logs,
     * and aggregated counts. The single round-trip is what makes live UI polling and REST/MCP
     * diagnosis cheap.
     */
    public io.devportal.build.dto.BuildProgress progress(long buildId) {
        Build base = builds.findById(buildId).orElseThrow(
            () -> new NotFoundException("Build " + buildId + " not found"));
        // Find the parent (root) of the chain by walking up parent_build_id, then collect children.
        Build parent = base;
        java.util.HashSet<Long> guard = new java.util.HashSet<>();
        while (parent.parentBuildId() != null && guard.add(parent.id())) {
            Build next = builds.findById(parent.parentBuildId()).orElse(null);
            if (next == null) break;
            parent = next;
        }
        List<Build> chain = builds.findChain(parent.id());

        // Children = all chain entries except the parent.
        List<io.devportal.build.dto.BuildProgress.ChildProgress> children = new java.util.ArrayList<>();
        int succeeded = 0, failed = 0, running = 0, queued = 0, cancelled = 0;
        for (Build c : chain) {
            if (c.id() == parent.id()) continue;
            switch (c.status()) {
                case SUCCEEDED -> succeeded++;
                case FAILED -> failed++;
                case RUNNING -> running++;
                case QUEUED -> queued++;
                case CANCELLED -> cancelled++;
            }
            children.add(toChildProgress(c));
        }

        // Parse the parent log for image-build chain summary text + skipped count, if present.
        String summaryText = null;
        int skipped = 0;
        try {
            java.nio.file.Path lp = java.nio.file.Path.of(parent.logPath());
            if (java.nio.file.Files.exists(lp)) {
                summaryText = java.nio.file.Files.readString(lp);
                for (String line : summaryText.split("\n")) {
                    if (line.startsWith("SKIPPED ")) skipped++;
                }
            }
        } catch (java.io.IOException ignored) {}

        Long durationMs = (parent.startedAt() == null || parent.finishedAt() == null) ? null
            : (parent.finishedAt().toEpochMilli() - parent.startedAt().toEpochMilli());

        return new io.devportal.build.dto.BuildProgress(
            parent.id(), parent.assetId(), parent.commandName(),
            parent.status().dbValue(), parent.mode().dbValue(),
            parent.startedAt(), parent.finishedAt(), durationMs,
            new io.devportal.build.dto.BuildProgress.Summary(
                children.size() + skipped, succeeded, failed, running, queued, cancelled, skipped),
            summaryText, children
        );
    }

    private io.devportal.build.dto.BuildProgress.ChildProgress toChildProgress(Build c) {
        Long durationMs = (c.startedAt() == null || c.finishedAt() == null) ? null
            : (c.finishedAt().toEpochMilli() - c.startedAt().toEpochMilli());

        // Parse a friendly label out of the commandLine. For docker build the tag after `-t` is
        // the most useful; for everything else, fall back to commandName.
        String label = c.commandName();
        if ("docker-build".equals(c.commandName()) && c.commandLine() != null) {
            int idx = c.commandLine().indexOf("-t ");
            if (idx >= 0) {
                String rest = c.commandLine().substring(idx + 3).trim();
                int sp = rest.indexOf(' ');
                label = sp > 0 ? rest.substring(0, sp).replace("'", "").replace("\"", "") : rest;
            }
        }

        // Tail the last few log lines so the caller can see live progress (running) or the
        // failure point (failed) without a separate request.
        List<String> tail = List.of();
        String errorHint = null;
        try {
            java.nio.file.Path lp = java.nio.file.Path.of(c.logPath());
            if (java.nio.file.Files.exists(lp)) {
                List<String> all = java.nio.file.Files.readAllLines(lp);
                int from = Math.max(0, all.size() - 12);
                tail = new java.util.ArrayList<>(all.subList(from, all.size()));
                if (c.status() == BuildStatus.FAILED) {
                    // First line that looks like an error — surface it as a hint.
                    for (String line : all) {
                        String l = line.toLowerCase();
                        if (l.contains("error") || l.contains("exception") || l.contains("failed")) {
                            errorHint = line.length() > 240 ? line.substring(0, 240) + "…" : line;
                            break;
                        }
                    }
                }
            }
        } catch (java.io.IOException ignored) {}

        return new io.devportal.build.dto.BuildProgress.ChildProgress(
            c.id(), c.assetId(), c.commandName(), c.status().dbValue(),
            c.exitCode(), label, c.startedAt(), c.finishedAt(), durationMs,
            tail, errorHint, c.logPath()
        );
    }

    /** Returns the log file's contents (or empty if not yet flushed). */
    public String readLog(long id) throws IOException {
        Build b = builds.findById(id)
            .orElseThrow(() -> new NotFoundException("Build " + id + " not found"));
        Path p = Path.of(b.logPath());
        if (!Files.exists(p)) return "";
        return Files.readString(p);
    }

    private java.util.Optional<String> lookupCommand(Path workspace, String commandName) {
        Path manifest = workspace.resolve("devportal.yaml");
        if (!Files.exists(manifest)) return java.util.Optional.empty();
        try {
            String yaml = Files.readString(manifest);
            ManifestParseResult parsed = manifestParser.parse(yaml);
            if (parsed.manifest() == null || parsed.manifest().spec() == null
                || parsed.manifest().spec().build() == null) {
                return java.util.Optional.empty();
            }
            Manifest.Build build = parsed.manifest().spec().build();
            Map<String, String> commands = build.commands();
            if (commands == null) return java.util.Optional.empty();
            return java.util.Optional.ofNullable(commands.get(commandName));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * When no manifest defines the command, derive one from the detected build tool. Only known
     * goals are mapped; arbitrary names still fail so users don't accidentally trigger something
     * unexpected.
     */
    private java.util.Optional<String> defaultCommand(Path workspace, String commandName) {
        // Maven
        if (Files.exists(workspace.resolve("pom.xml"))) {
            String goal = switch (commandName.toLowerCase()) {
                case "build", "package" -> "package";
                case "compile" -> "compile";
                case "test"    -> "test";
                case "install" -> "install";
                case "verify"  -> "verify";
                case "clean"   -> "clean";
                default -> null;
            };
            if (goal != null) return java.util.Optional.of("mvn -DskipTests " + goal);
        }
        // Gradle
        boolean gradleKts = Files.exists(workspace.resolve("build.gradle.kts"));
        boolean gradle = Files.exists(workspace.resolve("build.gradle"));
        if (gradleKts || gradle) {
            boolean wrapper = Files.exists(workspace.resolve("gradlew"));
            String exe = wrapper ? "./gradlew" : "gradle";
            String task = switch (commandName.toLowerCase()) {
                case "build", "package" -> "build";
                case "compile" -> "classes";
                case "test"    -> "test";
                case "clean"   -> "clean";
                case "install" -> "publishToMavenLocal";
                default -> null;
            };
            if (task != null) return java.util.Optional.of(exe + " -x test " + task);
        }
        // Cargo (Rust) — accept Cargo.toml at workspace root OR exactly one level deep. The latter
        // covers the common pattern where the Rust workspace lives in a sub-directory alongside
        // other languages (e.g. <repo>/aoee/Cargo.toml in the AOEE repo where aoee-spring is Java).
        // Multiple Cargo.toml files at depth 1 → ambiguous; require a manifest commandLine.
        Path cargoRoot = locateCargoRoot(workspace);
        if (cargoRoot != null) {
            // "install" intentionally maps to a release build rather than `cargo install`, since
            // `cargo install --path .` only works for crates with a [[bin]] section, and most
            // intra-portal Rust producers are libraries consumed via local path/registry.
            String cargoCmd = switch (commandName.toLowerCase()) {
                case "build", "package" -> "cargo build --release";
                case "compile", "check" -> "cargo check";
                case "test"             -> "cargo test";
                case "clean"            -> "cargo clean";
                case "install"          -> "cargo build --release";
                case "verify"           -> "cargo test";
                default                 -> null;
            };
            if (cargoCmd != null) {
                String prefix = cargoRoot.equals(workspace) ? ""
                    : "cd " + workspace.relativize(cargoRoot) + " && ";
                return java.util.Optional.of(prefix + cargoCmd);
            }
        }
        // npm / pnpm — runs scripts the user defined in package.json; we don't infer goal mapping.
        if (Files.exists(workspace.resolve("package.json"))) {
            String pm = Files.exists(workspace.resolve("pnpm-lock.yaml")) ? "pnpm"
                : Files.exists(workspace.resolve("yarn.lock")) ? "yarn"
                : "npm";
            // Conventional script names that map straight through.
            return switch (commandName.toLowerCase()) {
                case "build"   -> java.util.Optional.of(pm + " run build");
                case "test"    -> java.util.Optional.of(pm + " test");
                case "install" -> java.util.Optional.of(pm + " install");
                default        -> java.util.Optional.empty();
            };
        }
        return java.util.Optional.empty();
    }

    private static String describeWorkspace(Path workspace) {
        StringBuilder sb = new StringBuilder("Detected: ");
        boolean any = false;
        if (Files.exists(workspace.resolve("pom.xml")))           { sb.append("pom.xml "); any = true; }
        if (Files.exists(workspace.resolve("build.gradle")))      { sb.append("build.gradle "); any = true; }
        if (Files.exists(workspace.resolve("build.gradle.kts")))  { sb.append("build.gradle.kts "); any = true; }
        if (Files.exists(workspace.resolve("Cargo.toml")))        { sb.append("Cargo.toml "); any = true; }
        if (Files.exists(workspace.resolve("package.json")))      { sb.append("package.json "); any = true; }
        if (!any) sb.append("no recognized build files");
        return sb.toString().trim() + ".";
    }

    /**
     * Find the Cargo workspace root for an asset: either the workspace itself, or exactly one
     * subdir of it (covering the common "Java backend with a Rust subdir" pattern). Returns
     * {@code null} when there's no Cargo.toml or there are multiple — the latter is ambiguous
     * and the user must supply an explicit commandLine.
     */
    private static Path locateCargoRoot(Path workspace) {
        if (Files.exists(workspace.resolve("Cargo.toml"))) return workspace;
        if (!Files.isDirectory(workspace)) return null;
        try (var stream = Files.list(workspace)) {
            List<Path> candidates = stream
                .filter(Files::isDirectory)
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return !n.startsWith(".") && !"target".equals(n) && !"node_modules".equals(n);
                })
                .filter(p -> Files.exists(p.resolve("Cargo.toml")))
                .toList();
            return candidates.size() == 1 ? candidates.get(0) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String readHeadSha(Path ws) {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build()) {
            return repo.resolve("HEAD") == null ? null : repo.resolve("HEAD").name();
        } catch (IOException e) {
            return null;
        }
    }

    /** Current branch of the on-disk workspace, or empty if not cloned / detached HEAD. */
    private static java.util.Optional<String> currentWorkspaceBranch(Path ws) {
        if (!Files.isDirectory(ws.resolve(".git"))) return java.util.Optional.empty();
        try (Repository repo = new FileRepositoryBuilder().setGitDir(ws.resolve(".git").toFile()).build()) {
            String branch = repo.getBranch();
            if (branch == null || branch.isBlank()) return java.util.Optional.empty();
            // repo.getBranch() returns the SHA in detached-HEAD state; reject that.
            if (branch.matches("[0-9a-f]{40}")) return java.util.Optional.empty();
            return java.util.Optional.of(branch);
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }
}
