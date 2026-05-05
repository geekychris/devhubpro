package io.devportal.runtime.k8s.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.runtime.k8s.cluster.dto.PodDetail;
import io.devportal.runtime.k8s.cluster.dto.PodEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Read-only kubectl wrapper: pod list, logs, describe, events. */
@Service
public class ClusterService {

    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);

    private final AssetRepository assets;
    private final ObjectMapper json = new ObjectMapper();

    public ClusterService(AssetRepository assets) {
        this.assets = assets;
    }

    public List<PodDetail> listPods(String assetId) throws IOException, InterruptedException {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        ProcResult r = exec("kubectl", "get", "pods", "-A", "-l", "app=" + assetId, "-o", "json");
        if (r.exitCode != 0) {
            throw new IOException("kubectl get pods failed: " + r.output);
        }
        List<PodDetail> out = new ArrayList<>();
        JsonNode root = json.readTree(r.output);
        for (JsonNode item : root.path("items")) {
            out.add(toPodDetail(item));
        }
        return out;
    }

    public String logs(String assetId, String podName, String container, int tail) throws IOException, InterruptedException {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        String namespace = findPodNamespace(assetId, podName);
        List<String> args = new ArrayList<>(List.of(
            "kubectl", "logs", "-n", namespace, podName, "--tail=" + tail, "--timestamps=true"));
        if (container != null && !container.isBlank()) {
            args.add("-c");
            args.add(container);
        }
        ProcResult r = exec(args.toArray(String[]::new));
        return r.output;
    }

    public String describe(String assetId, String podName) throws IOException, InterruptedException {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        String namespace = findPodNamespace(assetId, podName);
        ProcResult r = exec("kubectl", "describe", "pod", "-n", namespace, podName);
        if (r.exitCode != 0) throw new IOException("kubectl describe failed: " + r.output);
        return r.output;
    }

    public List<PodEvent> events(String assetId) throws IOException, InterruptedException {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        // Get all pods for the asset, then events scoped to those.
        List<PodDetail> pods = listPods(assetId);
        List<PodEvent> out = new ArrayList<>();
        for (PodDetail p : pods) {
            ProcResult r = exec("kubectl", "get", "events", "-n", p.namespace(),
                "--field-selector", "involvedObject.name=" + p.name(),
                "-o", "json", "--sort-by=.lastTimestamp");
            if (r.exitCode != 0) continue;
            JsonNode root = json.readTree(r.output);
            for (JsonNode item : root.path("items")) {
                out.add(new PodEvent(
                    item.path("type").asText(null),
                    item.path("reason").asText(null),
                    item.path("message").asText(null),
                    item.path("source").path("component").asText(null),
                    item.path("firstTimestamp").asText(null),
                    item.path("lastTimestamp").asText(null),
                    item.path("count").asInt(0),
                    "Pod/" + p.name()
                ));
            }
        }
        return out;
    }

    private String findPodNamespace(String assetId, String podName) throws IOException, InterruptedException {
        for (PodDetail p : listPods(assetId)) {
            if (p.name().equals(podName)) return p.namespace();
        }
        throw new NotFoundException("Pod '" + podName + "' not found for asset '" + assetId + "'");
    }

    private static PodDetail toPodDetail(JsonNode item) {
        String name = item.path("metadata").path("name").asText();
        String ns = item.path("metadata").path("namespace").asText();
        String phase = item.path("status").path("phase").asText();
        String node = item.path("spec").path("nodeName").asText(null);
        String ip = item.path("status").path("podIP").asText(null);
        String startTime = item.path("status").path("startTime").asText(null);

        Map<String, String> labels = new LinkedHashMap<>();
        item.path("metadata").path("labels").fields().forEachRemaining(e ->
            labels.put(e.getKey(), e.getValue().asText()));

        Map<String, JsonNode> envByContainer = new LinkedHashMap<>();
        Map<String, List<Integer>> portsByContainer = new LinkedHashMap<>();
        for (JsonNode c : item.path("spec").path("containers")) {
            envByContainer.put(c.path("name").asText(), c.path("env"));
            List<Integer> ports = new ArrayList<>();
            for (JsonNode p : c.path("ports")) {
                int v = p.path("containerPort").asInt(-1);
                if (v > 0) ports.add(v);
            }
            portsByContainer.put(c.path("name").asText(), ports);
        }

        List<PodDetail.ContainerDetail> containers = new ArrayList<>();
        int ready = 0;
        int total = 0;
        int restarts = 0;
        for (JsonNode cs : item.path("status").path("containerStatuses")) {
            total++;
            boolean isReady = cs.path("ready").asBoolean(false);
            if (isReady) ready++;
            int restartCount = cs.path("restartCount").asInt(0);
            restarts += restartCount;

            String cname = cs.path("name").asText();
            String image = cs.path("image").asText();
            JsonNode state = cs.path("state");
            String stateName = state.fieldNames().hasNext() ? state.fieldNames().next() : "unknown";
            String reason = state.path(stateName).path("reason").asText(null);
            JsonNode last = cs.path("lastState");
            String lastTermReason = last.path("terminated").path("reason").asText(null);
            Integer lastExit = last.path("terminated").has("exitCode")
                ? last.path("terminated").path("exitCode").asInt() : null;

            List<PodDetail.EnvVar> env = new ArrayList<>();
            JsonNode envNode = envByContainer.get(cname);
            if (envNode != null && envNode.isArray()) {
                for (JsonNode e : envNode) {
                    env.add(new PodDetail.EnvVar(
                        e.path("name").asText(),
                        e.path("value").asText("")));
                }
            }
            containers.add(new PodDetail.ContainerDetail(
                cname, image, isReady, restartCount, stateName, reason, lastTermReason, lastExit,
                env, portsByContainer.getOrDefault(cname, List.of())));
        }

        return new PodDetail(name, ns, phase, node, ip, ready, total, restarts, startTime, labels, containers);
    }

    private static ProcResult exec(String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        return new ProcResult(p.waitFor(), out.toString());
    }

    private record ProcResult(int exitCode, String output) {}
}
