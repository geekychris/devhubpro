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
    static java.util.Optional<String> defaultCommand(Path workspace, String commandName) {
        // Maven — accept pom.xml at root OR exactly one level deep (polyglot monorepos).
        Path mvnRoot = locateBuildRoot(workspace, "pom.xml");
        if (mvnRoot != null) {
            String goal = switch (commandName.toLowerCase()) {
                case "build", "package" -> "package";
                case "compile" -> "compile";
                case "test"    -> "test";
                case "install" -> "install";
                case "verify"  -> "verify";
                case "clean"   -> "clean";
                default -> null;
            };
            if (goal != null) return java.util.Optional.of(withCd(workspace, mvnRoot, "mvn -DskipTests " + goal));
        }
        // Gradle
        Path gradleRoot = locateGradleRoot(workspace);
        if (gradleRoot != null) {
            boolean wrapper = Files.exists(gradleRoot.resolve("gradlew"));
            String exe = wrapper ? "./gradlew" : "gradle";
            String task = switch (commandName.toLowerCase()) {
                case "build", "package" -> "build";
                case "compile" -> "classes";
                case "test"    -> "test";
                case "clean"   -> "clean";
                case "install" -> "publishToMavenLocal";
                default -> null;
            };
            if (task != null) return java.util.Optional.of(withCd(workspace, gradleRoot, exe + " -x test " + task));
        }
        // Cargo (Rust) — accept Cargo.toml at workspace root OR exactly one level deep. The latter
        // covers the common pattern where the Rust workspace lives in a sub-directory alongside
        // other languages (e.g. <repo>/aoee/Cargo.toml in the AOEE repo where aoee-spring is Java).
        // Multiple Cargo.toml files at depth 1 → ambiguous; require a manifest commandLine.
        Path cargoRoot = locateBuildRoot(workspace, "Cargo.toml");
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
            if (cargoCmd != null) return java.util.Optional.of(withCd(workspace, cargoRoot, cargoCmd));
        }
        // npm / pnpm — runs scripts the user defined in package.json; we don't infer goal mapping.
        Path nodeRoot = locateBuildRoot(workspace, "package.json");
        if (nodeRoot != null) {
            String pm = Files.exists(nodeRoot.resolve("pnpm-lock.yaml")) ? "pnpm"
                : Files.exists(nodeRoot.resolve("yarn.lock")) ? "yarn"
                : "npm";
            String script = switch (commandName.toLowerCase()) {
                case "build"   -> pm + " run build";
                case "test"    -> pm + " test";
                case "install" -> pm + " install";
                default        -> null;
            };
            if (script != null) return java.util.Optional.of(withCd(workspace, nodeRoot, script));
        }
        return java.util.Optional.empty();
    }

    /**
     * Human-readable summary of what build markers were found and where, used in the
     * "no build command" error so the user can decide between adding a manifest, passing a
     * commandLine override, or picking a different commandName. Lists both root-level markers
     * and one-level-deep subdir markers so polyglot monorepos surface their components.
     */
    static String describeWorkspace(Path workspace) {
        java.util.LinkedHashMap<String, List<String>> found = new java.util.LinkedHashMap<>();
        for (String marker : List.of("pom.xml", "build.gradle", "build.gradle.kts", "Cargo.toml", "package.json")) {
            List<String> locs = locateMarkerLocations(workspace, marker);
            if (!locs.isEmpty()) found.put(marker, locs);
        }
        if (found.isEmpty()) return "Detected: no recognized build files at workspace root or in first-level subdirs.";
        StringBuilder sb = new StringBuilder("Detected: ");
        boolean first = true;
        for (var e : found.entrySet()) {
            if (!first) sb.append("; ");
            first = false;
            sb.append(e.getKey()).append(" in ").append(String.join(", ", e.getValue()));
        }
        sb.append('.');
        // If multiple subdirs hold the same marker, the auto-fallback bails as ambiguous; tell the user.
        boolean ambiguous = found.values().stream().anyMatch(v -> v.size() > 1);
        if (ambiguous) {
            sb.append(" Multiple components found — pass a commandLine override (e.g. ");
            String example = found.entrySet().stream().filter(e -> e.getValue().size() > 1).findFirst()
                .map(e -> "\"cd " + e.getValue().get(0) + " && <build cmd>\"").orElse("\"cd <subdir> && <build cmd>\"");
            sb.append(example).append(") or add spec.build.commands in devportal.yaml.");
        }
        return sb.toString();
    }

    /**
     * Locate the directory that holds {@code marker} for auto-detection: the workspace root if it
     * has the marker, otherwise exactly one first-level subdir if exactly one has it. Multiple
     * candidates at depth 1 return {@code null} (ambiguous — caller falls through to manifest /
     * commandLine override). Reused for Maven (pom.xml), Cargo (Cargo.toml), npm (package.json).
     */
    static Path locateBuildRoot(Path workspace, String marker) {
        if (Files.exists(workspace.resolve(marker))) return workspace;
        List<Path> candidates = listFirstLevelDirsWith(workspace, marker);
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /** Gradle special-case: either build.gradle OR build.gradle.kts counts as a Gradle project. */
    static Path locateGradleRoot(Path workspace) {
        if (Files.exists(workspace.resolve("build.gradle")) || Files.exists(workspace.resolve("build.gradle.kts"))) {
            return workspace;
        }
        try (var stream = Files.list(workspace)) {
            List<Path> candidates = stream
                .filter(Files::isDirectory)
                .filter(BuildService::isCandidateDir)
                .filter(p -> Files.exists(p.resolve("build.gradle")) || Files.exists(p.resolve("build.gradle.kts")))
                .toList();
            return candidates.size() == 1 ? candidates.get(0) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** All first-level subdirs containing {@code marker}, returned as relative paths for error messages. */
    private static List<String> locateMarkerLocations(Path workspace, String marker) {
        List<String> out = new java.util.ArrayList<>();
        if (Files.exists(workspace.resolve(marker))) out.add("<root>");
        for (Path sub : listFirstLevelDirsWith(workspace, marker)) {
            out.add(workspace.relativize(sub).toString());
        }
        return out;
    }

    private static List<Path> listFirstLevelDirsWith(Path workspace, String marker) {
        if (!Files.isDirectory(workspace)) return List.of();
        try (var stream = Files.list(workspace)) {
            return stream
                .filter(Files::isDirectory)
                .filter(BuildService::isCandidateDir)
                .filter(p -> Files.exists(p.resolve(marker)))
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Skip dotfiles + common ephemeral / vendored output dirs that shouldn't count as components. */
    private static boolean isCandidateDir(Path dir) {
        String n = dir.getFileName().toString();
        if (n.startsWith(".")) return false;
        return !SKIP_SUBDIRS.contains(n);
    }

    private static final java.util.Set<String> SKIP_SUBDIRS = java.util.Set.of(
        "target", "node_modules", "build", "dist", "out", "vendor", ".mvn", "venv", ".venv");

    /** Prefix {@code cmd} with {@code cd <relpath> &&} when the build root is in a subdir. */
    private static String withCd(Path workspace, Path buildRoot, String cmd) {
        return buildRoot.equals(workspace) ? cmd : "cd " + workspace.relativize(buildRoot) + " && " + cmd;
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
