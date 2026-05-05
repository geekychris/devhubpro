package io.devportal.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.build.Build;
import io.devportal.build.BuildMode;
import io.devportal.build.BuildRepository;
import io.devportal.build.BuildRunner;
import io.devportal.build.BuildStatus;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Lists and runs test-data fixtures declared under {@code spec.test.fixtures}. Each fixture is a
 * named shell command. Results are persisted as a regular {@code build} row with
 * {@code command_name="test-fixture"} so they show up in the Builds tab too.
 *
 * <p>Structured output: when a fixture command emits a line of the form
 * {@code DEVPORTAL_FIXTURE: {...}} the JSON is parsed for {@code credentials}, {@code links}, and
 * {@code summary}. The credentials table is the primary UI affordance.
 */
@Service
public class TestFixtureService {

    private static final Logger log = LoggerFactory.getLogger(TestFixtureService.class);
    private static final String MARKER = "DEVPORTAL_FIXTURE:";
    private static final ObjectMapper json = new ObjectMapper();

    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final ManifestParser manifestParser;
    private final BuildRepository builds;
    private final BuildRunner runner;

    public TestFixtureService(AssetRepository assets, WorkspaceService workspace,
                              ManifestParser manifestParser,
                              BuildRepository builds, BuildRunner runner) {
        this.assets = assets;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        this.builds = builds;
        this.runner = runner;
    }

    public List<Manifest.TestFixture> list(String assetId) {
        loadAssetOr404(assetId);
        Path ws = workspace.workspaceFor(assetId);
        if (!Files.isDirectory(ws.resolve(".git"))) return List.of();
        Path manifest = ws.resolve("devportal.yaml");
        if (!Files.exists(manifest)) return List.of();
        try {
            ManifestParseResult parsed = manifestParser.parse(Files.readString(manifest));
            if (parsed.manifest() == null
                || parsed.manifest().spec() == null
                || parsed.manifest().spec().test() == null
                || parsed.manifest().spec().test().fixtures() == null) {
                return List.of();
            }
            return parsed.manifest().spec().test().fixtures();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Run one named fixture synchronously. Returns the parsed result. */
    public FixtureResult run(String assetId, String fixtureName) throws IOException {
        Asset asset = loadAssetOr404(assetId);
        Manifest.TestFixture fixture = list(assetId).stream()
            .filter(f -> fixtureName.equals(f.name()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException(
                "No fixture '" + fixtureName + "' in spec.test.fixtures of " + assetId));
        if (fixture.command() == null || fixture.command().isBlank()) {
            throw new ConflictException("Fixture '" + fixtureName + "' has no command.");
        }
        String runIn = fixture.runIn() == null ? "host" : fixture.runIn();
        if (!"host".equals(runIn)) {
            throw new ConflictException(
                "runIn=" + runIn + " not yet implemented (only 'host' supported).");
        }

        Path ws = workspace.workspaceFor(asset.id());
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace for " + assetId + " not cloned yet.");
        }

        Path logPath = Path.of(System.getProperty("user.home"), ".devportal", "logs",
            assetId + "-fixture-" + fixtureName + "-" + System.currentTimeMillis() + ".log");
        long buildId = builds.insertQueued(assetId, /*parent*/ null, BuildMode.SHALLOW,
            "test-fixture", fixture.command(),
            asset.repoDefaultBranch() == null ? "main" : asset.repoDefaultBranch(),
            ws.toString(), logPath.toString());

        BuildStatus status = runner.run(buildId, ws, fixture.command(), /*gitSha*/ null, logPath);
        Build row = builds.findById(buildId).orElseThrow();
        return assemble(fixture.name(), row, logPath);
    }

    /**
     * Run all fixtures with {@code runOnApply: true} in declaration order. Used by the apply
     * endpoint when the caller passes {@code runHooks=true}. Each fixture's structured result is
     * collected and returned; the chain stops on the first failure.
     */
    public List<FixtureResult> runOnApplyHooks(String assetId) throws IOException {
        List<FixtureResult> out = new ArrayList<>();
        for (Manifest.TestFixture f : list(assetId)) {
            if (!Boolean.TRUE.equals(f.runOnApply())) continue;
            log.info("Lifecycle hook: running {}.{} (runOnApply)", assetId, f.name());
            FixtureResult r = run(assetId, f.name());
            out.add(r);
            if (!"succeeded".equals(r.status())) {
                log.warn("Lifecycle hook {}.{} failed; stopping chain", assetId, f.name());
                break;
            }
        }
        return out;
    }

    /** Last-run result for a fixture, or empty if it's never run. */
    public Optional<FixtureResult> lastRun(String assetId, String fixtureName) {
        // Find the most recent build with command_name=test-fixture for this asset whose
        // commandLine matches the fixture's command (cheap and side-effect-free).
        List<Build> recent = builds.findByAsset(assetId, 50);
        for (Build b : recent) {
            if (!"test-fixture".equals(b.commandName())) continue;
            // We stored the fixture name in command_name? No — we store "test-fixture". The
            // commandLine identifies the fixture indirectly. To be accurate, we re-load the
            // manifest fixture by name and match by commandLine.
            Manifest.TestFixture f = list(assetId).stream()
                .filter(x -> fixtureName.equals(x.name())).findFirst().orElse(null);
            if (f == null) return Optional.empty();
            if (!f.command().equals(b.commandLine())) continue;
            return Optional.of(assemble(fixtureName, b, Path.of(b.logPath())));
        }
        return Optional.empty();
    }

    // ---------- internals ----------

    private FixtureResult assemble(String fixtureName, Build row, Path logPath) {
        List<String> tail = readTail(logPath, 30);
        String marker = readFixtureMarker(logPath);
        String summary = null;
        List<FixtureResult.Credential> creds = List.of();
        List<FixtureResult.Link> links = List.of();
        String parseError = null;
        if (marker != null) {
            try {
                JsonNode doc = json.readTree(marker);
                summary = textOrNull(doc, "summary");
                creds = parseCredentials(doc.path("credentials"));
                links = parseLinks(doc.path("links"));
            } catch (IOException e) {
                parseError = "Failed to parse DEVPORTAL_FIXTURE JSON: " + e.getMessage();
            }
        }
        Long durationMs = (row.startedAt() == null || row.finishedAt() == null) ? null
            : Duration.between(row.startedAt(), row.finishedAt()).toMillis();
        return new FixtureResult(
            fixtureName, row.id(), row.status().dbValue(), row.exitCode(),
            row.startedAt(), row.finishedAt(), durationMs,
            summary, creds, links, tail, parseError
        );
    }

    /** Find the LAST DEVPORTAL_FIXTURE: line in the log; return its JSON payload or null. */
    private static String readFixtureMarker(Path logPath) {
        if (!Files.exists(logPath)) return null;
        try {
            String last = null;
            for (String line : Files.readAllLines(logPath)) {
                int i = line.indexOf(MARKER);
                if (i < 0) continue;
                last = line.substring(i + MARKER.length()).trim();
            }
            return last;
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> readTail(Path logPath, int lines) {
        if (!Files.exists(logPath)) return List.of();
        try {
            List<String> all = Files.readAllLines(logPath);
            int from = Math.max(0, all.size() - lines);
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<FixtureResult.Credential> parseCredentials(JsonNode arr) {
        if (!arr.isArray()) return List.of();
        List<FixtureResult.Credential> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            out.add(new FixtureResult.Credential(
                textOrNull(n, "label"),
                textOrNull(n, "username"),
                textOrNull(n, "password"),
                textOrNull(n, "role"),
                textOrNull(n, "url")
            ));
        }
        return out;
    }

    private static List<FixtureResult.Link> parseLinks(JsonNode arr) {
        if (!arr.isArray()) return List.of();
        List<FixtureResult.Link> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            out.add(new FixtureResult.Link(
                textOrNull(n, "label"),
                textOrNull(n, "url")
            ));
        }
        return out;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private Asset loadAssetOr404(String id) {
        return assets.findById(id).orElseThrow(
            () -> new NotFoundException("Asset '" + id + "' not found"));
    }
}
