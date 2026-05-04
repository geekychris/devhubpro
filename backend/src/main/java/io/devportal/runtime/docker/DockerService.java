package io.devportal.runtime.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.build.Build;
import io.devportal.build.BuildMode;
import io.devportal.build.BuildRepository;
import io.devportal.build.BuildRunner;
import io.devportal.build.dto.BuildView;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
import io.devportal.port.PortAllocator;
import io.devportal.port.PortReservation;
import io.devportal.runtime.docker.dto.DockerContainerView;
import io.devportal.runtime.docker.dto.RunContainerResult;
import io.devportal.workspace.WorkspaceService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DockerService {

    private static final Logger log = LoggerFactory.getLogger(DockerService.class);
    private static final String LABEL = "io.devportal.asset";

    private final AssetRepository assets;
    private final BuildRepository builds;
    private final BuildRunner runner;
    private final WorkspaceService workspace;
    private final ManifestParser manifestParser;
    private final PortAllocator ports;
    private final ObjectMapper json = new ObjectMapper();

    public DockerService(AssetRepository assets, BuildRepository builds, BuildRunner runner,
                         WorkspaceService workspace, ManifestParser manifestParser, PortAllocator ports) {
        this.assets = assets;
        this.builds = builds;
        this.runner = runner;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        this.ports = ports;
    }

    /** Kick off `docker build` as a regular Build row; returns immediately. */
    public BuildView buildImage(String assetId) throws IOException {
        Asset asset = loadAsset(assetId);
        Path ws = workspace.workspaceFor(assetId);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace for '" + assetId + "' is empty — run a build to clone the repo first");
        }
        DockerSpec spec = readSpec(ws);
        if (!spec.enabled) {
            throw new ConflictException("Asset '" + assetId + "' has docker.enabled=false in its manifest");
        }
        String image = spec.image == null || spec.image.isBlank() ? "devportal/" + assetId + ":latest" : spec.image;
        String dockerfile = spec.dockerfile == null ? "Dockerfile" : spec.dockerfile;
        String context = spec.context == null ? "." : spec.context;
        String commandLine = "docker build --label " + LABEL + "=" + assetId
            + " -f " + shell(dockerfile) + " -t " + shell(image) + " " + shell(context);

        Path logPath = Path.of(System.getProperty("user.home"), ".devportal", "logs",
            assetId + "-docker-build-" + System.currentTimeMillis() + ".log");
        long buildId = builds.insertQueued(asset.id(), null, BuildMode.SHALLOW,
            "docker-build", commandLine, asset.repoDefaultBranch(),
            ws.toString(), logPath.toString());
        runner.runAsync(buildId, ws, commandLine, null, logPath);
        return BuildView.of(builds.findById(buildId).orElseThrow());
    }

    /**
     * Run a container for an asset. Allocates local-scope ports if not already, maps them to
     * manifest-declared slots. Returns the container id and port mapping.
     */
    public RunContainerResult runContainer(String assetId) throws IOException, InterruptedException {
        Asset asset = loadAsset(assetId);
        Path ws = workspace.workspaceFor(assetId);
        DockerSpec spec = readSpec(ws);
        if (!spec.enabled) {
            throw new ConflictException("docker.enabled=false in manifest for '" + assetId + "'");
        }
        String image = spec.image == null || spec.image.isBlank() ? "devportal/" + assetId + ":latest" : spec.image;

        List<PortReservation> allocated = ports.allocate(assetId, "local", false);
        List<Manifest.Port> slots = readSlots(ws);

        // Build -p flags: host_port -> container_port. We assume container port == host port for simplicity
        // when the manifest declares slots without container-port info; user can override via docker run.
        List<String> args = new ArrayList<>(List.of("docker", "run", "-d",
            "--name", "devportal-" + assetId,
            "--label", LABEL + "=" + assetId));

        List<RunContainerResult.PortMapping> mappings = new ArrayList<>();
        for (PortReservation r : allocated) {
            int containerPort = r.port(); // simple convention; manifest can override later
            args.add("-p");
            args.add(r.port() + ":" + containerPort + (r.protocol().equals("udp") ? "/udp" : ""));
            mappings.add(new RunContainerResult.PortMapping(r.slotName(), r.port(), containerPort, r.protocol()));
        }
        args.add(image);

        ProcResult res = exec(args.toArray(String[]::new), ws);
        if (res.exitCode != 0) {
            throw new IOException("docker run failed (" + res.exitCode + "): " + res.combined);
        }
        String containerId = res.stdout.trim();
        log.info("Started container {} for asset {} ({}), ports={}", containerId, assetId, image, mappings);
        return new RunContainerResult(containerId, "devportal-" + assetId, image, mappings, res.combined);
    }

    public List<DockerContainerView> listContainers(String assetId) throws IOException, InterruptedException {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        ProcResult res = exec(new String[]{
            "docker", "ps", "-a",
            "--filter", "label=" + LABEL + "=" + assetId,
            "--format", "{{json .}}"
        }, null);
        if (res.exitCode != 0) {
            throw new IOException("docker ps failed: " + res.combined);
        }
        List<DockerContainerView> out = new ArrayList<>();
        for (String line : res.stdout.split("\n")) {
            if (line.isBlank()) continue;
            Map<?, ?> m = json.readValue(line, Map.class);
            Object portsObj = m.get("Ports");
            String portsStr = portsObj == null ? "" : portsObj.toString();
            out.add(new DockerContainerView(
                (String) m.get("ID"),
                (String) m.get("Names"),
                (String) m.get("Image"),
                (String) m.get("Status"),
                portsStr.isBlank() ? List.of() : List.of(portsStr.split(",\\s*"))
            ));
        }
        return out;
    }

    public void stopAndRemove(String containerName) throws IOException, InterruptedException {
        exec(new String[]{"docker", "stop", containerName}, null);
        exec(new String[]{"docker", "rm", containerName}, null);
    }

    private DockerSpec readSpec(Path ws) throws IOException {
        Path manifest = ws.resolve("devportal.yaml");
        if (!Files.exists(manifest)) return new DockerSpec(false, null, null, null);
        ManifestParseResult parsed = manifestParser.parse(Files.readString(manifest));
        if (parsed.manifest() == null || parsed.manifest().spec() == null
            || parsed.manifest().spec().docker() == null) {
            return new DockerSpec(false, null, null, null);
        }
        Manifest.Docker d = parsed.manifest().spec().docker();
        return new DockerSpec(
            d.enabled() != null && d.enabled(),
            d.dockerfile(), d.context(), d.image()
        );
    }

    private List<Manifest.Port> readSlots(Path ws) throws IOException {
        Path manifest = ws.resolve("devportal.yaml");
        if (!Files.exists(manifest)) return List.of();
        ManifestParseResult parsed = manifestParser.parse(Files.readString(manifest));
        if (parsed.manifest() == null || parsed.manifest().spec() == null
            || parsed.manifest().spec().runtime() == null
            || parsed.manifest().spec().runtime().ports() == null) {
            return List.of();
        }
        return parsed.manifest().spec().runtime().ports();
    }

    private Asset loadAsset(String id) {
        return assets.findById(id).orElseThrow(
            () -> new NotFoundException("Asset '" + id + "' not found"));
    }

    private static String shell(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static ProcResult exec(String[] args, Path cwd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args);
        if (cwd != null) pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
                out.append(line).append("\n");
            }
        }
        int code = p.waitFor();
        return new ProcResult(code, out.toString(), sb.toString());
    }

    private record ProcResult(int exitCode, String stdout, String combined) {}
    private record DockerSpec(boolean enabled, String dockerfile, String context, String image) {}

    /** Used by Build flow to know about completed builds (placeholder in case we need it). */
    @SuppressWarnings("unused")
    private void touchBuild(Build b) { /* no-op */ }
}
