package io.devportal.runtime.k8s;

import io.devportal.runtime.k8s.dto.K8sStatus;
import io.devportal.runtime.k8s.dto.MonitoringLinks;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class K8sController {

    private final K8sService k8s;
    private final K8sCompositionService composition;
    private final K8sDiagnosticsService diagnostics;
    private final io.devportal.asset.AssetRepository assets;
    private final io.devportal.test.TestFixtureService fixtures;

    public K8sController(K8sService k8s, K8sCompositionService composition,
                         K8sDiagnosticsService diagnostics,
                         io.devportal.asset.AssetRepository assets,
                         io.devportal.test.TestFixtureService fixtures) {
        this.k8s = k8s;
        this.composition = composition;
        this.diagnostics = diagnostics;
        this.assets = assets;
        this.fixtures = fixtures;
    }

    @PostMapping("/api/assets/{id}/k8s/apply")
    public Object apply(
        @PathVariable String id,
        @RequestParam(name = "include", required = false) String include,
        @RequestParam(name = "skip", required = false) String skip,
        // When true, after a successful apply run all spec.test.fixtures with runOnApply=true
        // in declaration order. Used to seed test data, populate caches, etc. Hook results
        // are merged into the response under the "hookResults" key.
        @RequestParam(name = "runHooks", required = false, defaultValue = "false") boolean runHooks
    ) throws IOException, InterruptedException {
        Object applyResult;
        if ("runtime".equalsIgnoreCase(include)) {
            applyResult = composition.applyComposite(id, parseCsv(skip));
        } else {
            applyResult = k8s.apply(id);
        }
        if (!runHooks) return applyResult;
        java.util.List<io.devportal.test.FixtureResult> hookResults = fixtures.runOnApplyHooks(id);
        // Wrap apply result + hooks in a single envelope so the caller sees both.
        return java.util.Map.of(
            "apply", applyResult,
            "hookResults", hookResults
        );
    }

    @GetMapping("/api/assets/{id}/k8s/runtime-plan")
    public K8sCompositionService.RuntimePlan runtimePlan(@PathVariable String id) {
        return composition.plan(id);
    }

    /** Render manifests with allocated NodePorts patched in; returns the dir path (no apply). */
    @PostMapping("/api/assets/{id}/k8s/render")
    public Map<String, Object> render(@PathVariable String id) throws IOException {
        var asset = assets.findById(id).orElseThrow(
            () -> new io.devportal.asset.error.NotFoundException("Asset '" + id + "' not found"));
        var src = k8s.resolveK8sPath(id);
        var rendered = k8s.renderForApply(asset, src);
        return Map.of("asset", id, "renderedDir", rendered.toString(), "source", src.toString());
    }

    @DeleteMapping("/api/assets/{id}/k8s")
    public Object delete(
        @PathVariable String id,
        @RequestParam(name = "include", required = false) String include,
        @RequestParam(name = "skip", required = false) String skip
    ) throws IOException, InterruptedException {
        if ("runtime".equalsIgnoreCase(include)) {
            return composition.deleteComposite(id, parseCsv(skip));
        }
        return k8s.delete(id);
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return new HashSet<>(Arrays.asList(csv.split("\\s*,\\s*")));
    }

    @GetMapping("/api/assets/{id}/k8s/status")
    public K8sStatus status(@PathVariable String id) throws IOException, InterruptedException {
        return k8s.status(id);
    }

    @GetMapping("/api/assets/{id}/k8s/links")
    public MonitoringLinks links(@PathVariable String id) {
        return k8s.links(id);
    }

    @GetMapping("/api/assets/{id}/k8s/diagnostics")
    public io.devportal.runtime.k8s.dto.K8sDiagnostics diagnostics(@PathVariable String id)
            throws IOException, InterruptedException {
        return diagnostics.diagnose(id);
    }
}
