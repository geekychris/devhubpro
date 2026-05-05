package io.devportal.runtime.endpoints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.port.PortRepository;
import io.devportal.port.PortReservation;
import io.devportal.workspace.WorkspaceService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Aggregates every reasonable way a user might "talk to" an asset:
 * <ul>
 *   <li>locally-allocated docker ports (each → {@code http://localhost:<port>/})</li>
 *   <li>k8s NodePort allocations (Rancher Desktop forwards these to localhost too)</li>
 *   <li>a few well-known paths (/, /actuator/health, /swagger-ui/index.html) layered on the http port</li>
 *   <li>the GitHub repo URL itself</li>
 * </ul>
 * The {@code live} flag is a best-effort hint: yes if there's a corresponding running container
 * or pod, no otherwise.
 */
@Service
public class EndpointsService {

    private static final Logger log = LoggerFactory.getLogger(EndpointsService.class);

    private final AssetRepository assets;
    private final PortRepository ports;
    private final WorkspaceService workspace;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public EndpointsService(AssetRepository assets, PortRepository ports, WorkspaceService workspace) {
        this.assets = assets;
        this.ports = ports;
        this.workspace = workspace;
    }

    public AssetEndpoints discover(String assetId) throws IOException, InterruptedException {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));

        List<AssetEndpoints.Endpoint> out = new ArrayList<>();

        boolean hasLocalContainer = dockerContainerRunning(assetId);
        boolean hasK8sPod = k8sPodRunning(assetId);

        // Local docker ports — host accessible
        for (PortReservation r : ports.findByAssetAndScope(assetId, "local")) {
            String url = "http://localhost:" + r.port() + "/";
            out.add(new AssetEndpoints.Endpoint(
                "Local docker — " + r.slotName(),
                url, "local-docker",
                "port registry slot " + r.slotName() + " (host=" + r.port() + ")",
                hasLocalContainer, true, null));
            if ("http".equals(r.slotName())) {
                addSpringPaths(out, "Local docker — ", "http://localhost:" + r.port(),
                    hasLocalContainer, "port registry", true, null);
            }
        }

        // k8s NodePort URLs — host accessible (Rancher Desktop forwards localhost;
        // on remote clusters NodePort is on the node IP, not localhost)
        for (PortReservation r : ports.findByAssetAndScope(assetId, "k8s-nodeport")) {
            String url = "http://localhost:" + r.port() + "/";
            out.add(new AssetEndpoints.Endpoint(
                "K8s NodePort — " + r.slotName(),
                url, "k8s-nodeport",
                "port registry NodePort " + r.port() + " forwards from localhost",
                hasK8sPod, true, null));
            if ("http".equals(r.slotName())) {
                addSpringPaths(out, "K8s — ", "http://localhost:" + r.port(),
                    hasK8sPod, "NodePort", true, null);
            }
        }

        // GitHub repo (external)
        if (asset.repoUrl() != null && !asset.repoUrl().isBlank()) {
            out.add(new AssetEndpoints.Endpoint(
                "GitHub repository", asset.repoUrl(), "external",
                "asset.repoUrl", true, true, null));
        }

        // ClusterIP — in-cluster only; offer "expose to host" hint that opens a port-forward.
        if (hasK8sPod) {
            ClusterIp cip = clusterServiceIp(assetId);
            if (cip != null) {
                String firstPodName = firstRunningPodName(assetId);
                AssetEndpoints.ExposeHint hint = firstPodName == null ? null
                    : new AssetEndpoints.ExposeHint("port-forward", firstPodName, cip.port());
                out.add(new AssetEndpoints.Endpoint(
                    "ClusterIP (in-cluster only)",
                    "http://" + cip.ip() + ":" + cip.port() + "/",
                    "k8s-cluster",
                    "Service " + cip.name(),
                    true, false, hint));
            }
        }

        return new AssetEndpoints(assetId, out);
    }

    private String firstRunningPodName(String assetId) {
        try {
            ProcResult r = exec("kubectl", "get", "pods", "-A", "-l", "app=" + assetId,
                "-o", "jsonpath={.items[?(@.status.phase=='Running')].metadata.name}");
            String s = r.output.trim();
            if (s.isEmpty()) return null;
            return s.split("\\s+")[0];
        } catch (Exception e) { return null; }
    }

    private void addSpringPaths(List<AssetEndpoints.Endpoint> out, String prefix, String base,
                                boolean live, String origin, boolean hostAccessible,
                                AssetEndpoints.ExposeHint hint) {
        for (String path : List.of("/", "/actuator/health", "/swagger-ui/index.html")) {
            out.add(new AssetEndpoints.Endpoint(
                prefix + path,
                base + path,
                "convention", origin + " — convention path", live, hostAccessible, hint));
        }
    }

    private boolean dockerContainerRunning(String assetId) {
        try {
            ProcResult r = exec("docker", "ps",
                "--filter", "label=io.devportal.asset=" + assetId,
                "--filter", "status=running",
                "-q");
            return r.exitCode == 0 && !r.output.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean k8sPodRunning(String assetId) {
        try {
            ProcResult r = exec("kubectl", "get", "pods", "-A", "-l", "app=" + assetId,
                "-o", "jsonpath={.items[?(@.status.phase=='Running')].metadata.name}");
            return r.exitCode == 0 && !r.output.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private record ClusterIp(String name, String ip, int port) {}

    private ClusterIp clusterServiceIp(String assetId) {
        try {
            ProcResult r = exec("kubectl", "get", "service", "-A", "-l", "app=" + assetId, "-o", "json");
            if (r.exitCode != 0) return null;
            JsonNode root = json.readTree(r.output);
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) return null;
            JsonNode svc = items.get(0);
            String name = svc.path("metadata").path("name").asText();
            String ip = svc.path("spec").path("clusterIP").asText(null);
            int port = svc.path("spec").path("ports").path(0).path("port").asInt(80);
            if (ip == null || ip.isEmpty() || "None".equals(ip)) return null;
            return new ClusterIp(name, ip, port);
        } catch (Exception e) {
            return null;
        }
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
