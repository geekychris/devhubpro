package io.devportal.runtime.k8s;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pin the pre-apply lint behaviour. These rules decide whether dev_portal lets kubectl
 * apply run; regressions here would either silently produce broken pods (false-negatives) or
 * refuse-to-apply valid manifests (false-positives).
 */
class K8sManifestLinterTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    // ---- ConfigMap refs ------------------------------------------------------

    @Test
    void flagsVolumeConfigMapRefMissingFromTreeAndCluster(@TempDir Path dir) throws IOException {
        write(dir, "deploy.yaml", """
            apiVersion: apps/v1
            kind: Deployment
            metadata: { name: app, namespace: worksphere }
            spec:
              template:
                spec:
                  containers: [{name: c, image: x}]
                  volumes:
                    - name: cfg
                      configMap: { name: missing-cm }
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).problem()).contains("ConfigMap 'missing-cm'");
        assertThat(issues.get(0).severity()).isEqualTo("error");
    }

    @Test
    void acceptsConfigMapRefSatisfiedByApplyTree(@TempDir Path dir) throws IOException {
        write(dir, "all.yaml", """
            apiVersion: v1
            kind: ConfigMap
            metadata: { name: app-cfg, namespace: worksphere }
            data: { x: y }
            ---
            apiVersion: apps/v1
            kind: Deployment
            metadata: { name: app, namespace: worksphere }
            spec:
              template:
                spec:
                  containers: [{name: c, image: x}]
                  volumes:
                    - name: cfg
                      configMap: { name: app-cfg }
            """);
        assertThat(K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER)).isEmpty();
    }

    @Test
    void acceptsConfigMapRefSatisfiedByCluster(@TempDir Path dir) throws IOException {
        write(dir, "deploy.yaml", """
            kind: Deployment
            apiVersion: apps/v1
            metadata: { name: app, namespace: worksphere }
            spec:
              template:
                spec:
                  containers: [{name: c, image: x}]
                  volumes:
                    - name: cfg
                      configMap: { name: shared-cm }
            """);
        var probe = stubProbe(name -> "shared-cm".equals(name), n -> false, n -> false, List.of());
        assertThat(K8sManifestLinter.lint(dir, yaml, probe)).isEmpty();
    }

    @Test
    void flagsEnvFromConfigMapRef(@TempDir Path dir) throws IOException {
        write(dir, "d.yaml", """
            kind: Deployment
            apiVersion: apps/v1
            metadata: { name: app, namespace: ns1 }
            spec:
              template:
                spec:
                  containers:
                    - name: c
                      image: x
                      envFrom:
                        - configMapRef: { name: gone }
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).problem()).contains("ConfigMap 'gone'");
    }

    @Test
    void respectsEnvFromOptionalFlag(@TempDir Path dir) throws IOException {
        // Optional refs are fine to be missing — k8s won't fail to start the pod.
        write(dir, "d.yaml", """
            kind: Deployment
            apiVersion: apps/v1
            metadata: { name: app, namespace: ns1 }
            spec:
              template:
                spec:
                  containers:
                    - name: c
                      image: x
                      envFrom:
                        - configMapRef: { name: maybe, optional: true }
                        - secretRef: { name: maybe-sec, optional: true }
            """);
        assertThat(K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER)).isEmpty();
    }

    @Test
    void flagsEnvValueFromKeyRefs(@TempDir Path dir) throws IOException {
        write(dir, "d.yaml", """
            kind: Deployment
            apiVersion: apps/v1
            metadata: { name: app, namespace: ns1 }
            spec:
              template:
                spec:
                  containers:
                    - name: c
                      image: x
                      env:
                        - name: A
                          valueFrom: { configMapKeyRef: { name: gone, key: k } }
                        - name: B
                          valueFrom: { secretKeyRef: { name: also-gone, key: k } }
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).hasSize(2);
        assertThat(issues).extracting(K8sManifestLinter.LintIssue::problem)
            .anySatisfy(p -> assertThat(p).contains("env 'A'"))
            .anySatisfy(p -> assertThat(p).contains("env 'B'"));
    }

    // ---- Secret refs ---------------------------------------------------------

    @Test
    void flagsMissingSecretVolume(@TempDir Path dir) throws IOException {
        write(dir, "d.yaml", """
            kind: StatefulSet
            apiVersion: apps/v1
            metadata: { name: db, namespace: ns1 }
            spec:
              template:
                spec:
                  containers: [{name: c, image: x}]
                  volumes:
                    - name: tls
                      secret: { secretName: tls-cert }
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).problem()).contains("Secret 'tls-cert'");
    }

    // ---- PVC refs ------------------------------------------------------------

    @Test
    void flagsMissingPVCMount(@TempDir Path dir) throws IOException {
        write(dir, "d.yaml", """
            kind: Deployment
            apiVersion: apps/v1
            metadata: { name: app, namespace: ns1 }
            spec:
              template:
                spec:
                  containers: [{name: c, image: x}]
                  volumes:
                    - name: data
                      persistentVolumeClaim: { claimName: missing-pvc }
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).problem()).contains("PVC 'missing-pvc'");
    }

    // ---- Static PVC binding (the real social-app case) -----------------------

    @Test
    void flagsRwxPvcAgainstRwoPv(@TempDir Path dir) throws IOException {
        // The exact bug we found on mp4: PV RWO, PVC RWX, storageClassName "" — binder won't pair.
        write(dir, "all.yaml", """
            kind: PersistentVolume
            apiVersion: v1
            metadata: { name: uploads-pv }
            spec:
              capacity: { storage: 20Gi }
              accessModes: [ReadWriteOnce]
              hostPath: { path: /data }
              storageClassName: ""
            ---
            kind: PersistentVolumeClaim
            apiVersion: v1
            metadata: { name: uploads-pvc, namespace: worksphere }
            spec:
              accessModes: [ReadWriteMany]
              resources: { requests: { storage: 20Gi } }
              storageClassName: ""
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).kind()).isEqualTo("PersistentVolumeClaim");
        assertThat(issues.get(0).name()).isEqualTo("uploads-pvc");
        assertThat(issues.get(0).problem())
            .contains("ReadWriteOnce")
            .contains("ReadWriteMany");
    }

    @Test
    void flagsPvcLargerThanPvCapacity(@TempDir Path dir) throws IOException {
        write(dir, "all.yaml", """
            kind: PersistentVolume
            apiVersion: v1
            metadata: { name: small-pv }
            spec:
              capacity: { storage: 5Gi }
              accessModes: [ReadWriteOnce]
              storageClassName: ""
            ---
            kind: PersistentVolumeClaim
            apiVersion: v1
            metadata: { name: big-pvc, namespace: ns }
            spec:
              accessModes: [ReadWriteOnce]
              resources: { requests: { storage: 20Gi } }
              storageClassName: ""
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).problem()).contains("capacity");
    }

    @Test
    void acceptsMatchingStaticPvcAndPv(@TempDir Path dir) throws IOException {
        write(dir, "all.yaml", """
            kind: PersistentVolume
            apiVersion: v1
            metadata: { name: ok-pv }
            spec:
              capacity: { storage: 20Gi }
              accessModes: [ReadWriteOnce]
              storageClassName: ""
            ---
            kind: PersistentVolumeClaim
            apiVersion: v1
            metadata: { name: ok-pvc, namespace: ns }
            spec:
              accessModes: [ReadWriteOnce]
              resources: { requests: { storage: 10Gi } }
              storageClassName: ""
            """);
        assertThat(K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER)).isEmpty();
    }

    @Test
    void skipsDynamicallyProvisionedPvc(@TempDir Path dir) throws IOException {
        // No storageClassName: "" means use the default provisioner — we have nothing to check.
        write(dir, "all.yaml", """
            kind: PersistentVolumeClaim
            apiVersion: v1
            metadata: { name: dynamic-pvc, namespace: ns }
            spec:
              accessModes: [ReadWriteOnce]
              resources: { requests: { storage: 5Gi } }
            """);
        assertThat(K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER)).isEmpty();
    }

    @Test
    void honoursPvcVolumeNameForSelectivity(@TempDir Path dir) throws IOException {
        // When PVC.spec.volumeName is set, only that PV is considered. Make a matching PV with a
        // mismatched name and a non-matching PV with the right name — the lint must flag.
        write(dir, "all.yaml", """
            kind: PersistentVolume
            apiVersion: v1
            metadata: { name: wrong-pv }
            spec:
              capacity: { storage: 20Gi }
              accessModes: [ReadWriteOnce]
              storageClassName: ""
            ---
            kind: PersistentVolume
            apiVersion: v1
            metadata: { name: target-pv }
            spec:
              capacity: { storage: 5Gi }
              accessModes: [ReadWriteOnce]
              storageClassName: ""
            ---
            kind: PersistentVolumeClaim
            apiVersion: v1
            metadata: { name: my-pvc, namespace: ns }
            spec:
              volumeName: target-pv
              accessModes: [ReadWriteOnce]
              resources: { requests: { storage: 10Gi } }
              storageClassName: ""
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).hasSize(1);  // target-pv is too small; wrong-pv excluded by volumeName
    }

    // ---- Pod-template kind variants -------------------------------------------

    @Test
    void inspectsAllPodTemplateKinds(@TempDir Path dir) throws IOException {
        // Each pod-template variant must be walked. Skipping one would let unbacked refs through
        // in that resource type.
        write(dir, "all.yaml", """
            kind: Pod
            apiVersion: v1
            metadata: { name: p, namespace: n }
            spec:
              containers: [{name: c, image: x}]
              volumes: [{name: v, configMap: { name: gone-1 }}]
            ---
            kind: StatefulSet
            apiVersion: apps/v1
            metadata: { name: s, namespace: n }
            spec:
              template:
                spec:
                  containers: [{name: c, image: x}]
                  volumes: [{name: v, configMap: { name: gone-2 }}]
            ---
            kind: DaemonSet
            apiVersion: apps/v1
            metadata: { name: ds, namespace: n }
            spec:
              template:
                spec:
                  containers: [{name: c, image: x}]
                  volumes: [{name: v, configMap: { name: gone-3 }}]
            ---
            kind: Job
            apiVersion: batch/v1
            metadata: { name: j, namespace: n }
            spec:
              template:
                spec:
                  containers: [{name: c, image: x}]
                  volumes: [{name: v, configMap: { name: gone-4 }}]
            ---
            kind: CronJob
            apiVersion: batch/v1
            metadata: { name: cj, namespace: n }
            spec:
              jobTemplate:
                spec:
                  template:
                    spec:
                      containers: [{name: c, image: x}]
                      volumes: [{name: v, configMap: { name: gone-5 }}]
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).hasSize(5);
        assertThat(issues).extracting(K8sManifestLinter.LintIssue::problem)
            .anySatisfy(p -> assertThat(p).contains("gone-1"))
            .anySatisfy(p -> assertThat(p).contains("gone-2"))
            .anySatisfy(p -> assertThat(p).contains("gone-3"))
            .anySatisfy(p -> assertThat(p).contains("gone-4"))
            .anySatisfy(p -> assertThat(p).contains("gone-5"));
    }

    @Test
    void ignoresInitContainerEnvFromOptional(@TempDir Path dir) throws IOException {
        write(dir, "d.yaml", """
            kind: Deployment
            apiVersion: apps/v1
            metadata: { name: d, namespace: n }
            spec:
              template:
                spec:
                  initContainers:
                    - name: ic
                      image: x
                      envFrom: [{configMapRef: { name: required }}]
                  containers: [{name: c, image: x}]
            """);
        var issues = K8sManifestLinter.lint(dir, yaml, K8sManifestLinter.NO_CLUSTER);
        assertThat(issues).extracting(K8sManifestLinter.LintIssue::problem)
            .anySatisfy(p -> assertThat(p).contains("required"));
    }

    // ---- parseStorage --------------------------------------------------------

    @Test
    void parsesK8sQuantityUnits() {
        assertThat(K8sManifestLinter.parseStorage("20Gi")).isEqualTo(20L * 1024 * 1024 * 1024);
        assertThat(K8sManifestLinter.parseStorage("500Mi")).isEqualTo(500L * 1024 * 1024);
        assertThat(K8sManifestLinter.parseStorage("1G")).isEqualTo(1_000_000_000L);
        assertThat(K8sManifestLinter.parseStorage("5T")).isEqualTo(5_000_000_000_000L);
        assertThat(K8sManifestLinter.parseStorage("100")).isEqualTo(100L);
        assertThat(K8sManifestLinter.parseStorage("")).isEqualTo(0L);
        assertThat(K8sManifestLinter.parseStorage("bogus")).isEqualTo(0L);
        assertThat(K8sManifestLinter.parseStorage(null)).isEqualTo(0L);
    }

    // ---- helpers --------------------------------------------------------------

    private static void write(Path dir, String name, String yaml) throws IOException {
        Files.writeString(dir.resolve(name), yaml);
    }

    private static K8sManifestLinter.ClusterProbe stubProbe(
        java.util.function.Predicate<String> cmExists,
        java.util.function.Predicate<String> secretExists,
        java.util.function.Predicate<String> pvcExists,
        List<JsonNode> pvs) {
        return new K8sManifestLinter.ClusterProbe() {
            public boolean configMapExists(String ns, String name) { return cmExists.test(name); }
            public boolean secretExists(String ns, String name) { return secretExists.test(name); }
            public boolean pvcExists(String ns, String name) { return pvcExists.test(name); }
            public List<JsonNode> allPersistentVolumes() { return pvs; }
        };
    }
}
