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
    private final io.devportal.port.PortRepository ports;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

    public K8sService(K8sProperties props, AssetRepository assets,
                      WorkspaceService workspace, ManifestParser manifestParser,
                      io.devportal.port.PortRepository ports) {
        this.props = props;
        this.assets = assets;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        this.ports = ports;
    }

    public Map<String, Object> apply(String assetId) throws IOException, InterruptedException {
        Asset asset = loadAsset(assetId);
        Path manifestPath = resolveK8sPath(assetId);
        Path renderedDir = renderForApply(asset, manifestPath);
        String ns = effectiveNamespace(assetId);
        ensureNamespace(ns);                    // auto-create if missing
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", asset.id());
        result.put("namespace", ns);
        result.put("manifestPath", manifestPath.toString());
        result.put("renderedDir", renderedDir.toString());
        ProcResult res = exec(new String[]{
            "kubectl", "apply", "-n", ns, "-f", renderedDir.toString()
        });
        result.put("exitCode", res.exitCode);
        result.put("output", res.output);
        if (res.exitCode != 0) throw new IOException("kubectl apply failed: " + res.output);
        return result;
    }

    public Map<String, Object> delete(String assetId) throws IOException, InterruptedException {
        Asset asset = loadAsset(assetId);
        Path manifestPath = resolveK8sPath(assetId);
        Path renderedDir = renderForApply(asset, manifestPath);
        String ns = effectiveNamespace(assetId);
        ProcResult res = exec(new String[]{
            "kubectl", "delete", "-n", ns, "-f", renderedDir.toString(), "--ignore-not-found=true"
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", asset.id());
        result.put("namespace", ns);
        result.put("exitCode", res.exitCode);
        result.put("output", res.output);
        return result;
    }

    /**
     * Read all yaml manifests under {@code source}, patch Service ports[].nodePort to the
     * asset's allocated k8s-nodeport reservations (matching by named slot), and write the
     * rendered output to {@code ~/.devportal/runtime/<assetId>/k8s-rendered/}. Returns that dir.
     *
     * <p>Slot matching: a Service port whose name matches a registered slot name gets that slot's
     * nodePort. If no name match but the asset has exactly one allocation, that's used.
     */
    public Path renderForApply(Asset asset, Path source) throws IOException {
        Path target = Path.of(System.getProperty("user.home"), ".devportal", "runtime",
            asset.id(), "k8s-rendered");
        if (Files.isDirectory(target)) {
            try (var stream = Files.walk(target)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
        Files.createDirectories(target);

        var allocations = ports.findByAssetAndScope(asset.id(), "k8s-nodeport");
        java.util.Map<String, Integer> bySlot = new java.util.HashMap<>();
        for (var r : allocations) bySlot.put(r.slotName(), r.port());

        if (Files.isRegularFile(source)) {
            renderOneFile(source, target.resolve(source.getFileName()), bySlot);
        } else {
            try (var stream = Files.walk(source)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".yaml") || n.endsWith(".yml");
                    })
                    .forEach(p -> {
                        Path rel = source.relativize(p);
                        Path dst = target.resolve(rel);
                        try {
                            Files.createDirectories(dst.getParent());
                            renderOneFile(p, dst, bySlot);
                        } catch (IOException e) {
                            log.warn("render skipped for {}: {}", p, e.getMessage());
                        }
                    });
            }
        }
        log.info("Rendered k8s manifests for {} into {} (allocations={})", asset.id(), target, bySlot);
        return target;
    }

    private void renderOneFile(Path src, Path dst, java.util.Map<String, Integer> bySlot) throws IOException {
        // Multi-doc YAML: read all docs, patch Services, write back.
        com.fasterxml.jackson.databind.node.ArrayNode out = json.createArrayNode();
        try (var parser = yaml.getFactory().createParser(Files.newBufferedReader(src))) {
            com.fasterxml.jackson.databind.MappingIterator<com.fasterxml.jackson.databind.JsonNode> it
                = yaml.readerFor(com.fasterxml.jackson.databind.JsonNode.class).readValues(parser);
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode doc = it.next();
                patchService(doc, bySlot);
                out.add(doc);
            }
        }
        // Write each doc separated by --- marker.
        try (var writer = Files.newBufferedWriter(dst)) {
            for (int i = 0; i < out.size(); i++) {
                if (i > 0) writer.write("---\n");
                writer.write(yaml.writeValueAsString(out.get(i)));
            }
        }
    }

    private void patchService(com.fasterxml.jackson.databind.JsonNode doc,
                              java.util.Map<String, Integer> bySlot) {
        if (doc == null || !doc.isObject()) return;
        if (!"Service".equalsIgnoreCase(doc.path("kind").asText())) return;
        com.fasterxml.jackson.databind.JsonNode portsArray = doc.path("spec").path("ports");
        if (!portsArray.isArray()) return;
        for (int i = 0; i < portsArray.size(); i++) {
            com.fasterxml.jackson.databind.node.ObjectNode portNode =
                (com.fasterxml.jackson.databind.node.ObjectNode) portsArray.get(i);
            String name = portNode.path("name").asText(null);
            Integer assigned = (name != null && bySlot.containsKey(name)) ? bySlot.get(name)
                : (bySlot.size() == 1 && i == 0 ? bySlot.values().iterator().next() : null);
            if (assigned != null) {
                portNode.put("nodePort", assigned);
            }
        }
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

    /** Public so the controller's render endpoint can use the same resolution rules. */
    public Path resolveK8sPath(String assetId) throws IOException {
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

    /**
     * Resolution order:
     * <ol>
     *   <li>{@code asset.k8sNamespace} from the DB (UI-editable per asset)</li>
     *   <li>{@code spec.kubernetes.namespace} from the asset's devportal.yaml</li>
     *   <li>global default {@code devportal.k8s.namespace} (defaults to {@code default})</li>
     * </ol>
     */
    public String effectiveNamespace(String assetId) {
        var asset = assets.findById(assetId).orElse(null);
        if (asset != null && asset.k8sNamespace() != null && !asset.k8sNamespace().isBlank()) {
            return asset.k8sNamespace();
        }
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

    /** Best-effort: ensure the namespace exists. {@code kubectl create ns} returns 0 or AlreadyExists. */
    private void ensureNamespace(String namespace) throws IOException, InterruptedException {
        if (namespace == null || namespace.isBlank() || "default".equals(namespace)) return;
        ProcResult check = exec(new String[]{"kubectl", "get", "namespace", namespace, "--no-headers", "--ignore-not-found"});
        if (check.exitCode == 0 && check.output != null && !check.output.trim().isEmpty()) return;
        ProcResult create = exec(new String[]{"kubectl", "create", "namespace", namespace});
        if (create.exitCode != 0 && !create.output.contains("AlreadyExists")) {
            log.warn("Could not auto-create namespace {}: {}", namespace, create.output);
        } else {
            log.info("Created namespace {}", namespace);
        }
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
