package io.devportal.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.devportal.backup.dto.BackupSummary;
import io.devportal.state.StateGitService;
import io.devportal.state.StateService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Snapshot + restore of portal state. Each backup is a timestamped folder under
 * {@code devportal.backup.dir} that bundles:
 *
 * <ul>
 *   <li>{@code state/} — Postgres-of-record exported as YAML by {@link StateService}.</li>
 *   <li>{@code secrets/} — copies of {@code ~/.devportal/secrets/} (opt-in; contains tokens).</li>
 *   <li>{@code logs/} — copies of {@code ~/.devportal/logs/} (opt-in; can be large).</li>
 *   <li>{@code manifest.json} — describes the bundle (timestamp, contents, hostname, schema version).</li>
 * </ul>
 *
 * <p>The backup root can live inside any git repo — including the dev_portal source repo
 * itself if you want code + state in one repo. If a git working tree wraps the dir, each
 * new backup is auto-committed (controlled by {@code devportal.backup.auto-commit}).
 */
@Service
@EnableConfigurationProperties(BackupProperties.class)
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final BackupProperties props;
    private final StateService state;
    private final StateGitService git;
    private final ObjectMapper json;

    public BackupService(BackupProperties props, StateService state, StateGitService git) {
        this.props = props;
        this.state = state;
        this.git = git;
        this.json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path rootDir() { return Path.of(props.getDir()); }

    public record CreateOptions(
        boolean includeSecrets,
        boolean includeLogs,
        Path dirOverride,
        boolean commit,
        boolean push,
        String message
    ) {}

    /**
     * Build a new backup. Returns a summary describing what was written. If the target
     * directory is inside a git working tree and {@code commit=true} (or auto-commit is
     * enabled), the new folder is added + committed.
     */
    public BackupSummary create(CreateOptions opts) throws IOException, GitAPIException {
        Path root = opts.dirOverride() != null ? opts.dirOverride() : rootDir();
        Files.createDirectories(root);

        String stamp = STAMP.format(Instant.now());
        Path dest = root.resolve(stamp);
        Files.createDirectories(dest);

        // 1) State — let StateService write to its configured location, then copy in.
        Path stateExport = state.export();
        Path stateOut = dest.resolve("state");
        copyTree(stateExport, stateOut);

        // 2) Secrets (opt-in).
        Path secretsCount = null;
        if (opts.includeSecrets()) {
            Path secretsSrc = Path.of(System.getProperty("user.home"), ".devportal", "secrets");
            if (Files.isDirectory(secretsSrc)) {
                secretsCount = dest.resolve("secrets");
                copyTree(secretsSrc, secretsCount);
                lockDownPermissions(secretsCount);
            }
        }

        // 3) Logs (opt-in).
        Path logsCount = null;
        if (opts.includeLogs()) {
            Path logsSrc = Path.of(System.getProperty("user.home"), ".devportal", "logs");
            if (Files.isDirectory(logsSrc)) {
                logsCount = dest.resolve("logs");
                copyTree(logsSrc, logsCount);
            }
        }

        // 4) Manifest describing the bundle.
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("hostname", hostname());
        manifest.put("includesState", true);
        manifest.put("includesSecrets", secretsCount != null);
        manifest.put("includesLogs", logsCount != null);
        manifest.put("assetCount", countYaml(stateOut.resolve("assets")));
        manifest.put("logCount", logsCount == null ? 0 : countFiles(logsCount));
        manifest.put("note", "Generated by io.devportal.backup.BackupService.");
        json.writeValue(dest.resolve("manifest.json").toFile(), manifest);

        // 5) Optional commit if the root dir is in (or wraps) a git working tree.
        String commitSha = null;
        boolean shouldCommit = opts.commit() || (props.isAutoCommit() && isInsideWorkTree(root));
        if (shouldCommit) {
            Path repoRoot = locateGitWorkTree(root);
            if (repoRoot == null) {
                git.ensureRepo(root);
                repoRoot = root;
            }
            String message = opts.message() != null && !opts.message().isBlank()
                ? opts.message()
                : "backup " + stamp;
            commitSha = git.commitAll(repoRoot, message);
            if (opts.push() && commitSha != null) pushHead(repoRoot);
        }

        // 6) Retention.
        int pruned = 0;
        if (props.getKeepLast() > 0) pruned = pruneOlderThan(root, props.getKeepLast());

        log.info("Created backup {} (secrets={}, logs={}, commit={}, pruned={})",
            dest, opts.includeSecrets(), opts.includeLogs(), commitSha, pruned);

        return new BackupSummary(stamp, dest.toString(),
            (Integer) manifest.get("assetCount"),
            (Integer) manifest.get("logCount"),
            opts.includeSecrets(), opts.includeLogs(),
            commitSha, pruned, Instant.now());
    }

    public record RestoreOptions(
        Path source,           // path to a specific timestamped backup folder
        boolean includeSecrets,
        boolean includeLogs
    ) {}

    public record RestoreResult(
        int assetsRestored,
        int edgesRestored,
        boolean secretsRestored,
        boolean logsRestored,
        String fromStamp
    ) {}

    /**
     * Restore from a backup folder (the timestamped sub-dir, not the root). Always restores
     * state. Secrets and logs are opt-in per call.
     */
    public RestoreResult restore(RestoreOptions opts) throws IOException {
        Path src = opts.source();
        if (!Files.isDirectory(src)) throw new IOException("Backup folder not found: " + src);

        // Sanity check the manifest before touching anything.
        Path manifest = src.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("Not a valid backup folder (no manifest.json): " + src);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = json.readValue(manifest.toFile(), Map.class);
        Object schemaObj = m.get("schemaVersion");
        int schema = schemaObj instanceof Number n ? n.intValue() : -1;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("Backup schema " + schema + " does not match expected " + SCHEMA_VERSION);
        }

        // 1) State — copy backup/state/ over StateService's configured location, then importTree.
        Path stateSrc = src.resolve("state");
        if (Files.isDirectory(stateSrc)) {
            Path stateLive = state.stateDir();
            // Wipe live state dir's working tree (preserve a top-level .git if it exists),
            // then copy the backup's state contents in.
            wipeKeepingGit(stateLive);
            copyTree(stateSrc, stateLive);
        }
        StateService.ImportResult im = state.importTree();

        // 2) Secrets — opt-in, deliberate.
        boolean secretsRestored = false;
        if (opts.includeSecrets()) {
            Path secretsSrc = src.resolve("secrets");
            if (Files.isDirectory(secretsSrc)) {
                Path dst = Path.of(System.getProperty("user.home"), ".devportal", "secrets");
                Files.createDirectories(dst);
                copyTree(secretsSrc, dst);
                lockDownPermissions(dst);
                secretsRestored = true;
            }
        }

        // 3) Logs — opt-in (rare; mostly here for completeness when reproducing).
        boolean logsRestored = false;
        if (opts.includeLogs()) {
            Path logsSrc = src.resolve("logs");
            if (Files.isDirectory(logsSrc)) {
                Path dst = Path.of(System.getProperty("user.home"), ".devportal", "logs");
                Files.createDirectories(dst);
                copyTree(logsSrc, dst);
                logsRestored = true;
            }
        }

        log.info("Restored from {} — {} assets, {} edges, secrets={}, logs={}",
            src, im.assets(), im.edges(), secretsRestored, logsRestored);
        return new RestoreResult(im.assets(), im.edges(), secretsRestored, logsRestored,
            src.getFileName().toString());
    }

    /** List timestamped backups under the configured root, newest first. */
    public List<BackupSummary> list() throws IOException {
        return list(rootDir());
    }

    public List<BackupSummary> list(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<BackupSummary> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                .filter(p -> Files.isRegularFile(p.resolve("manifest.json")))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .forEach(p -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = json.readValue(p.resolve("manifest.json").toFile(), Map.class);
                        Object createdAt = m.get("createdAt");
                        out.add(new BackupSummary(
                            p.getFileName().toString(),
                            p.toString(),
                            asInt(m.get("assetCount")),
                            asInt(m.get("logCount")),
                            Boolean.TRUE.equals(m.get("includesSecrets")),
                            Boolean.TRUE.equals(m.get("includesLogs")),
                            null, 0,
                            createdAt instanceof String s ? Instant.parse(s) : null
                        ));
                    } catch (IOException ignored) {}
                });
        }
        return out;
    }

    // ---------- helpers ----------

    private static String hostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    private static int asInt(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    private static int countYaml(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> p.toString().endsWith(".yaml")).count();
        }
    }

    private static int countFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            return (int) s.filter(Files::isRegularFile).count();
        }
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        Files.createDirectories(dest);
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(src.relativize(dir));
                if (!Files.isDirectory(target)) Files.createDirectory(target);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Skip the .git directory of the source — backups should not nest a repo inside a repo.
                if (src.relativize(file).startsWith(".git")) return FileVisitResult.CONTINUE;
                Files.copy(file, dest.resolve(src.relativize(file)),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Wipe a directory's contents but keep a top-level {@code .git} folder if present. */
    private static void wipeKeepingGit(Path root) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> top = Files.list(root)) {
            for (Path p : (Iterable<Path>) top::iterator) {
                if (".git".equals(p.getFileName().toString())) continue;
                deleteRecursive(p);
            }
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                s.sorted(Comparator.reverseOrder()).forEach(x -> {
                    try { Files.deleteIfExists(x); } catch (IOException ignored) {}
                });
            }
        } else {
            Files.deleteIfExists(p);
        }
    }

    private static void lockDownPermissions(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                try {
                    Files.setPosixFilePermissions(p,
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException | IOException ignored) {}
            });
        }
    }

    /** True if {@code dir} is anywhere inside a git working tree (walking up). */
    private static boolean isInsideWorkTree(Path dir) {
        return locateGitWorkTree(dir) != null;
    }

    /** Walk up from {@code dir} to the git working tree root, or null if not in one. */
    private static Path locateGitWorkTree(Path dir) {
        Path p = dir.toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve(".git"))) return p;
            p = p.getParent();
        }
        return null;
    }

    private void pushHead(Path repoRoot) throws IOException, GitAPIException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(repoRoot.resolve(".git").toFile()).build();
             org.eclipse.jgit.api.Git g = new org.eclipse.jgit.api.Git(repo)) {
            g.push().call();
        }
    }

    private static int pruneOlderThan(Path root, int keepLast) throws IOException {
        if (!Files.isDirectory(root)) return 0;
        List<Path> stamped;
        try (Stream<Path> s = Files.list(root)) {
            stamped = s.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().matches("\\d{8}-\\d{6}"))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .toList();
        }
        if (stamped.size() <= keepLast) return 0;
        int pruned = 0;
        for (Path old : stamped.subList(keepLast, stamped.size())) {
            deleteRecursive(old);
            pruned++;
        }
        return pruned;
    }
}
