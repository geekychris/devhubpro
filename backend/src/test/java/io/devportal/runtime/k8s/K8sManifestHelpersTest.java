package io.devportal.runtime.k8s;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pin the apply-time namespace handling. These rules decide whether {@code kubectl apply}
 * succeeds or rejects the manifest with "namespace from object does not match" — bugs here
 * regress the symptom the user hit on mp4 where pods half-applied into two namespaces.
 */
class K8sManifestHelpersTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    // ---- injectNamespaceIfMissing --------------------------------------------

    @Test
    void injectsNamespaceWhenMetadataLacksOne() throws IOException {
        JsonNode doc = yaml.readTree("""
            kind: Deployment
            metadata:
              name: web
            spec: {}
            """);
        boolean mutated = K8sManifestHelpers.injectNamespaceIfMissing(doc, "worksphere");
        assertThat(mutated).isTrue();
        assertThat(doc.path("metadata").path("namespace").asText()).isEqualTo("worksphere");
    }

    @Test
    void leavesExplicitNamespaceUntouched() throws IOException {
        JsonNode doc = yaml.readTree("""
            kind: Deployment
            metadata:
              name: web
              namespace: production
            """);
        boolean mutated = K8sManifestHelpers.injectNamespaceIfMissing(doc, "worksphere");
        assertThat(mutated).isFalse();
        assertThat(doc.path("metadata").path("namespace").asText()).isEqualTo("production");
    }

    @Test
    void treatsBlankNamespaceAsMissing() throws IOException {
        // Some scaffolders emit `namespace: ""` which kubectl rejects as invalid. Replace it.
        JsonNode doc = yaml.readTree("""
            kind: Service
            metadata:
              name: api
              namespace: ""
            """);
        boolean mutated = K8sManifestHelpers.injectNamespaceIfMissing(doc, "worksphere");
        assertThat(mutated).isTrue();
        assertThat(doc.path("metadata").path("namespace").asText()).isEqualTo("worksphere");
    }

    @Test
    void doesNotInjectIntoClusterScopedKinds() throws IOException {
        for (String kind : new String[]{"Namespace", "PersistentVolume", "ClusterRole",
                                        "ClusterRoleBinding", "StorageClass",
                                        "CustomResourceDefinition"}) {
            JsonNode doc = yaml.readTree("kind: " + kind + "\nmetadata:\n  name: x\n");
            boolean mutated = K8sManifestHelpers.injectNamespaceIfMissing(doc, "worksphere");
            assertThat(mutated)
                .as("cluster-scoped %s must not get a namespace", kind)
                .isFalse();
            assertThat(doc.path("metadata").has("namespace"))
                .as("%s metadata.namespace must remain absent", kind)
                .isFalse();
        }
    }

    @Test
    void kindCheckIsCaseInsensitive() throws IOException {
        JsonNode doc = yaml.readTree("kind: namespace\nmetadata:\n  name: foo\n");
        assertThat(K8sManifestHelpers.injectNamespaceIfMissing(doc, "worksphere")).isFalse();
    }

    @Test
    void ignoresDocWithoutMetadata() throws IOException {
        JsonNode doc = yaml.readTree("kind: Deployment\nspec: {}\n");
        assertThat(K8sManifestHelpers.injectNamespaceIfMissing(doc, "worksphere")).isFalse();
    }

    @Test
    void ignoresNullAndNonObjectDocs() {
        assertThat(K8sManifestHelpers.injectNamespaceIfMissing(null, "worksphere")).isFalse();
        assertThat(K8sManifestHelpers.injectNamespaceIfMissing(yaml.createArrayNode(), "worksphere"))
            .isFalse();
    }

    @Test
    void blankFallbackIsNoOp() throws IOException {
        // Without a fallback to inject, do nothing — better an empty namespace field that fails
        // a later validation than silently writing the empty string.
        JsonNode doc = yaml.readTree("kind: Deployment\nmetadata:\n  name: x\n");
        assertThat(K8sManifestHelpers.injectNamespaceIfMissing(doc, "")).isFalse();
        assertThat(K8sManifestHelpers.injectNamespaceIfMissing(doc, null)).isFalse();
    }

    // ---- scanManifestNamespaces -----------------------------------------------

    @Test
    void scansSingleFileForDeclaredNamespaces(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("app.yaml");
        Files.writeString(f, """
            kind: Deployment
            metadata:
              name: a
              namespace: worksphere
            ---
            kind: Service
            metadata:
              name: b
            ---
            kind: Namespace
            metadata:
              name: worksphere
            """);
        var ns = K8sManifestHelpers.scanManifestNamespaces(f, yaml);
        assertThat(ns).containsExactlyInAnyOrder("worksphere");
    }

    @Test
    void scansDirectoryRecursivelyAcrossYamlFiles(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("a.yaml"),
            "kind: Deployment\nmetadata:\n  name: a\n  namespace: worksphere\n");
        Files.writeString(dir.resolve("nested/b.yml"),
            "kind: Service\nmetadata:\n  name: b\n  namespace: worksphere\n");
        Files.writeString(dir.resolve("c.txt"),  // non-yaml: ignored
            "namespace: should-be-ignored\n");
        var ns = K8sManifestHelpers.scanManifestNamespaces(dir, yaml);
        assertThat(ns).containsExactlyInAnyOrder("worksphere");
    }

    @Test
    void surfacesDisagreementBetweenManifests(@TempDir Path dir) throws IOException {
        // Two different namespaces across docs is the signal the caller uses to refuse the
        // single-namespace reconciliation — proving this surface is critical.
        Files.writeString(dir.resolve("a.yaml"),
            "kind: Deployment\nmetadata:\n  name: a\n  namespace: ns-a\n");
        Files.writeString(dir.resolve("b.yaml"),
            "kind: Service\nmetadata:\n  name: b\n  namespace: ns-b\n");
        var ns = K8sManifestHelpers.scanManifestNamespaces(dir, yaml);
        assertThat(ns).containsExactlyInAnyOrder("ns-a", "ns-b");
    }

    @Test
    void emptyWhenNoManifestDeclaresNamespace(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.yaml"),
            "kind: Deployment\nmetadata:\n  name: a\n");
        var ns = K8sManifestHelpers.scanManifestNamespaces(dir, yaml);
        assertThat(ns).isEmpty();
    }

    @Test
    void skipsKustomizationInputs(@TempDir Path dir) throws IOException {
        // kustomization.yaml isn't a standalone resource — applying it errors, and its declared
        // namespace shouldn't show up in the scan either.
        Files.writeString(dir.resolve("kustomization.yaml"),
            "namespace: should-be-ignored\nresources: []\n");
        Files.writeString(dir.resolve("ok.yaml"),
            "kind: Deployment\nmetadata:\n  name: a\n  namespace: worksphere\n");
        var ns = K8sManifestHelpers.scanManifestNamespaces(dir, yaml);
        assertThat(ns).containsExactlyInAnyOrder("worksphere");
    }

    @Test
    void returnsEmptyForMissingPath(@TempDir Path dir) {
        var ns = K8sManifestHelpers.scanManifestNamespaces(dir.resolve("does-not-exist"), yaml);
        assertThat(ns).isEmpty();
    }

    @Test
    void returnsEmptyForNullPath() {
        assertThat(K8sManifestHelpers.scanManifestNamespaces(null, yaml)).isEmpty();
    }

    // ---- isClusterScoped -----------------------------------------------------

    @Test
    void isClusterScopedKnowsTheCommonKinds() {
        // Spot-check the cluster-scoped allow-list — regressing this would re-create the apply
        // bug where Namespace/PV manifests get a metadata.namespace and kubectl rejects them.
        assertThat(K8sManifestHelpers.isClusterScoped("Namespace")).isTrue();
        assertThat(K8sManifestHelpers.isClusterScoped("namespace")).isTrue();
        assertThat(K8sManifestHelpers.isClusterScoped("PersistentVolume")).isTrue();
        assertThat(K8sManifestHelpers.isClusterScoped("ClusterRoleBinding")).isTrue();
        assertThat(K8sManifestHelpers.isClusterScoped("Deployment")).isFalse();
        assertThat(K8sManifestHelpers.isClusterScoped("Service")).isFalse();
        assertThat(K8sManifestHelpers.isClusterScoped("")).isFalse();
        assertThat(K8sManifestHelpers.isClusterScoped(null)).isFalse();
    }

    // ---- integration spot-check: multi-doc rewrite stays consistent ----------

    @Test
    void rewritesMultiDocFileInPlace(@TempDir Path dir) throws IOException {
        // Apply the inject across every doc in a real multi-doc YAML and verify the resulting
        // stream still parses to the same number of valid docs, with namespace where expected.
        Path src = dir.resolve("mixed.yaml");
        Files.writeString(src, """
            kind: Namespace
            metadata:
              name: worksphere
            ---
            kind: Deployment
            metadata:
              name: app
            ---
            kind: PersistentVolume
            metadata:
              name: pv1
            ---
            kind: Service
            metadata:
              name: app
              namespace: explicit
            """);

        try (var parser = yaml.getFactory().createParser(Files.newBufferedReader(src))) {
            var it = yaml.readerFor(JsonNode.class).<JsonNode>readValues(parser);
            int doc = 0;
            while (it.hasNext()) {
                JsonNode n = it.next();
                K8sManifestHelpers.injectNamespaceIfMissing(n, "worksphere");
                String kind = n.path("kind").asText();
                switch (kind) {
                    case "Namespace" -> assertThat(n.path("metadata").has("namespace")).isFalse();
                    case "Deployment" -> assertThat(n.path("metadata").path("namespace").asText())
                        .isEqualTo("worksphere");
                    case "PersistentVolume" -> assertThat(n.path("metadata").has("namespace")).isFalse();
                    case "Service" -> assertThat(n.path("metadata").path("namespace").asText())
                        .isEqualTo("explicit");
                    default -> { /* no-op */ }
                }
                doc++;
                // Sanity: assert it's still a usable ObjectNode after mutation.
                assertThat(n).isInstanceOf(ObjectNode.class);
            }
            assertThat(doc).isEqualTo(4);
        }
    }
}
