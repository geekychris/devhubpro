package io.devportal.build;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.Dependency;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Topo-sort the producer-closure of an asset for deep build mode. */
@Component
public class DependencyWalker {

    private final AssetRepository assets;

    public DependencyWalker(AssetRepository assets) {
        this.assets = assets;
    }

    /**
     * Returns assets in build order: leaf producers first, root last.
     * Throws {@link IllegalStateException} on cycles.
     */
    public List<Asset> buildOrder(String rootId) {
        Map<String, Asset> nodes = new LinkedHashMap<>();
        Map<String, List<String>> edges = new HashMap<>(); // consumer -> producers
        collect(rootId, nodes, edges);

        // Kahn's algorithm. We want producers (no incoming "depends-on" edges from within set) first.
        Map<String, Integer> indegree = new HashMap<>();
        for (String id : nodes.keySet()) indegree.put(id, 0);
        // For Kahn we need: a topological order where for each (consumer -> producer) edge,
        // producer comes first. So reverse: indegree[consumer] += producers count.
        for (var e : edges.entrySet()) {
            indegree.merge(e.getKey(), e.getValue().size(), Integer::sum);
        }
        // We want zero-indegree consumers ... no wait, that gives root first.
        // Re-do correctly: producers first means nodes whose all producers are already emitted.
        // Standard Kahn: process nodes with indegree 0 (no unmet prerequisites).
        // Here a node's prerequisites are its producers, so indegree = number of producers we depend on.
        // That's exactly what we computed.

        List<Asset> ordered = new ArrayList<>();
        java.util.ArrayDeque<String> ready = new java.util.ArrayDeque<>();
        for (var e : indegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        while (!ready.isEmpty()) {
            String n = ready.poll();
            ordered.add(nodes.get(n));
            // Find consumers of n among the collected set; decrement their indegree.
            for (var e : edges.entrySet()) {
                if (e.getValue().contains(n)) {
                    indegree.merge(e.getKey(), -1, Integer::sum);
                    if (indegree.get(e.getKey()) == 0) ready.add(e.getKey());
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            // Find one cycle to give the user a useful error.
            Set<String> remaining = new HashSet<>(nodes.keySet());
            for (Asset a : ordered) remaining.remove(a.id());
            String cyclePath = findCycle(remaining, edges);
            throw new IllegalStateException(
                "Dependency cycle detected in producer-closure of '" + rootId + "'."
                + (cyclePath == null ? "" : " Cycle: " + cyclePath)
                + " Edit dependencies via /api/assets/<id>/dependencies/<producer> "
                + "(DELETE) or re-run auto-wire after fixing the pom.");
        }
        return ordered;
    }

    /** DFS one node from {@code remaining} to surface a single cycle for the error message. */
    private static String findCycle(Set<String> remaining, Map<String, List<String>> edges) {
        if (remaining.isEmpty()) return null;
        String start = remaining.iterator().next();
        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        stack.push(start);
        parent.put(start, null);
        while (!stack.isEmpty()) {
            String cur = stack.peek();
            if (!visited.add(cur)) { onStack.remove(cur); stack.pop(); continue; }
            onStack.add(cur);
            boolean any = false;
            for (String next : edges.getOrDefault(cur, List.of())) {
                if (!remaining.contains(next)) continue;
                if (onStack.contains(next)) {
                    // Found cycle. Walk back through parents.
                    StringBuilder sb = new StringBuilder(next);
                    String c = cur;
                    while (c != null && !c.equals(next)) { sb.insert(0, c + " -> "); c = parent.get(c); }
                    sb.insert(0, next + " -> ");
                    return sb.toString();
                }
                if (!visited.contains(next)) {
                    parent.put(next, cur);
                    stack.push(next);
                    any = true;
                    break;
                }
            }
            if (!any) { onStack.remove(cur); stack.pop(); }
        }
        return null;
    }

    private void collect(String rootId, Map<String, Asset> nodes, Map<String, List<String>> edges) {
        Set<String> visited = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (!visited.add(cur)) continue;
            assets.findById(cur).ifPresent(a -> nodes.put(a.id(), a));
            // Build chain only walks kind=build edges; runtime edges are for the k8s composite-apply
            // path, not for "what do I need compiled first?". Also dedupe per producer: with the
            // dual build+runtime edges between the same pair we'd otherwise inflate the indegree
            // and Kahn's would report a false cycle when the producer's single decrement leaves
            // indegree > 0.
            Set<String> seen = new HashSet<>();
            List<String> producers = new ArrayList<>();
            for (Dependency d : assets.findDependenciesOf(cur)) {
                if (!"build".equals(d.kind())) continue;
                if (!seen.add(d.producerId())) continue;
                producers.add(d.producerId());
                if (!visited.contains(d.producerId())) queue.add(d.producerId());
            }
            edges.put(cur, producers);
        }
    }
}
