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
import io.devportal.runtime.k8s.dto.K8sStatus;
import io.devportal.runtime.k8s.dto.MonitoringLinks;
import io.devportal.workspace.WorkspaceService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(K8sProperties.class)
public class K8sService {

    private static final Logger log = LoggerFactory.getLogger(K8sService.class);

    private final K8sProperties props;
    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final ManifestParser manifestParser;
    private final ObjectMapper json = new ObjectMapper();

    public K8sService(K8sProperties props, AssetRepository assets,
                      WorkspaceService workspace, ManifestParser manifestParser) {
        this.props = props;
        this.assets = assets;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
    }

    public Map<String, Object> apply(String assetId) throws IOException, InterruptedException {
        Asset asset = loadAsset(assetId);
        Path manifestPath = resolveK8sPath(assetId);
        String ns = effectiveNamespace(assetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", asset.id());
        result.put("namespace", ns);
        result.put("manifestPath", manifestPath.toString());
        ProcResult res = exec(new String[]{
            "kubectl", "apply", "-n", ns, "-f", manifestPath.toString()
        });
        result.put("exitCode", res.exitCode);
        result.put("output", res.output);
        if (res.exitCode != 0) throw new IOException("kubectl apply failed: " + res.output);
        return result;
    }

    public Map<String, Object> delete(String assetId) throws IOException, InterruptedException {
        Asset asset = loadAsset(assetId);
        Path manifestPath = resolveK8sPath(assetId);
        String ns = effectiveNamespace(assetId);
        ProcResult res = exec(new String[]{
            "kubectl", "delete", "-n", ns, "-f", manifestPath.toString(), "--ignore-not-found=true"
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", asset.id());
        result.put("namespace", ns);
        result.put("exitCode", res.exitCode);
        result.put("output", res.output);
        return result;
    }

    public K8sStatus status(String assetId) throws IOException, InterruptedException {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        String ns = effectiveNamespace(assetId);
        String selector = "app=" + assetId;

        ProcResult pods = exec(new String[]{
            "kubectl", "get", "pods", "-n", ns, "-l", selector, "-o", "json"
        });
        ProcResult svcs = exec(new String[]{
            "kubectl", "get", "services", "-n", ns, "-l", selector, "-o", "json"
        });

        List<K8sStatus.Pod> podList = new ArrayList<>();
        List<K8sStatus.Service> svcList = new ArrayList<>();

        if (pods.exitCode == 0) {
            JsonNode root = json.readTree(pods.output);
            for (JsonNode item : root.path("items")) {
                podList.add(new K8sStatus.Pod(
                    item.path("metadata").path("name").asText(),
                    item.path("status").path("phase").asText(),
                    item.path("spec").path("nodeName").asText(null),
                    item.path("status").path("startTime").asText(null)
                ));
            }
        }
        if (svcs.exitCode == 0) {
            JsonNode root = json.readTree(svcs.output);
            for (JsonNode item : root.path("items")) {
                List<Integer> ports = new ArrayList<>();
                for (JsonNode p : item.path("spec").path("ports")) {
                    ports.add(p.path("port").asInt());
                }
                svcList.add(new K8sStatus.Service(
                    item.path("metadata").path("name").asText(),
                    item.path("spec").path("type").asText(),
                    item.path("spec").path("clusterIP").asText(null),
                    ports
                ));
            }
        }
        return new K8sStatus(ns, podList, svcList);
    }

    public MonitoringLinks links(String assetId) {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        String ns = effectiveNamespace(assetId);
        String k9s = "k9s --namespace " + ns + " --command pods --selector app=" + assetId;
        String kubectlLogs = "kubectl logs -n " + ns + " -l app=" + assetId + " --tail=200 -f";
        String grafana = props.monitoringBaseUrl() == null || props.monitoringBaseUrl().isBlank()
            ? null : props.monitoringBaseUrl() + assetId;
        return new MonitoringLinks(k9s, grafana, kubectlLogs);
    }

    private Path resolveK8sPath(String assetId) throws IOException {
        Path ws = workspace.workspaceFor(assetId);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace empty for '" + assetId + "' — run a build first");
        }
        Path manifest = ws.resolve("devportal.yaml");
        String relPath = "k8s/";
        if (Files.exists(manifest)) {
            ManifestParseResult parsed = manifestParser.parse(Files.readString(manifest));
            if (parsed.manifest() != null && parsed.manifest().spec() != null
                && parsed.manifest().spec().kubernetes() != null) {
                Manifest.Kubernetes k = parsed.manifest().spec().kubernetes();
                if (k.enabled() != null && !k.enabled()) {
                    throw new ConflictException("kubernetes.enabled=false in manifest");
                }
                if (k.manifestPath() != null && !k.manifestPath().isBlank()) relPath = k.manifestPath();
            }
        }
        Path resolved = ws.resolve(relPath);
        if (!Files.exists(resolved)) {
            throw new ConflictException("k8s manifest path not found: " + resolved);
        }
        return resolved;
    }

    private String effectiveNamespace(String assetId) {
        Path manifest = workspace.workspaceFor(assetId).resolve("devportal.yaml");
        if (Files.exists(manifest)) {
            try {
                ManifestParseResult parsed = manifestParser.parse(Files.readString(manifest));
                if (parsed.manifest() != null && parsed.manifest().spec() != null
                    && parsed.manifest().spec().kubernetes() != null) {
                    String ns = parsed.manifest().spec().kubernetes().namespace();
                    if (ns != null && !ns.isBlank()) return ns;
                }
            } catch (IOException ignored) {}
        }
        return props.namespace() == null || props.namespace().isBlank() ? "default" : props.namespace();
    }

    private Asset loadAsset(String id) {
        return assets.findById(id).orElseThrow(
            () -> new NotFoundException("Asset '" + id + "' not found"));
    }

    private static ProcResult exec(String[] args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append("\n");
        }
        int code = p.waitFor();
        log.debug("kubectl rc={} for {}", code, String.join(" ", args));
        return new ProcResult(code, out.toString());
    }

    private record ProcResult(int exitCode, String output) {}
}
