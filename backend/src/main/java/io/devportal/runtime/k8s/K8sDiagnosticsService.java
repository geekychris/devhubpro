package io.devportal.runtime.k8s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.runtime.k8s.dto.K8sDiagnostics;
import io.devportal.workspace.WorkspaceService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cross-references manifests, the local docker image store, and live pod state to produce a
 * deduplicated punch list of "what's wrong with this deployment".
 *
 * <p>Intentionally best-effort: any kubectl/docker call that fails is silently dropped — we'd
 * rather under-report than crash the diagnostics endpoint when the cluster is sick. Each finding
 * carries a stable {@code code} so the UI / Claude can group or suppress.
 */
@Service
public class K8sDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(K8sDiagnosticsService.class);

    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final K8sService k8s;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public K8sDiagnosticsService(AssetRepository assets, WorkspaceService workspace, K8sService k8s) {
        this.assets = assets;
        this.workspace = workspace;
        this.k8s = k8s;
    }

    public K8sDiagnostics diagnose(String assetId) throws IOException, InterruptedException {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        String ns = k8s.effectiveNamespace(assetId);
        List<K8sDiagnostics.Finding> findings = new ArrayList<>();

        // 1) Pre-build the manifest image map: tag -> imagePullPolicy declared in this asset's
        //    manifests. Used to flag mismatches against declared-local images.
        Map<String, String> declaredImagePolicies = readManifestImagePolicies(assetId);

        // 2) Source of truth for "this image should be locally built": the asset's
        //    spec.docker.images declarations (this asset and runtime-edge producers).
        Set<String> declaredLocalImages = new HashSet<>(readDeclaredLocalImageTags(assetId));

        // 3) Local docker image inventory: which tags exist on the host's docker daemon. Stored
        //    normalized so 'docker.io/foo:tag' and 'foo:tag' match.
        Set<String> localImages = listLocalImages().stream()
            .map(K8sDiagnosticsService::normalizeTag)
            .collect(java.util.stream.Collectors.toSet());

        // 4) Cross-reference declared-local images against the local docker store and policies.
        //    Public images (postgres:16, redis:7-alpine, etc.) are intentionally not flagged —
        //    kubelet pulling them from Docker Hub is correct.
        for (var e : declaredImagePolicies.entrySet()) {
            String image = e.getKey();
            String policy = e.getValue();          // null when not declared (kubelet default applies)
            boolean declaredLocal = declaredLocalImages.contains(normalizeTag(image));
            if (!declaredLocal) continue;          // skip public images
            boolean haveLocally = localImages.contains(normalizeTag(image));
            if (!"Never".equalsIgnoreCase(policy) && haveLocally) {
                findings.add(new K8sDiagnostics.Finding(
                    "warn", "image-pull-policy-mismatch",
                    "Manifest", null, image,
                    "Image " + image + " is declared as a local build and exists in the host store, but the manifest doesn't set imagePullPolicy: Never; kubelet will try to pull from a registry.",
                    "Add 'imagePullPolicy: Never' to the container spec in the corresponding k8s manifest, then re-apply.",
                    List.of(), null, null, null, null
                ));
            }
            if ("Never".equalsIgnoreCase(policy) && !haveLocally) {
                findings.add(new K8sDiagnostics.Finding(
                    "error", "missing-image-locally",
                    "Manifest", null, image,
                    "Image " + image + " has imagePullPolicy: Never but is not in the local docker image store.",
                    "Build it via 'Build local images'. If the Dockerfile path is wrong or missing, fix the spec.docker.images entry.",
                    List.of(), null, null, null, null
                ));
            }
        }

        // 4) Live pod state. Pull pods labeled with this asset, plus all pods in the namespace
        //    when the namespace IS the asset (typical multi-tenant setup like worksphere).
        JsonNode pods = listPods(ns, assetId);
        int total = 0, running = 0, pending = 0, broken = 0;
        if (pods.isArray()) {
            for (JsonNode pod : pods) {
                total++;
                String name = pod.path("metadata").path("name").asText("");
                String phase = pod.path("status").path("phase").asText("");
                if ("Running".equals(phase)) running++;
                else if ("Pending".equals(phase)) pending++;
                else broken++;
                addPodFindings(pod, name, ns, declaredLocalImages, findings);
                if ("Pending".equals(phase)) addPendingFindings(pod, name, ns, findings);
            }
        }

        // Dedupe: when a pod-level err-image-never-pull exists for an image, the manifest-level
        // missing-image-locally finding for the same image is redundant — the pod finding
        // already says everything the manifest one does.
        Set<String> imagesWithPodMissing = findings.stream()
            .filter(f -> "err-image-never-pull".equals(f.code()))
            .map(K8sDiagnostics.Finding::image)
            .filter(java.util.Objects::nonNull)
            .map(K8sDiagnosticsService::normalizeTag)
            .collect(java.util.stream.Collectors.toSet());
        findings.removeIf(f ->
            "missing-image-locally".equals(f.code())
                && f.image() != null
                && imagesWithPodMissing.contains(normalizeTag(f.image())));

        int err = (int) findings.stream().filter(f -> "error".equals(f.severity())).count();
        int warn = (int) findings.stream().filter(f -> "warn".equals(f.severity())).count();
        return new K8sDiagnostics(assetId, ns, java.time.Instant.now(),
            new K8sDiagnostics.Summary(total, running, pending, broken, err, warn),
            findings);
    }

    // ---------- helpers ----------

    /** Walk pod containerStatuses to identify image-pull / crash issues. */
    private void addPodFindings(JsonNode pod, String podName, String ns,
                                Set<String> declaredLocalImages,
                                List<K8sDiagnostics.Finding> out) {
        java.time.Instant podCreated = parseInstant(pod.path("metadata").path("creationTimestamp").asText(null));
        Long podAge = ageSeconds(podCreated);

        JsonNode statuses = pod.path("status").path("containerStatuses");
        if (!statuses.isArray()) statuses = pod.path("status").path("initContainerStatuses");
        for (JsonNode cs : statuses) {
            String image = cs.path("image").asText("");
            JsonNode waiting = cs.path("state").path("waiting");
            JsonNode terminated = cs.path("state").path("terminated");
            JsonNode lastTerm = cs.path("lastState").path("terminated");
            int restartCount = cs.path("restartCount").asInt(0);
            // For crashing containers the most informative time is the lastState termination.
            java.time.Instant lastTransition = parseInstant(lastTerm.path("finishedAt").asText(null));
            if (lastTransition == null) {
                lastTransition = parseInstant(terminated.path("finishedAt").asText(null));
            }

            if (waiting.isObject()) {
                String reason = waiting.path("reason").asText("");
                String msg = waiting.path("message").asText("");
                switch (reason) {
                    case "ImagePullBackOff", "ErrImagePull" -> out.add(new K8sDiagnostics.Finding(
                        "error", "image-pull-backoff",
                        "Pod/" + podName, podName, image,
                        "Cannot pull image " + image + ": " + (msg.isBlank() ? reason : msg),
                        declaredLocalImages.contains(normalizeTag(image))
                            ? "This image is declared as a local build (spec.docker.images). Build it via 'Build local images' AND set imagePullPolicy: Never on the container in the manifest."
                            : "Public image — check the tag is valid and try 'docker pull " + image + "' from the host. Manifest unknown errors usually mean the tag doesn't exist on the registry.",
                        List.of(), podCreated, podAge, null, null
                    ));
                    case "ErrImageNeverPull" -> out.add(new K8sDiagnostics.Finding(
                        "error", "err-image-never-pull",
                        "Pod/" + podName, podName, image,
                        "Image " + image + " has imagePullPolicy: Never but isn't present in Rancher Desktop's image store.",
                        "Build it via 'Build local images'. Confirm the Dockerfile path in spec.docker.images.",
                        List.of(), podCreated, podAge, null, null
                    ));
                    case "CrashLoopBackOff" -> out.add(new K8sDiagnostics.Finding(
                        "error", "crash-loop",
                        "Pod/" + podName, podName, image,
                        "Container is crashing (" + restartCount + " restart" + (restartCount == 1 ? "" : "s") + ").",
                        "Inspect logs below; common causes: missing config, dependency not yet up, init script doesn't exec the daemon.",
                        tailLogs(ns, podName, cs.path("name").asText("")),
                        podCreated, podAge, restartCount, lastTransition
                    ));
                    case "CreateContainerConfigError", "CreateContainerError" -> out.add(new K8sDiagnostics.Finding(
                        "error", "create-error",
                        "Pod/" + podName, podName, image,
                        "Container can't start: " + msg,
                        "Check ConfigMaps / Secrets referenced by this Deployment.",
                        List.of(), podCreated, podAge, null, null
                    ));
                    default -> {} // not interesting
                }
            }
            if (terminated.isObject()) {
                int exit = terminated.path("exitCode").asInt(0);
                if (exit != 0) {
                    out.add(new K8sDiagnostics.Finding(
                        "error", "error-exit",
                        "Pod/" + podName, podName, image,
                        "Container exited with code " + exit + " (reason " + terminated.path("reason").asText("?") + ").",
                        "Inspect logs below.",
                        tailLogs(ns, podName, cs.path("name").asText("")),
                        podCreated, podAge, restartCount, lastTransition
                    ));
                }
            }
        }
    }

    private static java.time.Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return java.time.Instant.parse(iso); }
        catch (java.time.format.DateTimeParseException e) { return null; }
    }

    private static Long ageSeconds(java.time.Instant t) {
        if (t == null) return null;
        return Math.max(0L, java.time.Duration.between(t, java.time.Instant.now()).getSeconds());
    }

    /** For Pending pods, surface the FailedScheduling event reason (CPU, PVC, etc). */
    private void addPendingFindings(JsonNode pod, String podName, String ns,
                                    List<K8sDiagnostics.Finding> out) {
        ProcResult ev = exec("kubectl", "get", "events", "-n", ns,
            "--field-selector", "involvedObject.name=" + podName,
            "-o", "jsonpath={range .items[*]}{.reason}\\t{.message}\\n{end}");
        if (ev == null || ev.exitCode != 0) return;
        for (String line : ev.output.split("\n")) {
            if (!line.startsWith("FailedScheduling")) continue;
            String msg = line.substring("FailedScheduling".length()).trim();
            String code = msg.toLowerCase().contains("cpu") ? "pending-cpu"
                : msg.toLowerCase().contains("memory") ? "pending-memory"
                : msg.toLowerCase().contains("volume") ? "pending-pvc"
                : "pending-other";
            String hint = code.equals("pending-cpu")
                ? "Bump Rancher Desktop CPU allocation (Settings → Virtual Machine), or trim replicas to 1."
                : "Inspect the FailedScheduling event with 'kubectl describe pod " + podName + " -n " + ns + "'.";
            out.add(new K8sDiagnostics.Finding(
                "warn", code, "Pod/" + podName, podName, null, msg, hint,
                List.of(), null, null, null, null
            ));
            break; // one finding per pod is enough
        }
    }

    /** Read the asset's k8s/ tree, return image -> imagePullPolicy (or null) per container ref. */
    private Map<String, String> readManifestImagePolicies(String assetId) {
        Map<String, String> out = new HashMap<>();
        try {
            Path src = k8s.resolveK8sPath(assetId);
            try (var stream = Files.walk(src)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return (n.endsWith(".yaml") || n.endsWith(".yml"))
                            && !n.equals("kustomization.yaml") && !n.equals("kustomization.yml");
                    })
                    .forEach(p -> collectImages(p, out));
            }
        } catch (IOException e) {
            log.debug("readManifestImagePolicies({}) skipped: {}", assetId, e.getMessage());
        }
        return out;
    }

    private void collectImages(Path file, Map<String, String> out) {
        try (var parser = yaml.getFactory().createParser(Files.newBufferedReader(file))) {
            com.fasterxml.jackson.databind.MappingIterator<JsonNode> it =
                yaml.readerFor(JsonNode.class).readValues(parser);
            while (it.hasNext()) {
                JsonNode doc = it.next();
                if (doc == null || !doc.isObject()) continue;
                JsonNode containers = doc.path("spec").path("template").path("spec").path("containers");
                JsonNode initContainers = doc.path("spec").path("template").path("spec").path("initContainers");
                walkContainers(containers, out);
                walkContainers(initContainers, out);
            }
        } catch (IOException ignored) {}
    }

    private static void walkContainers(JsonNode containers, Map<String, String> out) {
        if (!containers.isArray()) return;
        for (JsonNode c : containers) {
            String image = c.path("image").asText(null);
            if (image == null || image.isBlank()) continue;
            String policy = c.path("imagePullPolicy").asText(null);
            // Last writer wins is fine here — we just need to know "is Never declared anywhere".
            out.merge(image, policy == null ? "" : policy,
                (a, b) -> "Never".equalsIgnoreCase(a) ? a : b);
        }
    }

    private Set<String> listLocalImages() {
        Set<String> out = new HashSet<>();
        try {
            ProcResult r = exec("docker", "image", "ls", "--format", "{{.Repository}}:{{.Tag}}");
            if (r != null && r.exitCode == 0) {
                for (String line : r.output.split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.endsWith(":<none>")) out.add(t);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Strip the implicit registry prefix and ":latest" tag so two refs that mean the same image
     *  (e.g. {@code docker.io/foo:latest} vs {@code foo:latest}, or {@code foo} vs
     *  {@code foo:latest}) compare equal. */
    private static String normalizeTag(String image) {
        if (image == null) return "";
        String s = image.trim();
        if (s.startsWith("docker.io/")) s = s.substring("docker.io/".length());
        if (s.startsWith("index.docker.io/")) s = s.substring("index.docker.io/".length());
        if (!s.contains(":")) s = s + ":latest";
        return s;
    }

    /** Tags from spec.docker.images for this asset and (best-effort) its runtime producers.
     *  These are the images we actually expect to be locally built; everything else is public. */
    private List<String> readDeclaredLocalImageTags(String rootAsset) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(rootAsset);
        while (!queue.isEmpty()) {
            String aid = queue.poll();
            if (!seen.add(aid)) continue;
            Path manifest = workspace.workspaceFor(aid).resolve("devportal.yaml");
            if (Files.exists(manifest)) {
                try {
                    JsonNode doc = yaml.readTree(Files.readString(manifest));
                    JsonNode imgs = doc.path("spec").path("docker").path("images");
                    if (imgs.isArray()) {
                        for (JsonNode i : imgs) {
                            String tag = i.path("tag").asText(null);
                            if (tag != null && !tag.isBlank()) out.add(normalizeTag(tag));
                        }
                    }
                } catch (IOException ignored) {}
            }
            // Walk runtime producers so a consumer's diagnostics catches its dependencies' images.
            for (var d : assets.findDependenciesOf(aid)) {
                if ("runtime".equals(d.kind())) queue.add(d.producerId());
            }
        }
        return out;
    }

    private JsonNode listPods(String ns, String assetId) {
        // Try label selector first (typical "app=<id>" convention from scaffolder); fall back to
        // namespace-wide so we still see worksphere pods even when they aren't labeled.
        ProcResult labeled = exec("kubectl", "get", "pods", "-n", ns,
            "-l", "app=" + assetId, "-o", "json");
        if (labeled != null && labeled.exitCode == 0) {
            try {
                JsonNode root = json.readTree(labeled.output);
                JsonNode items = root.path("items");
                if (items.isArray() && items.size() > 0) return items;
            } catch (IOException ignored) {}
        }
        ProcResult all = exec("kubectl", "get", "pods", "-n", ns, "-o", "json");
        if (all != null && all.exitCode == 0) {
            try {
                return json.readTree(all.output).path("items");
            } catch (IOException ignored) {}
        }
        return json.createArrayNode();
    }

    /** Last 12 lines of the container's log; safe on missing pods/containers. */
    private List<String> tailLogs(String ns, String podName, String container) {
        if (podName == null || podName.isBlank()) return List.of();
        List<String> args = new ArrayList<>(List.of("kubectl", "logs", "-n", ns, podName,
            "--tail", "12"));
        if (!container.isBlank()) {
            args.add("-c");
            args.add(container);
        }
        ProcResult r = exec(args.toArray(String[]::new));
        if (r == null || r.exitCode != 0 || r.output.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : r.output.split("\n")) {
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    private record ProcResult(int exitCode, String output) {}

    private static ProcResult exec(String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return new ProcResult(p.waitFor(), sb.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
