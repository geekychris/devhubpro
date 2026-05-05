package io.devportal.runtime.k8s;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.Dependency;
import io.devportal.asset.error.NotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Compose a k8s apply across an asset and the producers it consumes at runtime.
 *
 * <p>The runtime closure is computed by walking {@code dependency} edges where
 * {@code kind = 'runtime'} only. Each producer keeps its own namespace (the V5
 * default is {@code asset.id}); cross-namespace DNS handles service discovery.
 *
 * <p>Apply order is leaf producer first, root last — same shape as the build
 * chain. On any failure the chain aborts; downstream steps are not attempted.
 * Delete order is reversed so consumers come down before producers.
 */
@Service
public class K8sCompositionService {

    private static final Logger log = LoggerFactory.getLogger(K8sCompositionService.class);

    private final AssetRepository assets;
    private final K8sService k8s;

    public K8sCompositionService(AssetRepository assets, K8sService k8s) {
        this.assets = assets;
        this.k8s = k8s;
    }

    public record RuntimePlan(String rootId, List<Step> steps) {
        /** One asset to apply. {@code hasManifests} is false when the workspace has no k8s/ dir. */
        public record Step(String assetId, String namespace, boolean hasManifests, boolean isRoot) {}
    }

    /**
     * Returns the ordered apply plan: producers first (transitive runtime closure), root last.
     * Steps with {@code hasManifests=false} are reported but skipped during apply.
     */
    public RuntimePlan plan(String rootId) {
        if (!assets.existsById(rootId)) {
            throw new NotFoundException("Asset '" + rootId + "' not found");
        }

        // BFS the producer-closure following only kind=runtime edges.
        Map<String, List<String>> producers = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (!visited.add(cur)) continue;
            List<String> outs = new ArrayList<>();
            for (Dependency d : assets.findDependenciesOf(cur)) {
                if (!"runtime".equals(d.kind())) continue;
                outs.add(d.producerId());
                if (!visited.contains(d.producerId())) queue.add(d.producerId());
            }
            producers.put(cur, outs);
        }

        // Kahn's: indegree = number of producers each node depends on (inside the closure).
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String id : producers.keySet()) indegree.put(id, 0);
        for (var e : producers.entrySet()) {
            indegree.put(e.getKey(), e.getValue().size());
        }
        Deque<String> ready = new ArrayDeque<>();
        for (var e : indegree.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());

        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String n = ready.poll();
            ordered.add(n);
            for (var e : producers.entrySet()) {
                if (e.getValue().contains(n)) {
                    indegree.merge(e.getKey(), -1, Integer::sum);
                    if (indegree.get(e.getKey()) == 0) ready.add(e.getKey());
                }
            }
        }
        if (ordered.size() != producers.size()) {
            // Runtime cycle. Surface the unresolved set so the user can fix the dep edges.
            Set<String> remaining = new HashSet<>(producers.keySet());
            ordered.forEach(remaining::remove);
            throw new IllegalStateException(
                "Runtime dependency cycle in producer-closure of '" + rootId + "': "
                + remaining + ". Remove a kind=runtime edge to break the cycle.");
        }

        // Decorate each step. resolveK8sPath probes the workspace; treat any throw as no-manifests.
        List<RuntimePlan.Step> steps = new ArrayList<>(ordered.size());
        for (String id : ordered) {
            String ns = k8s.effectiveNamespace(id);
            boolean hasManifests = false;
            try {
                k8s.resolveK8sPath(id);
                hasManifests = true;
            } catch (IOException | RuntimeException ignored) {
                // Not cloned, or no k8s/ dir, or kubernetes.enabled=false.
            }
            steps.add(new RuntimePlan.Step(id, ns, hasManifests, id.equals(rootId)));
        }
        return new RuntimePlan(rootId, steps);
    }

    /**
     * Apply each step in order, skipping any in {@code skip} or any without manifests.
     * Returns per-step output; throws on the first failure with the partial result attached
     * to the exception's {@code cause} chain via the standard message.
     */
    public List<Map<String, Object>> applyComposite(String rootId, Set<String> skip)
            throws IOException, InterruptedException {
        RuntimePlan plan = plan(rootId);
        Set<String> skipSet = skip == null ? Set.of() : skip;
        List<Map<String, Object>> results = new ArrayList<>();
        for (RuntimePlan.Step s : plan.steps()) {
            if (skipSet.contains(s.assetId())) {
                results.add(Map.of("asset", s.assetId(), "skipped", "by-user"));
                continue;
            }
            if (!s.hasManifests()) {
                results.add(Map.of("asset", s.assetId(), "skipped", "no-manifests"));
                continue;
            }
            log.info("Composite apply: {} -> namespace {}", s.assetId(), s.namespace());
            Map<String, Object> r = k8s.apply(s.assetId());
            results.add(r);
        }
        return results;
    }

    /**
     * Delete each step in reverse order (consumers first, producers last). Honors the same
     * skip semantics. {@link K8sService#delete} already passes {@code --ignore-not-found}.
     */
    public List<Map<String, Object>> deleteComposite(String rootId, Set<String> skip)
            throws IOException, InterruptedException {
        RuntimePlan plan = plan(rootId);
        Set<String> skipSet = skip == null ? Set.of() : skip;
        List<RuntimePlan.Step> reversed = new ArrayList<>(plan.steps());
        java.util.Collections.reverse(reversed);
        List<Map<String, Object>> results = new ArrayList<>();
        for (RuntimePlan.Step s : reversed) {
            if (skipSet.contains(s.assetId()) || !s.hasManifests()) {
                results.add(Map.of("asset", s.assetId(), "skipped", "true"));
                continue;
            }
            log.info("Composite delete: {} <- namespace {}", s.assetId(), s.namespace());
            Map<String, Object> r = k8s.delete(s.assetId());
            results.add(r);
        }
        return results;
    }
}
