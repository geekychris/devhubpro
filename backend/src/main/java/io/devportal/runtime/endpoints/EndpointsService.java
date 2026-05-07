package io.devportal.runtime.endpoints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
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
    private final io.devportal.runtime.k8s.K8sService k8s;
    private final ManifestParser manifestParser;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public EndpointsService(AssetRepository assets, PortRepository ports,
                            WorkspaceService workspace,
                            io.devportal.runtime.k8s.K8sService k8s,
                            ManifestParser manifestParser) {
        this.assets = assets;
        this.ports = ports;
        this.workspace = workspace;
        this.k8s = k8s;
        this.manifestParser = manifestParser;
    }

    public AssetEndpoints discover(String assetId) throws IOException, InterruptedException {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));

        List<AssetEndpoints.Endpoint> out = new ArrayList<>();

        boolean hasLocalContainer = dockerContainerRunning(assetId);

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

        // k8s Services — walk the live cluster instead of the port_reservation table so we see
        // every Service the asset deploys (including Services with hard-coded NodePorts that
        // bypassed the registry, like a frontend pinned to 30080). Each Service+port pair gets
        // its own endpoint row so the user can tell which URL talks to which component.
        for (K8sServiceInfo svc : listAssetServices(assetId)) {
            for (K8sServicePort sp : svc.ports) {
                String role = inferRole(svc.name, sp.name, sp.targetPort, svc.imageHints);
                String label = svc.name + (sp.name != null && !sp.name.isBlank() ? " — " + sp.name : "")
                    + (role != null ? " (" + role + ")" : "");
                if ("NodePort".equalsIgnoreCase(svc.type) || "LoadBalancer".equalsIgnoreCase(svc.type)) {
                    if (sp.nodePort != null) {
                        String url = "http://localhost:" + sp.nodePort + "/";
                        out.add(new AssetEndpoints.Endpoint(
                            label, url, "k8s-nodeport",
                            "Service " + svc.name + " " + svc.type + " :" + sp.nodePort,
                            svc.hasReadyEndpoint, true, null));
                        // Layer Spring's conventional paths only on Services that look like Spring Boot.
                        if (looksLikeSpring(role, svc.imageHints)) {
                            addSpringPaths(out, svc.name + " — ", "http://localhost:" + sp.nodePort,
                                svc.hasReadyEndpoint, "NodePort " + sp.nodePort, true, null);
                        }
                    }
                } else if ("ClusterIP".equalsIgnoreCase(svc.type) || svc.type == null || svc.type.isBlank()) {
                    // In-cluster only — offer a port-forward hint so users can expose to host on demand.
                    String firstPodName = firstRunningPodNameForLabel(svc.namespace, svc.selector);
                    AssetEndpoints.ExposeHint hint = (firstPodName == null || sp.targetPort == null)
                        ? null
                        : new AssetEndpoints.ExposeHint("port-forward", firstPodName, sp.targetPort);
                    out.add(new AssetEndpoints.Endpoint(
                        label + " (in-cluster only)",
                        svc.clusterIp != null ? "http://" + svc.clusterIp + ":" + sp.port + "/" : "(no clusterIP)",
                        "k8s-cluster",
                        "Service " + svc.name + " ClusterIP",
                        svc.hasReadyEndpoint, false, hint));
                }
            }
        }

        // GitHub repo (external)
        if (asset.repoUrl() != null && !asset.repoUrl().isBlank()) {
            out.add(new AssetEndpoints.Endpoint(
                "GitHub repository", asset.repoUrl(), "external",
                "asset.repoUrl", true, true, null));
        }

        // Dependents — host-accessible URLs from runtime-edge producers, prefixed so it's clear
        // they belong to a different asset. 1-hop only to keep the list digestible; users can
        // drill into a specific producer's endpoint card to see its full surface.
        java.util.Set<String> seenProducers = new java.util.HashSet<>();
        for (io.devportal.asset.Dependency d : assets.findDependenciesOf(assetId)) {
            if (!"runtime".equals(d.kind())) continue;
            if (!seenProducers.add(d.producerId())) continue;
            for (PortReservation r : ports.findByAssetAndScope(d.producerId(), "local")) {
                out.add(new AssetEndpoints.Endpoint(
                    "Dependent " + d.producerId() + " — " + r.slotName(),
                    "http://localhost:" + r.port() + "/",
                    "dependent-local",
                    "runtime edge -> " + d.producerId() + " (port registry local)",
                    dockerContainerRunning(d.producerId()), true, null));
            }
            for (PortReservation r : ports.findByAssetAndScope(d.producerId(), "k8s-nodeport")) {
                out.add(new AssetEndpoints.Endpoint(
                    "Dependent " + d.producerId() + " — " + r.slotName() + " (NodePort)",
                    "http://localhost:" + r.port() + "/",
                    "dependent-k8s-nodeport",
                    "runtime edge -> " + d.producerId() + " (NodePort " + r.port() + ")",
                    k8sPodRunning(d.producerId()), true, null));
            }
        }

        // Ingress proxy — generated from spec.runtime.proxy at apply time. Emit a row even when the
        // ingress hasn't been applied yet so the user can see the path their manifest will claim.
        Manifest.Proxy proxy = readProxyConfig(assetId);
        if (proxy != null && proxy.path() != null && !proxy.path().isBlank()) {
            String host = (proxy.host() != null && !proxy.host().isBlank()) ? proxy.host() : "localhost";
            String url = "http://" + host + proxy.path();
            boolean live = k8sPodRunning(assetId);
            String origin = "spec.runtime.proxy"
                + (proxy.host() == null || proxy.host().isBlank() ? " (any host — also reachable as router/external IP)" : "");
            out.add(new AssetEndpoints.Endpoint(
                "Ingress proxy — " + proxy.path(),
                url, "ingress-proxy", origin, live, true, null));
        }

        return new AssetEndpoints(assetId, out);
    }

    private Manifest.Proxy readProxyConfig(String assetId) {
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

    /** Inferred role string for an endpoint, e.g. "Web UI", "API", "WebSocket", "metrics". */
    private static String inferRole(String svcName, String portName, Integer targetPort, String imageHints) {
        String s = (svcName == null ? "" : svcName.toLowerCase());
        String p = (portName == null ? "" : portName.toLowerCase());
        String h = (imageHints == null ? "" : imageHints.toLowerCase());
        if (p.contains("metric") || (targetPort != null && (targetPort == 9090 || targetPort == 9100))) return "metrics";
        if (p.contains("ws") || p.contains("websocket") || s.contains("ws-") || s.contains("-ws") || s.contains("gateway")) return "WebSocket";
        if (h.contains("nginx") || h.contains("httpd") || h.contains("caddy") || s.contains("frontend") || s.contains("ui") || s.contains("web")) return "Web UI";
        if (s.contains("proxy") || s.contains("gateway") || s.contains("api") || s.contains("app") || s.contains("server") || s.contains("backend")) return "API";
        if (s.contains("admin") || s.contains("airflow") || s.contains("console")) return "Admin UI";
        return null;
    }

    /** True when the image / role looks like a Spring Boot deployment (so /actuator/* paths are useful). */
    private static boolean looksLikeSpring(String role, String imageHints) {
        if (imageHints == null) return false;
        String h = imageHints.toLowerCase();
        if (h.contains("temurin") || h.contains("openjdk") || h.contains("eclipse-temurin") || h.contains("bellsoft")) return true;
        return false;
    }

    private record K8sServicePort(String name, int port, Integer targetPort, Integer nodePort) {}

    private static class K8sServiceInfo {
        String name;
        String namespace;
        String type;
        String clusterIp;
        String selector;             // joined "k=v,k=v" form, used for pod lookups
        String imageHints;           // image refs of pods matching the selector, comma-joined
        boolean hasReadyEndpoint;
        List<K8sServicePort> ports = new ArrayList<>();
    }

    /**
     * List Services that belong to the asset. We can't rely on a label like {@code app=<id>}
     * because manifests typically label by service name (e.g. {@code app=social-app}). Instead,
     * read the Service names from the asset's k8s manifest tree and look each up in the cluster.
     */
    private List<K8sServiceInfo> listAssetServices(String assetId) {
        List<K8sServiceInfo> result = new ArrayList<>();
        java.util.Set<String> svcNames = readServiceNamesFromManifests(assetId);
        if (svcNames.isEmpty()) return result;

        // Resolve namespace via the k8s service so we use the same logic as apply.
        String ns;
        try { ns = k8sNamespace(assetId); } catch (Exception e) { return result; }

        try {
            ProcResult r = exec("kubectl", "get", "services", "-n", ns, "-o", "json");
            if (r == null || r.exitCode != 0) return result;
            JsonNode root = json.readTree(r.output);
            JsonNode items = root.path("items");
            if (!items.isArray()) return result;
            for (JsonNode svc : items) {
                String name = svc.path("metadata").path("name").asText("");
                if (!svcNames.contains(name)) continue;
                K8sServiceInfo info = new K8sServiceInfo();
                info.name = name;
                info.namespace = ns;
                info.type = svc.path("spec").path("type").asText("ClusterIP");
                JsonNode cip = svc.path("spec").path("clusterIP");
                info.clusterIp = (!cip.isMissingNode() && !"None".equals(cip.asText(null))) ? cip.asText(null) : null;
                info.selector = joinSelector(svc.path("spec").path("selector"));
                info.imageHints = imageHintsForSelector(ns, info.selector);
                info.hasReadyEndpoint = endpointsReady(ns, name);
                JsonNode portsArr = svc.path("spec").path("ports");
                if (portsArr.isArray()) {
                    for (JsonNode pn : portsArr) {
                        info.ports.add(new K8sServicePort(
                            pn.path("name").asText(null),
                            pn.path("port").asInt(0),
                            pn.has("targetPort") && pn.path("targetPort").canConvertToInt()
                                ? pn.path("targetPort").asInt() : null,
                            pn.has("nodePort") && !pn.path("nodePort").isNull() ? pn.path("nodePort").asInt() : null
                        ));
                    }
                }
                result.add(info);
            }
        } catch (Exception e) {
            log.debug("listAssetServices({}): {}", assetId, e.getMessage());
        }
        return result;
    }

    private java.util.Set<String> readServiceNamesFromManifests(String assetId) {
        java.util.Set<String> names = new java.util.HashSet<>();
        try {
            java.nio.file.Path src = k8s.resolveK8sPath(assetId);
            try (var stream = java.nio.file.Files.walk(src)) {
                stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return (n.endsWith(".yaml") || n.endsWith(".yml"))
                            && !n.equals("kustomization.yaml") && !n.equals("kustomization.yml");
                    })
                    .forEach(p -> collectServiceNames(p, names));
            }
        } catch (Exception ignored) {}
        return names;
    }

    private void collectServiceNames(java.nio.file.Path file, java.util.Set<String> out) {
        try (var parser = yaml.getFactory().createParser(java.nio.file.Files.newBufferedReader(file))) {
            com.fasterxml.jackson.databind.MappingIterator<JsonNode> it =
                yaml.readerFor(JsonNode.class).readValues(parser);
            while (it.hasNext()) {
                JsonNode doc = it.next();
                if (doc == null || !doc.isObject()) continue;
                if ("Service".equalsIgnoreCase(doc.path("kind").asText())) {
                    String name = doc.path("metadata").path("name").asText(null);
                    if (name != null && !name.isBlank()) out.add(name);
                }
            }
        } catch (IOException ignored) {}
    }

    private String k8sNamespace(String assetId) {
        // Reuse K8sService.effectiveNamespace via the same logic — but we don't have a direct
        // reference, so duplicate the cheap part: read asset.k8sNamespace, fall back to id.
        var a = assets.findById(assetId).orElse(null);
        if (a != null && a.k8sNamespace() != null && !a.k8sNamespace().isBlank()) return a.k8sNamespace();
        return assetId;
    }

    private static String joinSelector(JsonNode selectorNode) {
        if (!selectorNode.isObject()) return "";
        var sb = new StringBuilder();
        var fields = selectorNode.fields();
        boolean first = true;
        while (fields.hasNext()) {
            var e = fields.next();
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue().asText());
            first = false;
        }
        return sb.toString();
    }

    private String imageHintsForSelector(String ns, String selector) {
        if (selector == null || selector.isBlank()) return "";
        try {
            ProcResult r = exec("kubectl", "get", "pods", "-n", ns, "-l", selector,
                "-o", "jsonpath={.items[*].spec.containers[*].image}");
            if (r != null && r.exitCode == 0) return r.output.trim().replace(' ', ',');
        } catch (Exception ignored) {}
        return "";
    }

    private boolean endpointsReady(String ns, String svcName) {
        try {
            ProcResult r = exec("kubectl", "get", "endpoints", svcName, "-n", ns,
                "-o", "jsonpath={.subsets[*].addresses[*].ip}");
            return r != null && r.exitCode == 0 && !r.output.trim().isEmpty();
        } catch (Exception e) { return false; }
    }

    private String firstRunningPodNameForLabel(String ns, String selector) {
        if (selector == null || selector.isBlank()) return null;
        try {
            ProcResult r = exec("kubectl", "get", "pods", "-n", ns, "-l", selector,
                "-o", "jsonpath={.items[?(@.status.phase=='Running')].metadata.name}");
            String s = r.output.trim();
            if (s.isEmpty()) return null;
            return s.split("\\s+")[0];
        } catch (Exception e) { return null; }
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
