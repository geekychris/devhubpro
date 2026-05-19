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
import java.util.Set;
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
        return apply(assetId, false);
    }

    public Map<String, Object> apply(String assetId, boolean force) throws IOException, InterruptedException {
        return apply(assetId, force, 0);
    }

    public Map<String, Object> apply(String assetId, boolean force, int waitSec) throws IOException, InterruptedException {
        Asset asset = loadAsset(assetId);
        Path manifestPath = resolveK8sPath(assetId);
        // Pre-flight: assets that declare spec.docker.images expect those images to be built
        // locally before apply — kubectl will set their pods to ErrImageNeverPull otherwise.
        // Fail fast with a structured pointer at the spinup chain so the user doesn't have to
        // wait for the cluster to expose the failure.
        preflightDeclaredImages(asset, force);
        // Reconcile the asset's namespace with what the YAML actually declares BEFORE rendering.
        // If the user (or scaffolder) wrote `metadata.namespace: worksphere` in the manifests but
        // the asset is still on its default (k8sNamespace == assetId), kubectl will reject the
        // apply with "namespace from object does not match -n flag". Trust the YAML and shift the
        // asset to match — that's the namespace the workloads actually live in.
        asset = reconcileNamespaceFromManifests(asset, manifestPath);
        Path renderedDir = renderForApply(asset, manifestPath);
        String ns = effectiveNamespace(asset.id());
        ensureNamespace(ns);                    // auto-create if missing
        validateProxyPathUnique(asset.id(), renderedDir);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", asset.id());
        result.put("namespace", ns);
        result.put("manifestPath", manifestPath.toString());
        result.put("renderedDir", renderedDir.toString());
        // Pre-apply manifest lint — catches unbacked ConfigMap/Secret/PVC refs and access-mode
        // mismatches that would otherwise leave pods in FailedMount or Pending state with no
        // explicit signal. Errors block apply unless force=true; warnings always pass through.
        java.util.List<K8sManifestLinter.LintIssue> lintIssues =
            K8sManifestLinter.lint(renderedDir, yaml, buildClusterProbe(ns));
        result.put("lintIssues", lintIssues);
        if (!force) {
            java.util.List<K8sManifestLinter.LintIssue> errors = lintIssues.stream()
                .filter(i -> "error".equals(i.severity())).toList();
            if (!errors.isEmpty()) {
                String summary = errors.stream()
                    .map(i -> "  - " + i.kind() + "/" + i.name() + " (" + i.namespace() + "): "
                        + i.problem() + " — " + i.suggestion())
                    .collect(java.util.stream.Collectors.joining("\n"));
                throw new io.devportal.asset.error.ConflictException(
                    "Pre-apply lint found " + errors.size() + " issue(s) that would leave pods stuck:\n"
                    + summary + "\nRetry with ?force=true to apply anyway.");
            }
        }
        ProcResult res = exec(new String[]{
            "kubectl", "apply", "-f", renderedDir.toString()
        });
        result.put("exitCode", res.exitCode);
        result.put("output", res.output);
        if (res.exitCode != 0) throw new IOException("kubectl apply failed: " + res.output);
        if (waitSec > 0) {
            result.put("readiness", waitForReady(ns, waitSec));
        }
        return result;
    }

    /**
     * After a successful apply, wait up to {@code waitSec} for each Deployment / StatefulSet /
     * DaemonSet in {@code namespace} to reach Ready, then snapshot pod health. Returns a
     * structured per-workload + per-unhealthy-pod report so the UI / API caller can see what
     * progressed and what's stuck without having to shell out to kubectl themselves.
     *
     * <p>Bounded by {@code waitSec} per workload (worst case waitSec*workloads, but rollout
     * status returns as soon as a workload is Ready). Default callers pass 60–90 seconds.
     */
    public Map<String, Object> waitForReady(String namespace, int waitSec) {
        java.util.Map<String, Object> out = new LinkedHashMap<>();
        out.put("namespace", namespace);
        out.put("timeoutSec", waitSec);
        java.util.List<java.util.Map<String, Object>> workloads = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> unhealthyPods = new java.util.ArrayList<>();
        int total = 0;
        int ready = 0;
        try {
            for (String kind : java.util.List.of("deployment", "statefulset", "daemonset")) {
                ProcResult list = exec(new String[]{"kubectl", "-n", namespace, "get", kind,
                    "-o", "jsonpath={.items[*].metadata.name}"});
                if (list.exitCode != 0 || list.output.isBlank()) continue;
                for (String name : list.output.trim().split("\\s+")) {
                    if (name.isBlank()) continue;
                    total++;
                    java.util.Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kind", kind);
                    row.put("name", name);
                    ProcResult roll = exec(new String[]{"kubectl", "-n", namespace,
                        "rollout", "status", kind + "/" + name,
                        "--timeout=" + waitSec + "s"});
                    boolean isReady = roll.exitCode == 0;
                    row.put("ready", isReady);
                    row.put("message", roll.output.trim().lines()
                        .reduce((a, b) -> b).orElse(""));   // last line
                    if (isReady) ready++;
                    workloads.add(row);
                }
            }
            // Snapshot pods that aren't Ready so the report surfaces the *reason* (ErrImagePull,
            // CrashLoopBackOff, init-container failure, etc.) — rollout status alone says "timed
            // out" without naming the failing container.
            ProcResult pods = exec(new String[]{"kubectl", "-n", namespace, "get", "pods", "-o", "json"});
            if (pods.exitCode == 0) {
                com.fasterxml.jackson.databind.JsonNode root = json.readTree(pods.output);
                for (com.fasterxml.jackson.databind.JsonNode pod : root.path("items")) {
                    String name = pod.path("metadata").path("name").asText();
                    String phase = pod.path("status").path("phase").asText();
                    int readyCount = 0;
                    int totalCount = 0;
                    String reason = null;
                    for (com.fasterxml.jackson.databind.JsonNode cs : pod.path("status").path("containerStatuses")) {
                        totalCount++;
                        if (cs.path("ready").asBoolean(false)) readyCount++;
                        if (reason == null) {
                            com.fasterxml.jackson.databind.JsonNode waiting = cs.path("state").path("waiting");
                            if (!waiting.isMissingNode()) {
                                reason = waiting.path("reason").asText(null);
                            }
                            com.fasterxml.jackson.databind.JsonNode term = cs.path("lastState").path("terminated");
                            if (reason == null && !term.isMissingNode()) {
                                reason = term.path("reason").asText(null);
                            }
                        }
                    }
                    boolean podOk = "Running".equals(phase) && readyCount == totalCount && totalCount > 0;
                    if (podOk || "Succeeded".equals(phase)) continue;
                    java.util.Map<String, Object> p = new LinkedHashMap<>();
                    p.put("name", name);
                    p.put("phase", phase);
                    p.put("ready", readyCount + "/" + totalCount);
                    p.put("reason", reason == null ? "" : reason);
                    unhealthyPods.add(p);
                }
            }
        } catch (Exception e) {
            out.put("error", "readiness check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        out.put("workloadsTotal", total);
        out.put("workloadsReady", ready);
        out.put("workloads", workloads);
        out.put("unhealthyPods", unhealthyPods);
        out.put("allReady", total > 0 && ready == total && unhealthyPods.isEmpty());
        return out;
    }

    /**
     * For every Deployment in {@code namespace} whose pod-spec containers reference an image
     * in {@code imageTags}, run {@code kubectl rollout restart}. Returns the list of restarted
     * deployment names. Used by the spinup chain so a freshly rebuilt {@code :latest} image
     * actually reaches pods — {@code kubectl apply} alone is a no-op when only the image bytes
     * change (and with {@code imagePullPolicy: Never}, the kubelet won't re-pull either).
     *
     * <p>Tags are matched flexibly: a deployment referencing {@code docker.io/worksphere/x:latest}
     * matches a built tag of {@code worksphere/x:latest} (strip leading {@code docker.io/}).
     */
    public List<String> rolloutRestartDeploymentsUsingImages(String namespace, Set<String> imageTags) {
        List<String> restarted = new ArrayList<>();
        if (imageTags == null || imageTags.isEmpty()) return restarted;
        try {
            ProcResult list = exec(new String[]{"kubectl", "-n", namespace,
                "get", "deployments", "-o", "json"});
            if (list.exitCode != 0) {
                log.warn("Could not list deployments in {} for rollout restart: {}", namespace, list.output);
                return restarted;
            }
            com.fasterxml.jackson.databind.JsonNode root = json.readTree(list.output);
            for (com.fasterxml.jackson.databind.JsonNode dep : root.path("items")) {
                String name = dep.path("metadata").path("name").asText();
                boolean match = false;
                for (com.fasterxml.jackson.databind.JsonNode c :
                        dep.path("spec").path("template").path("spec").path("containers")) {
                    String img = normaliseTag(c.path("image").asText(""));
                    if (img.isEmpty()) continue;
                    if (imageTags.stream().map(this::normaliseTag).anyMatch(img::equals)) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
                ProcResult roll = exec(new String[]{"kubectl", "-n", namespace,
                    "rollout", "restart", "deployment/" + name});
                if (roll.exitCode == 0) {
                    restarted.add(name);
                } else {
                    log.warn("rollout restart failed for {}/{}: {}", namespace, name, roll.output);
                }
            }
        } catch (Exception e) {
            log.warn("rolloutRestartDeploymentsUsingImages failed for {}: {}", namespace, e.getMessage());
        }
        return restarted;
    }

    private String normaliseTag(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.startsWith("docker.io/")) t = t.substring("docker.io/".length());
        return t;
    }

    public Map<String, Object> delete(String assetId) throws IOException, InterruptedException {
        Asset asset = loadAsset(assetId);
        Path manifestPath = resolveK8sPath(assetId);
        asset = reconcileNamespaceFromManifests(asset, manifestPath);
        Path renderedDir = renderForApply(asset, manifestPath);
        String ns = effectiveNamespace(asset.id());
        ProcResult res = exec(new String[]{
            "kubectl", "delete", "-f", renderedDir.toString(), "--ignore-not-found=true"
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", asset.id());
        result.put("namespace", ns);
        result.put("exitCode", res.exitCode);
        result.put("output", res.output);
        return result;
    }

    /**
     * Scan the source manifest tree for declared {@code metadata.namespace} values. If the asset
     * is still on its default namespace (k8sNamespace == assetId, the value insert() wrote) and
     * every manifest that declares one agrees on a single non-default namespace, shift the asset
     * to that namespace and return the updated record. Otherwise return the asset unchanged.
     */
    /** Cluster-state probe for {@link K8sManifestLinter}. Uses kubectl to check ref existence. */
    K8sManifestLinter.ClusterProbe buildClusterProbe(String defaultNs) {
        return new K8sManifestLinter.ClusterProbe() {
            @Override public boolean configMapExists(String namespace, String name) {
                return resourceExists("configmap", namespace, name);
            }
            @Override public boolean secretExists(String namespace, String name) {
                return resourceExists("secret", namespace, name);
            }
            @Override public boolean pvcExists(String namespace, String name) {
                return resourceExists("pvc", namespace, name);
            }
            @Override public java.util.List<com.fasterxml.jackson.databind.JsonNode> allPersistentVolumes() {
                try {
                    ProcResult r = exec(new String[]{"kubectl", "get", "pv", "-o", "json"});
                    if (r.exitCode != 0) return java.util.List.of();
                    com.fasterxml.jackson.databind.JsonNode root = json.readTree(r.output);
                    java.util.List<com.fasterxml.jackson.databind.JsonNode> out = new java.util.ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode item : root.path("items")) out.add(item);
                    return out;
                } catch (Exception e) { return java.util.List.of(); }
            }
            private boolean resourceExists(String kind, String namespace, String name) {
                if (namespace == null || namespace.isBlank()) namespace = defaultNs;
                try {
                    ProcResult r = exec(new String[]{"kubectl", "get", kind, name, "-n", namespace,
                        "--ignore-not-found", "-o", "name"});
                    return r.exitCode == 0 && !r.output.trim().isEmpty();
                } catch (Exception e) { return false; }
            }
        };
    }

    /**
     * Refuse the apply when the asset declares images under {@code spec.docker.images} but at
     * least one of those tags isn't present in the docker daemon — kubectl would otherwise
     * create pods that immediately go ErrImageNeverPull/ImagePullBackOff because the local
     * tag isn't anywhere kubelet can pull from. The corresponding {@link io.devportal.spinup.SpinupService}
     * builds them first; point the caller at that instead of silently producing broken pods.
     */
    void preflightDeclaredImages(Asset asset, boolean force) throws IOException, InterruptedException {
        if (force) return;
        Path file = workspace.workspaceFor(asset.id()).resolve("devportal.yaml");
        if (!Files.exists(file)) return;
        io.devportal.manifest.Manifest m;
        try {
            m = manifestParser.parse(Files.readString(file)).manifest();
        } catch (Exception e) {
            return;
        }
        if (m == null || m.spec() == null || m.spec().docker() == null) return;
        java.util.List<io.devportal.manifest.Manifest.Image> images = m.spec().docker().images();
        if (images == null || images.isEmpty()) return;

        java.util.List<String> missing = new java.util.ArrayList<>();
        for (io.devportal.manifest.Manifest.Image img : images) {
            if (img.tag() == null || img.tag().isBlank()) continue;
            ProcResult r = exec(new String[]{"docker", "image", "inspect", img.tag()});
            if (r.exitCode != 0) missing.add(img.tag());
        }
        if (missing.isEmpty()) return;

        throw new io.devportal.asset.error.ConflictException(
            "Asset '" + asset.id() + "' declares " + images.size() + " image(s) in spec.docker.images "
            + "but " + missing.size() + " are not present in the docker daemon: "
            + String.join(", ", missing) + ". "
            + "Run POST /api/assets/" + asset.id() + "/spinup for the full build → apply → probe chain "
            + "(the Spin Up button in the UI), or POST /api/assets/" + asset.id() + "/docker/build-images "
            + "to build them and then retry this apply. "
            + "If the images were already imported into the cluster's container runtime "
            + "out-of-band, retry with ?force=true to skip this check.");
    }

    private Asset reconcileNamespaceFromManifests(Asset asset, Path manifestPath) {
        boolean isDefault = asset.k8sNamespace() == null
            || asset.k8sNamespace().isBlank()
            || asset.id().equals(asset.k8sNamespace());
        if (!isDefault) return asset;
        java.util.Set<String> declared = K8sManifestHelpers.scanManifestNamespaces(manifestPath, yaml);
        if (declared.size() != 1) return asset;  // disagreement or none → don't touch
        String ns = declared.iterator().next();
        if (ns.equals(asset.k8sNamespace())) return asset;
        log.info("Reconciling asset {} k8sNamespace {} -> {} (from manifest metadata.namespace)",
            asset.id(), asset.k8sNamespace(), ns);
        Asset updated = new Asset(
            asset.id(), asset.name(), asset.description(), asset.owner(), asset.type(),
            asset.language(), asset.repoUrl(), asset.repoDefaultBranch(), asset.tags(),
            asset.lifecycle(), ns, asset.favorite(), asset.rating(), asset.dashboardPinned(),
            asset.createdAt(), asset.updatedAt());
        assets.update(updated);
        return updated;
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

        String fallbackNs = effectiveNamespace(asset.id());

        if (Files.isRegularFile(source)) {
            renderOneFile(source, target.resolve(source.getFileName()), bySlot, fallbackNs);
        } else {
            try (var stream = Files.walk(source)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        if (!n.endsWith(".yaml") && !n.endsWith(".yml")) return false;
                        // Skip kustomize inputs — they aren't standalone resources, and
                        // `kubectl apply -f kustomization.yaml` always errors.
                        return !n.equals("kustomization.yaml") && !n.equals("kustomization.yml");
                    })
                    .forEach(p -> {
                        Path rel = source.relativize(p);
                        Path dst = target.resolve(rel);
                        try {
                            Files.createDirectories(dst.getParent());
                            renderOneFile(p, dst, bySlot, fallbackNs);
                        } catch (IOException e) {
                            log.warn("render skipped for {}: {}", p, e.getMessage());
                        }
                    });
            }
        }
        writeIngressIfDeclared(asset, target);
        log.info("Rendered k8s manifests for {} into {} (allocations={})", asset.id(), target, bySlot);
        return target;
    }

    /**
     * If the asset's manifest declares spec.runtime.proxy, render an Ingress (and a Traefik
     * stripPrefix Middleware when stripPrefix is true) into {@code renderedDir/generated-ingress.yaml}
     * so it gets applied alongside the asset's own resources. The Ingress backend is resolved by
     * scanning the just-rendered Service docs for a port whose name matches proxy.portSlot.
     */
    private void writeIngressIfDeclared(Asset asset, Path renderedDir) {
        Manifest manifest = parseAssetManifest(asset.id());
        if (manifest == null || manifest.spec() == null || manifest.spec().runtime() == null) return;
        Manifest.Proxy p = manifest.spec().runtime().proxy();
        if (p == null) return;
        if (p.portSlot() == null || p.portSlot().isBlank()) {
            log.warn("[{}] proxy declared but portSlot missing — skipping ingress generation", asset.id());
            return;
        }
        boolean hasHost = p.host() != null && !p.host().isBlank();
        boolean hasPath = p.path() != null && !p.path().isBlank();
        if (!hasHost && !hasPath) {
            log.warn("[{}] proxy declared but neither path nor host set — skipping ingress generation", asset.id());
            return;
        }

        ServiceRef backend = findServiceForPortSlot(asset.id(), renderedDir, p.portSlot());
        if (backend == null) {
            log.warn("[{}] proxy.portSlot='{}' did not match any Service port (by name or via the port registry) in rendered manifests — skipping ingress generation",
                asset.id(), p.portSlot());
            return;
        }

        // Effective path defaults to "/" in host-only mode. stripPrefix is meaningless when the
        // upstream already sees "/" — suppress the middleware in that case.
        String effectivePath = hasPath ? p.path() : "/";
        boolean stripPrefix = (p.stripPrefix() == null || p.stripPrefix()) && !"/".equals(effectivePath);

        String ns = effectiveNamespace(asset.id());
        String mwName = asset.id() + "-stripprefix";
        StringBuilder doc = new StringBuilder();

        if (stripPrefix) {
            doc.append("apiVersion: traefik.io/v1alpha1\n");
            doc.append("kind: Middleware\n");
            doc.append("metadata:\n");
            doc.append("  name: ").append(mwName).append("\n");
            doc.append("  namespace: ").append(ns).append("\n");
            doc.append("  labels:\n");
            doc.append("    app: ").append(asset.id()).append("\n");
            doc.append("    devportal.io/managed: \"true\"\n");
            doc.append("spec:\n");
            doc.append("  stripPrefix:\n");
            doc.append("    prefixes:\n");
            doc.append("      - ").append(yamlString(effectivePath)).append("\n");
            doc.append("---\n");
        }

        doc.append("apiVersion: networking.k8s.io/v1\n");
        doc.append("kind: Ingress\n");
        doc.append("metadata:\n");
        doc.append("  name: ").append(asset.id()).append("-proxy\n");
        doc.append("  namespace: ").append(ns).append("\n");
        doc.append("  labels:\n");
        doc.append("    app: ").append(asset.id()).append("\n");
        doc.append("    devportal.io/managed: \"true\"\n");
        doc.append("  annotations:\n");
        doc.append("    devportal.io/proxy-path: ").append(yamlString(effectivePath)).append("\n");
        if (hasHost) {
            doc.append("    devportal.io/proxy-host: ").append(yamlString(p.host())).append("\n");
        }
        if (stripPrefix) {
            doc.append("    traefik.ingress.kubernetes.io/router.middlewares: ")
                .append(yamlString(ns + "-" + mwName + "@kubernetescrd")).append("\n");
        }
        doc.append("spec:\n");
        doc.append("  ingressClassName: traefik\n");
        doc.append("  rules:\n");
        doc.append("    - ");
        if (hasHost) {
            doc.append("host: ").append(yamlString(p.host())).append("\n");
            doc.append("      ");
        }
        doc.append("http:\n");
        doc.append("        paths:\n");
        doc.append("          - path: ").append(yamlString(effectivePath)).append("\n");
        doc.append("            pathType: Prefix\n");
        doc.append("            backend:\n");
        doc.append("              service:\n");
        doc.append("                name: ").append(backend.name()).append("\n");
        doc.append("                port:\n");
        doc.append("                  number: ").append(backend.port()).append("\n");

        try {
            Files.writeString(renderedDir.resolve("generated-ingress.yaml"), doc.toString());
            log.info("[{}] generated ingress {}{} -> service {}:{} (stripPrefix={})",
                asset.id(), hasHost ? p.host() : "*", effectivePath, backend.name(), backend.port(), stripPrefix);
        } catch (IOException e) {
            log.warn("[{}] failed to write generated-ingress.yaml: {}", asset.id(), e.getMessage());
        }
    }

    /** Quote any path that contains characters with YAML semantics; otherwise leave bare. */
    private static String yamlString(String s) {
        if (s == null) return "\"\"";
        if (s.matches("^[A-Za-z0-9._/-]+$")) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record ServiceRef(String name, int port) {}

    /**
     * Resolve the proxy backend Service for {@code portSlot}, trying multiple strategies
     * because slot names from the port registry don't always match Service port names:
     * <ol>
     *   <li>Service port name == portSlot (exact)</li>
     *   <li>Look up the slot's allocated nodePort in the port registry, then find a Service
     *       whose {@code nodePort} matches it</li>
     *   <li>Split portSlot at "-" and try (svcName, portName) pairs — supports the
     *       "&lt;service&gt;-&lt;port&gt;" naming convention</li>
     * </ol>
     * Returns the Service name + spec.ports[].port pair, or null if no strategy matched.
     */
    private ServiceRef findServiceForPortSlot(String assetId, Path renderedDir, String portSlot) {
        List<JsonNode> services = readRenderedServices(renderedDir);
        if (services.isEmpty()) return null;

        // Strategy 1: exact port-name match.
        for (JsonNode svc : services) {
            String svcName = svc.path("metadata").path("name").asText(null);
            if (svcName == null) continue;
            for (JsonNode pn : svc.path("spec").path("ports")) {
                if (portSlot.equals(pn.path("name").asText(null))) {
                    return new ServiceRef(svcName, pn.path("port").asInt(0));
                }
            }
        }

        // Strategy 2: registry-mediated nodePort lookup. The port registry tracks slots whose
        // names are user-chosen (e.g. "backend-http") and may not match the Service port names
        // (e.g. just "http"). We resolve via the allocated nodePort, which IS unique.
        try {
            var allocs = ports.findByAssetAndScope(assetId, "k8s-nodeport");
            for (var r : allocs) {
                if (!portSlot.equals(r.slotName())) continue;
                int wantedNodePort = r.port();
                for (JsonNode svc : services) {
                    String svcName = svc.path("metadata").path("name").asText(null);
                    if (svcName == null) continue;
                    for (JsonNode pn : svc.path("spec").path("ports")) {
                        if (pn.has("nodePort") && pn.path("nodePort").asInt(-1) == wantedNodePort) {
                            return new ServiceRef(svcName, pn.path("port").asInt(0));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Strategy 3: split "<svc>-<port>" — e.g. "backend-http" → service "backend" port "http".
        int dash = portSlot.indexOf('-');
        if (dash > 0) {
            String wantSvc = portSlot.substring(0, dash);
            String wantPort = portSlot.substring(dash + 1);
            for (JsonNode svc : services) {
                if (!wantSvc.equals(svc.path("metadata").path("name").asText(null))) continue;
                for (JsonNode pn : svc.path("spec").path("ports")) {
                    if (wantPort.equals(pn.path("name").asText(null))) {
                        return new ServiceRef(wantSvc, pn.path("port").asInt(0));
                    }
                }
                // Same service name and only one port → use it even if the port name doesn't match.
                JsonNode portsArr = svc.path("spec").path("ports");
                if (portsArr.isArray() && portsArr.size() == 1) {
                    return new ServiceRef(wantSvc, portsArr.get(0).path("port").asInt(0));
                }
            }
        }
        return null;
    }

    private List<JsonNode> readRenderedServices(Path renderedDir) {
        List<JsonNode> out = new ArrayList<>();
        try (var stream = Files.walk(renderedDir)) {
            var files = stream.filter(Files::isRegularFile)
                .filter(f -> {
                    String n = f.getFileName().toString().toLowerCase();
                    return n.endsWith(".yaml") || n.endsWith(".yml");
                })
                .toList();
            for (Path f : files) {
                try (var parser = yaml.getFactory().createParser(Files.newBufferedReader(f))) {
                    com.fasterxml.jackson.databind.MappingIterator<JsonNode> it =
                        yaml.readerFor(JsonNode.class).readValues(parser);
                    while (it.hasNext()) {
                        JsonNode doc = it.next();
                        if (doc != null && doc.isObject()
                            && "Service".equalsIgnoreCase(doc.path("kind").asText())) {
                            out.add(doc);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("readRenderedServices failed: {}", e.getMessage());
        }
        return out;
    }

    private Manifest parseAssetManifest(String assetId) {
        try {
            Path file = workspace.workspaceFor(assetId).resolve("devportal.yaml");
            if (!Files.exists(file)) return null;
            ManifestParseResult parsed = manifestParser.parse(Files.readString(file));
            return parsed.manifest();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reject if any other asset has already claimed the same (host, path) pair. Two routes only
     * conflict when both their host and path match — different paths on the same host, or the
     * same path on different hosts, are fine.
     */
    private void validateProxyPathUnique(String assetId, Path renderedDir) {
        Path generated = renderedDir.resolve("generated-ingress.yaml");
        if (!Files.exists(generated)) return;
        String[] wanted = readGeneratedProxyKey(generated);
        if (wanted == null) return;
        String wantedHost = wanted[0];
        String wantedPath = wanted[1];
        try {
            ProcResult r = exec(new String[]{
                "kubectl", "get", "ingresses", "-A", "-l", "devportal.io/managed=true", "-o", "json"
            });
            if (r.exitCode != 0) return; // best-effort
            JsonNode root = json.readTree(r.output);
            for (JsonNode item : root.path("items")) {
                String otherAsset = item.path("metadata").path("labels").path("app").asText(null);
                if (otherAsset == null || otherAsset.equals(assetId)) continue;
                JsonNode annot = item.path("metadata").path("annotations");
                String otherHost = annot.path("devportal.io/proxy-host").asText("");
                String otherPath = annot.path("devportal.io/proxy-path").asText("/");
                if (wantedHost.equals(otherHost) && wantedPath.equals(otherPath)) {
                    String label = wantedHost.isEmpty()
                        ? "Proxy path '" + wantedPath + "'"
                        : "Proxy host '" + wantedHost + "' at '" + wantedPath + "'";
                    throw new ConflictException(label
                        + " is already claimed by asset '" + otherAsset + "'");
                }
            }
        } catch (IOException | InterruptedException e) {
            log.debug("proxy path uniqueness check skipped: {}", e.getMessage());
        }
    }

    /** Read (host, path) from the generated Ingress's annotations. host is "" when unset. */
    private String[] readGeneratedProxyKey(Path generated) {
        try (var parser = yaml.getFactory().createParser(Files.newBufferedReader(generated))) {
            com.fasterxml.jackson.databind.MappingIterator<JsonNode> it =
                yaml.readerFor(JsonNode.class).readValues(parser);
            while (it.hasNext()) {
                JsonNode doc = it.next();
                if (doc == null || !doc.isObject()) continue;
                if (!"Ingress".equalsIgnoreCase(doc.path("kind").asText())) continue;
                JsonNode annot = doc.path("metadata").path("annotations");
                String p = annot.path("devportal.io/proxy-path").asText(null);
                if (p == null) return null;
                String h = annot.path("devportal.io/proxy-host").asText("");
                return new String[]{ h, p };
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void renderOneFile(Path src, Path dst, java.util.Map<String, Integer> bySlot,
                               String fallbackNs) throws IOException {
        // Multi-doc YAML: read all docs, patch Services, write back.
        com.fasterxml.jackson.databind.node.ArrayNode out = json.createArrayNode();
        try (var parser = yaml.getFactory().createParser(Files.newBufferedReader(src))) {
            com.fasterxml.jackson.databind.MappingIterator<com.fasterxml.jackson.databind.JsonNode> it
                = yaml.readerFor(com.fasterxml.jackson.databind.JsonNode.class).readValues(parser);
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode doc = it.next();
                patchService(doc, bySlot);
                K8sManifestHelpers.injectNamespaceIfMissing(doc, fallbackNs);
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
        // NodePort is only valid on type=NodePort or LoadBalancer. Patching it onto a ClusterIP
        // service makes kubectl reject the manifest with "spec.ports[].nodePort: Forbidden".
        // ClusterIP is also the default when type is omitted, so we treat both as ClusterIP.
        String type = doc.path("spec").path("type").asText("ClusterIP");
        if (!"NodePort".equalsIgnoreCase(type) && !"LoadBalancer".equalsIgnoreCase(type)) return;

        com.fasterxml.jackson.databind.JsonNode portsArray = doc.path("spec").path("ports");
        if (!portsArray.isArray()) return;
        for (int i = 0; i < portsArray.size(); i++) {
            com.fasterxml.jackson.databind.node.ObjectNode portNode =
                (com.fasterxml.jackson.databind.node.ObjectNode) portsArray.get(i);
            // If the manifest already pinned a nodePort, respect it — never overwrite. Lets users
            // hardcode different NodePorts on different Services within the same asset.
            if (portNode.has("nodePort") && !portNode.path("nodePort").isNull()) continue;
            String name = portNode.path("name").asText(null);
            // Single-allocation fallback applies only when:
            //   1) the port has no name (so we can't match by slot), and
            //   2) it's the first port of the Service (i == 0).
            // Without (1) the renderer used to incorrectly clobber named ports that didn't match
            // any slot, causing every Service in the asset to get the same nodePort.
            Integer assigned = (name != null && bySlot.containsKey(name)) ? bySlot.get(name)
                : (name == null && bySlot.size() == 1 && i == 0
                    ? bySlot.values().iterator().next() : null);
            if (assigned != null) {
                portNode.put("nodePort", assigned);
            }
        }
    }

    public K8sStatus status(String assetId) throws IOException, InterruptedException {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        String ns = effectiveNamespace(assetId);

        // Multi-component assets don't share a single app=<assetId> label across their workloads,
        // so list everything in the asset's namespace and rely on the namespace boundary.
        ProcResult pods = exec(new String[]{
            "kubectl", "get", "pods", "-n", ns, "-o", "json"
        });
        ProcResult svcs = exec(new String[]{
            "kubectl", "get", "services", "-n", ns, "-o", "json"
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
        String k9s = "k9s --namespace " + ns + " --command pods";
        String kubectlLogs = "kubectl logs -n " + ns + " -l app --all-containers=true --tail=200 -f";
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
    public void ensureNamespace(String namespace) throws IOException, InterruptedException {
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
