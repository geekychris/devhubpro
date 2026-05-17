package io.devportal.runtime.k8s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
import io.devportal.manifest.ManifestProxyEditor;
import io.devportal.workspace.WorkspaceService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Portal-wide directory of proxy routes declared by assets via {@code spec.runtime.proxy}.
 * Reads the workspace copy of each asset's {@code devportal.yaml} and enriches with cluster
 * state (whether a corresponding Ingress is applied) by querying ingresses labeled
 * {@code devportal.io/managed=true}.
 */
@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(
    io.devportal.runtime.UrlsProperties.class)
public class ProxyRoutesService {

    private static final Logger log = LoggerFactory.getLogger(ProxyRoutesService.class);

    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final ManifestParser manifestParser;
    private final K8sService k8s;
    private final io.devportal.runtime.UrlsProperties urls;
    private final ObjectMapper json = new ObjectMapper();

    public ProxyRoutesService(AssetRepository assets, WorkspaceService workspace,
                              ManifestParser manifestParser, K8sService k8s,
                              io.devportal.runtime.UrlsProperties urls) {
        this.assets = assets;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        this.k8s = k8s;
        this.urls = urls;
    }

    public record Route(
        String assetId,
        String assetName,
        String namespace,
        String path,
        String portSlot,
        boolean stripPrefix,
        String host,
        boolean applied,
        String url
    ) {}

    public record RoutesResponse(List<Route> routes, List<Conflict> conflicts) {}

    /** A conflict is two assets sharing the same (host, path) key. host is "*" when unset. */
    public record Conflict(String host, String path, List<String> assetIds) {}

    public RoutesResponse list() {
        List<Asset> all = assets.findAll(null, null, null, null);
        Map<String, Boolean> appliedByAsset = appliedIngresses();

        List<Route> routes = new ArrayList<>();
        Map<String, List<String>> byKey = new HashMap<>();

        for (Asset a : all) {
            Manifest.Proxy p = readProxy(a.id());
            if (p == null || p.portSlot() == null || p.portSlot().isBlank()) continue;
            boolean hasHost = p.host() != null && !p.host().isBlank();
            boolean hasPath = p.path() != null && !p.path().isBlank();
            if (!hasHost && !hasPath) continue;

            String effectivePath = hasPath ? p.path() : "/";
            boolean stripPrefix = (p.stripPrefix() == null || p.stripPrefix()) && !"/".equals(effectivePath);
            String ns;
            try { ns = k8s.effectiveNamespace(a.id()); } catch (Exception e) { ns = a.id(); }
            String urlHost = hasHost ? p.host() : urls.host();
            routes.add(new Route(
                a.id(), a.name(), ns,
                effectivePath, p.portSlot(), stripPrefix, p.host(),
                appliedByAsset.getOrDefault(a.id(), false),
                "http://" + urlHost + effectivePath
            ));
            String key = (hasHost ? p.host() : "*") + "|" + effectivePath;
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(a.id());
        }

        List<Conflict> conflicts = new ArrayList<>();
        for (var e : byKey.entrySet()) {
            if (e.getValue().size() <= 1) continue;
            String[] parts = e.getKey().split("\\|", 2);
            conflicts.add(new Conflict(parts[0], parts[1], e.getValue()));
        }

        return new RoutesResponse(routes, conflicts);
    }

    private Manifest.Proxy readProxy(String assetId) {
        try {
            Path file = workspace.workspaceFor(assetId).resolve("devportal.yaml");
            if (!Files.exists(file)) return null;
            ManifestParseResult parsed = manifestParser.parse(Files.readString(file));
            if (parsed.manifest() == null || parsed.manifest().spec() == null) return null;
            if (parsed.manifest().spec().runtime() == null) return null;
            return parsed.manifest().spec().runtime().proxy();
        } catch (IOException e) {
            return null;
        }
    }

    /** Read the current proxy config for an asset (null when unset). */
    public Manifest.Proxy currentProxy(String assetId) {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        return readProxy(assetId);
    }

    /**
     * Write {@code spec.runtime.proxy} into the asset's workspace {@code devportal.yaml}, leaving
     * the change uncommitted so the user reviews it via the Changes tab. Returns the proxy that
     * was written.
     */
    public Manifest.Proxy setProxy(String assetId, String path, String portSlot,
                                   Boolean stripPrefix, String host) throws IOException {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        if (portSlot == null || portSlot.isBlank()) {
            throw new ConflictException("portSlot is required");
        }
        boolean hasPath = path != null && !path.isBlank();
        boolean hasHost = host != null && !host.isBlank();
        if (!hasPath && !hasHost) {
            throw new ConflictException("Set at least one of path or host");
        }
        if (hasPath && !path.startsWith("/")) {
            throw new ConflictException("path must start with '/'");
        }
        Path file = workspace.workspaceFor(assetId).resolve("devportal.yaml");
        if (!Files.exists(file)) {
            throw new ConflictException("Workspace has no devportal.yaml — sync the asset first");
        }
        boolean strip = stripPrefix == null || stripPrefix;
        String src = Files.readString(file);
        String updated = ManifestProxyEditor.setProxy(src,
            hasPath ? path : null, portSlot, strip, hasHost ? host : null);
        Files.writeString(file, updated);
        return new Manifest.Proxy(hasPath ? path : null, portSlot, strip, hasHost ? host : null);
    }

    /** Remove {@code spec.runtime.proxy} from the asset's workspace {@code devportal.yaml}. */
    public void removeProxy(String assetId) throws IOException {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        Path file = workspace.workspaceFor(assetId).resolve("devportal.yaml");
        if (!Files.exists(file)) {
            throw new ConflictException("Workspace has no devportal.yaml — sync the asset first");
        }
        String src = Files.readString(file);
        String updated = ManifestProxyEditor.removeProxy(src);
        if (!updated.equals(src)) Files.writeString(file, updated);
    }

    /** Map asset id → true when a portal-managed Ingress for that asset exists in the cluster. */
    private Map<String, Boolean> appliedIngresses() {
        Map<String, Boolean> out = new HashMap<>();
        try {
            ProcResult r = exec("kubectl", "get", "ingresses", "-A",
                "-l", "devportal.io/managed=true", "-o", "json");
            if (r.exitCode != 0) return out;
            JsonNode root = json.readTree(r.output);
            for (JsonNode item : root.path("items")) {
                String app = item.path("metadata").path("labels").path("app").asText(null);
                if (app != null) out.put(app, true);
            }
        } catch (IOException | InterruptedException e) {
            log.debug("appliedIngresses scan failed: {}", e.getMessage());
        }
        return out;
    }

    private static ProcResult exec(String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return new ProcResult(p.waitFor(), sb.toString());
    }

    private record ProcResult(int exitCode, String output) {}
}
