package io.devportal.runtime.k8s.scaffold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.devportal.asset.Asset;
import io.devportal.manifest.Manifest;
import io.devportal.port.PortRepository;
import io.devportal.port.PortReservation;
import io.devportal.port.PortSlotInferrer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generates a minimal, opinionated set of Kubernetes manifests for an asset that doesn't already
 * have any. Output: {@code k8s/deployment.yaml} + {@code k8s/service.yaml}, written into the
 * asset's workspace. Caller decides whether to commit them.
 *
 * <p>Conventions:
 * <ul>
 *   <li>Container image: {@code devportal/<assetId>:latest}</li>
 *   <li>Labels + selector: {@code app=<assetId>}</li>
 *   <li>Service type: {@code NodePort} so the port registry's k8s-nodeport allocations apply</li>
 *   <li>Ports: derived from {@link PortSlotInferrer} (or already-allocated reservations)</li>
 *   <li>Replicas: 1</li>
 * </ul>
 */
@Component
public class K8sScaffolder {

    private static final Logger log = LoggerFactory.getLogger(K8sScaffolder.class);

    private final PortRepository ports;
    private final PortSlotInferrer inferrer;
    private final ObjectMapper yaml = new ObjectMapper(
        new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    );

    public K8sScaffolder(PortRepository ports, PortSlotInferrer inferrer) {
        this.ports = ports;
        this.inferrer = inferrer;
    }

    /**
     * Emits {@code k8s/deployment.yaml} and {@code k8s/service.yaml} under the asset's workspace.
     * Returns the relative paths of files written.
     */
    public List<String> scaffold(Asset asset, Path workspace) throws IOException {
        Path k8sDir = workspace.resolve("k8s");
        Files.createDirectories(k8sDir);

        List<Manifest.Port> slots = inferrer.infer(workspace);
        List<PortReservation> nodePorts = ports.findByAssetAndScope(asset.id(), "k8s-nodeport");

        Map<String, Object> deployment = buildDeployment(asset, slots);
        Map<String, Object> service = buildService(asset, slots, nodePorts);

        Path deploymentFile = k8sDir.resolve("deployment.yaml");
        Path serviceFile = k8sDir.resolve("service.yaml");
        yaml.writeValue(deploymentFile.toFile(), deployment);
        yaml.writeValue(serviceFile.toFile(), service);

        log.info("Scaffolded k8s for {}: {} slots, {} nodePort reservations",
            asset.id(), slots.size(), nodePorts.size());

        return List.of(
            workspace.relativize(deploymentFile).toString(),
            workspace.relativize(serviceFile).toString()
        );
    }

    private Map<String, Object> buildDeployment(Asset asset, List<Manifest.Port> slots) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", "apps/v1");
        root.put("kind", "Deployment");
        root.put("metadata", Map.of(
            "name", asset.id(),
            "labels", Map.of("app", asset.id(),
                             "app.kubernetes.io/managed-by", "devportal")
        ));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("replicas", 1);
        spec.put("selector", Map.of("matchLabels", Map.of("app", asset.id())));
        Map<String, Object> templateSpec = new LinkedHashMap<>();
        templateSpec.put("metadata", Map.of("labels", Map.of("app", asset.id())));

        Map<String, Object> podSpec = new LinkedHashMap<>();
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", asset.id());
        container.put("image", "devportal/" + asset.id() + ":latest");
        container.put("imagePullPolicy", "IfNotPresent");

        List<Map<String, Object>> ports = new ArrayList<>();
        for (Manifest.Port p : slots) {
            int containerPort = guessContainerPort(p.name());
            Map<String, Object> portEntry = new LinkedHashMap<>();
            portEntry.put("name", p.name());
            portEntry.put("containerPort", containerPort);
            portEntry.put("protocol", "TCP");
            ports.add(portEntry);
        }
        container.put("ports", ports);

        // Health probe — hit the http slot if one exists.
        if (slots.stream().anyMatch(p -> "http".equals(p.name()))) {
            Map<String, Object> probe = Map.of(
                "httpGet", Map.of("path", "/", "port", "http"),
                "initialDelaySeconds", 10,
                "periodSeconds", 10
            );
            container.put("readinessProbe", probe);
        }

        podSpec.put("containers", List.of(container));
        templateSpec.put("spec", podSpec);
        spec.put("template", templateSpec);
        root.put("spec", spec);
        return root;
    }

    private Map<String, Object> buildService(Asset asset, List<Manifest.Port> slots,
                                             List<PortReservation> nodePorts) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", "v1");
        root.put("kind", "Service");
        root.put("metadata", Map.of(
            "name", asset.id(),
            "labels", Map.of("app", asset.id(),
                             "app.kubernetes.io/managed-by", "devportal")
        ));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "NodePort");
        spec.put("selector", Map.of("app", asset.id()));

        Map<String, Integer> nodePortBySlot = new LinkedHashMap<>();
        for (PortReservation r : nodePorts) nodePortBySlot.put(r.slotName(), r.port());

        List<Map<String, Object>> ports = new ArrayList<>();
        for (Manifest.Port p : slots) {
            int containerPort = guessContainerPort(p.name());
            Map<String, Object> portEntry = new LinkedHashMap<>();
            portEntry.put("name", p.name());
            portEntry.put("port", containerPort);
            portEntry.put("targetPort", p.name());
            portEntry.put("protocol", "TCP");
            Integer np = nodePortBySlot.get(p.name());
            if (np != null) portEntry.put("nodePort", np);
            ports.add(portEntry);
        }
        spec.put("ports", ports);
        root.put("spec", spec);
        return root;
    }

    /** Pick a sensible default container port for a named slot. */
    private static int guessContainerPort(String slotName) {
        return switch (slotName) {
            case "http" -> 8080;
            case "management", "metrics" -> 8081;
            case "debug" -> 5005;
            case "grpc" -> 9090;
            default -> 8080;
        };
    }
}
