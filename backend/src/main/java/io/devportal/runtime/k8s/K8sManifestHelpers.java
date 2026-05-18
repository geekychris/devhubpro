package io.devportal.runtime.k8s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Stateless helpers for K8s manifest rewriting. Pulled out of {@link K8sService} so the
 * namespace-handling rules (which determine whether {@code kubectl apply} will succeed) can be
 * unit-tested without spinning up the full Spring service.
 */
final class K8sManifestHelpers {

    /**
     * Cluster-scoped kinds reject a {@code metadata.namespace} field: kubectl errors with
     * "namespace specified for cluster-scoped resource". Anything else can legally carry one.
     */
    static final Set<String> CLUSTER_SCOPED_KINDS = Set.of(
        "namespace", "node", "persistentvolume", "storageclass", "clusterrole",
        "clusterrolebinding", "customresourcedefinition", "priorityclass", "podsecuritypolicy",
        "csidriver", "csinode", "validatingwebhookconfiguration", "mutatingwebhookconfiguration",
        "apiservice", "ingressclass", "runtimeclass", "volumesnapshotclass");

    private K8sManifestHelpers() {}

    static boolean isClusterScoped(String kind) {
        return kind != null && CLUSTER_SCOPED_KINDS.contains(kind.toLowerCase());
    }

    /**
     * If {@code doc} is a namespaced kind missing {@code metadata.namespace}, set it to
     * {@code fallbackNs}. Returns true if the doc was mutated. Cluster-scoped kinds, missing
     * metadata, and docs that already carry a namespace are left untouched.
     */
    static boolean injectNamespaceIfMissing(JsonNode doc, String fallbackNs) {
        if (doc == null || !doc.isObject() || fallbackNs == null || fallbackNs.isBlank()) return false;
        String kind = doc.path("kind").asText("");
        if (isClusterScoped(kind)) return false;
        JsonNode meta = doc.path("metadata");
        if (!(meta instanceof ObjectNode metaObj)) return false;
        if (metaObj.hasNonNull("namespace") && !metaObj.path("namespace").asText("").isBlank()) {
            return false;
        }
        metaObj.put("namespace", fallbackNs);
        return true;
    }

    /**
     * Read every namespace declared in {@code metadata.namespace} across all YAML documents in
     * {@code source} (which may be a single file or a directory walked recursively for *.yaml /
     * *.yml, excluding kustomization inputs). Empty when no manifest declares one.
     */
    static Set<String> scanManifestNamespaces(Path source, ObjectMapper yaml) {
        Set<String> out = new HashSet<>();
        if (source == null) return out;
        try {
            if (Files.isRegularFile(source)) {
                collectNamespaces(source, out, yaml);
                return out;
            }
            try (var stream = Files.walk(source)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return (n.endsWith(".yaml") || n.endsWith(".yml"))
                            && !n.equals("kustomization.yaml") && !n.equals("kustomization.yml");
                    })
                    .forEach(p -> collectNamespaces(p, out, yaml));
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static void collectNamespaces(Path file, Set<String> out, ObjectMapper yaml) {
        try (var parser = yaml.getFactory().createParser(Files.newBufferedReader(file))) {
            MappingIterator<JsonNode> it = yaml.readerFor(JsonNode.class).readValues(parser);
            while (it.hasNext()) {
                JsonNode doc = it.next();
                if (doc == null || !doc.isObject()) continue;
                String ns = doc.path("metadata").path("namespace").asText(null);
                if (ns != null && !ns.isBlank()) out.add(ns);
            }
        } catch (IOException ignored) {}
    }
}
