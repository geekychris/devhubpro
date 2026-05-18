package io.devportal.runtime.k8s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-apply manifest lint. Scans the rendered YAML for references that would cause kubectl
 * apply to produce broken pods (FailedMount, FailedScheduling, ImagePullBackOff) and reports
 * them up-front so the user fixes the manifest rather than chasing pod events.
 *
 * <p>Checks today:
 * <ul>
 *   <li>{@code volumes[].configMap.name} / {@code envFrom.configMapRef.name} /
 *       {@code env.valueFrom.configMapKeyRef.name} references resolved against ConfigMaps
 *       declared in the manifest tree or known to exist in the cluster.</li>
 *   <li>Same for Secret references.</li>
 *   <li>{@code volumes[].persistentVolumeClaim.claimName} references resolved against PVCs
 *       declared in the manifest tree or known in the cluster.</li>
 *   <li>For PVCs that use static binding ({@code storageClassName: ""}), at least one PV
 *       (manifest-declared or in the cluster) must satisfy every requested access mode and
 *       have capacity >= the PVC's requested storage. This catches the RWX-PVC-vs-RWO-PV
 *       trap that leaves the PVC Pending forever with no event explaining why.</li>
 * </ul>
 *
 * <p>Stateless; takes a {@link ClusterProbe} so unit tests can stub cluster state.
 */
final class K8sManifestLinter {

    public record LintIssue(
        String severity,    // "error" or "warning"
        String kind,        // referencing resource kind, e.g. Deployment
        String name,        // referencing resource name
        String namespace,
        String problem,     // human description
        String suggestion   // concrete next action
    ) {}

    /** Cluster-state probe for refs not in the about-to-be-applied tree. */
    public interface ClusterProbe {
        boolean configMapExists(String namespace, String name);
        boolean secretExists(String namespace, String name);
        boolean pvcExists(String namespace, String name);
        /** Every PV in the cluster — manifest-declared PVs are merged in by the linter itself. */
        List<JsonNode> allPersistentVolumes();
    }

    /** Always-empty probe — useful when you want strictly-manifest-only checking. */
    public static final ClusterProbe NO_CLUSTER = new ClusterProbe() {
        public boolean configMapExists(String n, String s) { return false; }
        public boolean secretExists(String n, String s) { return false; }
        public boolean pvcExists(String n, String s) { return false; }
        public List<JsonNode> allPersistentVolumes() { return List.of(); }
    };

    private K8sManifestLinter() {}

    /** Walk every YAML doc under {@code renderedDir} and return found issues, none if clean. */
    public static List<LintIssue> lint(Path renderedDir, ObjectMapper yaml, ClusterProbe probe) {
        List<JsonNode> docs = readAll(renderedDir, yaml);
        // Inventory what's about-to-be-applied so refs can resolve against it.
        Set<String> declaredCMs = new HashSet<>();     // "namespace/name"
        Set<String> declaredSecrets = new HashSet<>();
        Set<String> declaredPVCs = new HashSet<>();
        List<JsonNode> declaredPVs = new ArrayList<>();
        Map<String, JsonNode> pvcByKey = new HashMap<>();   // "namespace/name" -> pvc node
        for (JsonNode d : docs) {
            String kind = d.path("kind").asText("");
            String name = d.path("metadata").path("name").asText("");
            String ns = d.path("metadata").path("namespace").asText("");
            if (name.isBlank()) continue;
            switch (kind) {
                case "ConfigMap" -> declaredCMs.add(ns + "/" + name);
                case "Secret" -> declaredSecrets.add(ns + "/" + name);
                case "PersistentVolumeClaim" -> {
                    declaredPVCs.add(ns + "/" + name);
                    pvcByKey.put(ns + "/" + name, d);
                }
                case "PersistentVolume" -> declaredPVs.add(d);
                default -> { /* ignored */ }
            }
        }

        List<LintIssue> issues = new ArrayList<>();
        for (JsonNode d : docs) {
            String kind = d.path("kind").asText("");
            if (!isPodTemplate(kind)) continue;
            String name = d.path("metadata").path("name").asText("");
            String ns = d.path("metadata").path("namespace").asText("");
            JsonNode podSpec = podSpecOf(d, kind);
            if (podSpec == null) continue;

            // Volumes: configMap, secret, persistentVolumeClaim
            for (JsonNode vol : podSpec.path("volumes")) {
                JsonNode cm = vol.path("configMap");
                if (!cm.isMissingNode()) {
                    String n = cm.path("name").asText("");
                    if (!n.isBlank() && !declaredCMs.contains(ns + "/" + n)
                        && !probe.configMapExists(ns, n)) {
                        issues.add(new LintIssue("error", kind, name, ns,
                            "references ConfigMap '" + n + "' that doesn't exist in namespace '"
                                + ns + "' and isn't in the applied set",
                            "Add a ConfigMap manifest, or remove the volume reference."));
                    }
                }
                JsonNode sec = vol.path("secret");
                if (!sec.isMissingNode()) {
                    String n = sec.path("secretName").asText("");
                    if (!n.isBlank() && !declaredSecrets.contains(ns + "/" + n)
                        && !probe.secretExists(ns, n)) {
                        issues.add(new LintIssue("error", kind, name, ns,
                            "references Secret '" + n + "' that doesn't exist in namespace '"
                                + ns + "' and isn't in the applied set",
                            "Add a Secret manifest, or create it out-of-band before apply."));
                    }
                }
                JsonNode pvc = vol.path("persistentVolumeClaim");
                if (!pvc.isMissingNode()) {
                    String n = pvc.path("claimName").asText("");
                    if (!n.isBlank() && !declaredPVCs.contains(ns + "/" + n)
                        && !probe.pvcExists(ns, n)) {
                        issues.add(new LintIssue("error", kind, name, ns,
                            "references PVC '" + n + "' that doesn't exist in namespace '"
                                + ns + "' and isn't in the applied set",
                            "Add a PersistentVolumeClaim manifest or pre-create one."));
                    }
                }
            }

            // Container env: envFrom.{configMapRef,secretRef}, env.valueFrom.{configMapKeyRef,secretKeyRef}
            for (JsonNode container : allContainers(podSpec)) {
                for (JsonNode ef : container.path("envFrom")) {
                    JsonNode cmRef = ef.path("configMapRef");
                    if (!cmRef.isMissingNode()) {
                        String n = cmRef.path("name").asText("");
                        boolean optional = ef.path("configMapRef").path("optional").asBoolean(false)
                            || cmRef.path("optional").asBoolean(false);
                        if (!optional && !n.isBlank()
                            && !declaredCMs.contains(ns + "/" + n) && !probe.configMapExists(ns, n)) {
                            issues.add(new LintIssue("error", kind, name, ns,
                                "envFrom references ConfigMap '" + n + "' that doesn't exist",
                                "Add the ConfigMap or mark the envFrom ref as optional."));
                        }
                    }
                    JsonNode secRef = ef.path("secretRef");
                    if (!secRef.isMissingNode()) {
                        String n = secRef.path("name").asText("");
                        boolean optional = secRef.path("optional").asBoolean(false);
                        if (!optional && !n.isBlank()
                            && !declaredSecrets.contains(ns + "/" + n) && !probe.secretExists(ns, n)) {
                            issues.add(new LintIssue("error", kind, name, ns,
                                "envFrom references Secret '" + n + "' that doesn't exist",
                                "Add the Secret or mark the envFrom ref as optional."));
                        }
                    }
                }
                for (JsonNode env : container.path("env")) {
                    JsonNode vf = env.path("valueFrom");
                    JsonNode cmK = vf.path("configMapKeyRef");
                    if (!cmK.isMissingNode()) {
                        String n = cmK.path("name").asText("");
                        boolean optional = cmK.path("optional").asBoolean(false);
                        if (!optional && !n.isBlank()
                            && !declaredCMs.contains(ns + "/" + n) && !probe.configMapExists(ns, n)) {
                            issues.add(new LintIssue("error", kind, name, ns,
                                "env '" + env.path("name").asText() + "' references ConfigMap '"
                                    + n + "' that doesn't exist",
                                "Add the ConfigMap or mark the keyRef as optional."));
                        }
                    }
                    JsonNode sK = vf.path("secretKeyRef");
                    if (!sK.isMissingNode()) {
                        String n = sK.path("name").asText("");
                        boolean optional = sK.path("optional").asBoolean(false);
                        if (!optional && !n.isBlank()
                            && !declaredSecrets.contains(ns + "/" + n) && !probe.secretExists(ns, n)) {
                            issues.add(new LintIssue("error", kind, name, ns,
                                "env '" + env.path("name").asText() + "' references Secret '"
                                    + n + "' that doesn't exist",
                                "Add the Secret or mark the keyRef as optional."));
                        }
                    }
                }
            }
        }

        // Static-binding PVCs: storageClassName "" needs a matching PV (manifest + cluster).
        List<JsonNode> allPVs = new ArrayList<>(declaredPVs);
        allPVs.addAll(probe.allPersistentVolumes());
        for (var entry : pvcByKey.entrySet()) {
            JsonNode pvc = entry.getValue();
            // Only check if static binding is requested (empty string, not absent).
            JsonNode scNode = pvc.path("spec").path("storageClassName");
            if (scNode.isMissingNode() || !scNode.asText("").isEmpty()) continue;

            Set<String> wanted = readAccessModes(pvc.path("spec").path("accessModes"));
            long requested = parseStorage(
                pvc.path("spec").path("resources").path("requests").path("storage").asText(""));
            String pvcKey = entry.getKey();
            String pvcName = pvcKey.substring(pvcKey.indexOf('/') + 1);
            String pvcNs = pvcKey.substring(0, pvcKey.indexOf('/'));

            boolean satisfiable = false;
            String firstFailReason = null;
            for (JsonNode pv : allPVs) {
                // PV may match by explicit volumeName too — pvc.spec.volumeName == pv.metadata.name.
                String vn = pvc.path("spec").path("volumeName").asText("");
                if (!vn.isBlank() && !vn.equals(pv.path("metadata").path("name").asText(""))) continue;
                Set<String> offered = readAccessModes(pv.path("spec").path("accessModes"));
                long capacity = parseStorage(pv.path("spec").path("capacity").path("storage").asText(""));
                if (!offered.containsAll(wanted)) {
                    if (firstFailReason == null) {
                        firstFailReason = "PV '" + pv.path("metadata").path("name").asText()
                            + "' offers " + offered + " but PVC requests " + wanted;
                    }
                    continue;
                }
                if (capacity < requested) {
                    if (firstFailReason == null) {
                        firstFailReason = "PV '" + pv.path("metadata").path("name").asText()
                            + "' capacity " + capacity + " < PVC request " + requested;
                    }
                    continue;
                }
                satisfiable = true;
                break;
            }
            if (!satisfiable) {
                issues.add(new LintIssue("error", "PersistentVolumeClaim", pvcName, pvcNs,
                    "static-binding PVC has no compatible PV available — "
                        + (firstFailReason == null ? "no PVs declared or in the cluster" : firstFailReason),
                    "Either match the PVC's accessModes/storage to an existing PV, set "
                        + "spec.volumeName to bind explicitly, or drop storageClassName: \"\" to "
                        + "use the default provisioner."));
            }
        }

        return issues;
    }

    // ---- helpers --------------------------------------------------------------

    private static boolean isPodTemplate(String kind) {
        return switch (kind) {
            case "Pod", "Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob", "ReplicaSet" -> true;
            default -> false;
        };
    }

    /** Reach into the pod template for the kind. CronJob is nested an extra level. */
    private static JsonNode podSpecOf(JsonNode doc, String kind) {
        if ("Pod".equals(kind)) return doc.path("spec");
        if ("CronJob".equals(kind)) return doc.path("spec").path("jobTemplate").path("spec").path("template").path("spec");
        return doc.path("spec").path("template").path("spec");
    }

    private static List<JsonNode> allContainers(JsonNode podSpec) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode c : podSpec.path("containers")) out.add(c);
        for (JsonNode c : podSpec.path("initContainers")) out.add(c);
        return out;
    }

    private static Set<String> readAccessModes(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode m : node) out.add(m.asText());
        }
        return out;
    }

    /** Parse k8s storage strings like "20Gi", "5G", "500Mi" into bytes. Returns 0 on unparseable. */
    static long parseStorage(String s) {
        if (s == null || s.isBlank()) return 0L;
        s = s.trim();
        // Match digits + optional unit suffix (k8s quantity, simplified).
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        double n;
        try { n = Double.parseDouble(s.substring(0, i)); } catch (Exception e) { return 0L; }
        String unit = s.substring(i).trim();
        return switch (unit) {
            case "", "B" -> (long) n;
            case "K", "k" -> (long) (n * 1000);
            case "Ki" -> (long) (n * 1024);
            case "M" -> (long) (n * 1_000_000);
            case "Mi" -> (long) (n * 1024 * 1024);
            case "G" -> (long) (n * 1_000_000_000);
            case "Gi" -> (long) (n * 1024L * 1024 * 1024);
            case "T" -> (long) (n * 1_000_000_000_000L);
            case "Ti" -> (long) (n * 1024L * 1024 * 1024 * 1024);
            default -> 0L;
        };
    }

    private static List<JsonNode> readAll(Path renderedDir, ObjectMapper yaml) {
        List<JsonNode> docs = new ArrayList<>();
        if (renderedDir == null) return docs;
        try {
            if (Files.isRegularFile(renderedDir)) {
                appendDocs(renderedDir, docs, yaml);
                return docs;
            }
            try (var stream = Files.walk(renderedDir)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return (n.endsWith(".yaml") || n.endsWith(".yml"))
                            && !n.equals("kustomization.yaml") && !n.equals("kustomization.yml");
                    })
                    .forEach(p -> appendDocs(p, docs, yaml));
            }
        } catch (IOException ignored) {}
        return docs;
    }

    private static void appendDocs(Path file, List<JsonNode> out, ObjectMapper yaml) {
        try (var parser = yaml.getFactory().createParser(Files.newBufferedReader(file))) {
            MappingIterator<JsonNode> it = yaml.readerFor(JsonNode.class).readValues(parser);
            while (it.hasNext()) {
                JsonNode d = it.next();
                if (d != null && d.isObject()) out.add(d);
            }
        } catch (IOException ignored) {}
    }
}
