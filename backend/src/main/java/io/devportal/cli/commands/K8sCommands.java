package io.devportal.cli.commands;

import io.devportal.asset.AssetRepository;
import io.devportal.cli.output.Out;
import io.devportal.runtime.k8s.K8sCompositionService;
import io.devportal.runtime.k8s.K8sDiagnosticsService;
import io.devportal.runtime.k8s.K8sService;
import io.devportal.runtime.k8s.dto.K8sDiagnostics;
import io.devportal.runtime.k8s.dto.K8sStatus;
import io.devportal.runtime.k8s.dto.MonitoringLinks;
import io.devportal.test.FixtureResult;
import io.devportal.test.TestFixtureService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "k8s", description = "kubectl apply / delete / status, plus runtime-plan and diagnostics.")
public class K8sCommands {

    private final K8sService k8s;
    private final K8sCompositionService composition;
    private final K8sDiagnosticsService diagnostics;
    private final AssetRepository assets;
    private final TestFixtureService fixtures;

    public K8sCommands(K8sService k8s, K8sCompositionService composition,
                       K8sDiagnosticsService diagnostics, AssetRepository assets, TestFixtureService fixtures) {
        this.k8s = k8s;
        this.composition = composition;
        this.diagnostics = diagnostics;
        this.assets = assets;
        this.fixtures = fixtures;
    }

    @Command(name = "apply", description = "kubectl apply manifests for an asset (or its full runtime closure).")
    public Integer apply(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--include", description = "Pass 'runtime' to apply the asset + its transitive runtime producers.") String include,
        @Option(names = "--skip", description = "CSV of asset ids to skip when --include=runtime.") String skip,
        @Option(names = "--run-hooks", description = "After apply, run runOnApply fixtures.") boolean runHooks
    ) throws Exception {
        Object res;
        if ("runtime".equalsIgnoreCase(include)) {
            res = composition.applyComposite(id, parseCsv(skip));
        } else {
            res = k8s.apply(id);
        }
        if (runHooks) {
            List<FixtureResult> hooks = fixtures.runOnApplyHooks(id);
            System.out.println(Out.yaml(Map.of("apply", res, "hookResults", hooks)));
        } else {
            System.out.println(Out.yaml(res));
        }
        return 0;
    }

    @Command(name = "plan", description = "Composite runtime plan — what would apply.")
    public Integer plan(@Parameters(paramLabel = "ID") String id) {
        K8sCompositionService.RuntimePlan p = composition.plan(id);
        System.out.println(Out.yaml(p));
        return 0;
    }

    @Command(name = "render", description = "Render manifests with allocated NodePorts patched in (no apply).")
    public Integer render(@Parameters(paramLabel = "ID") String id) throws Exception {
        var asset = assets.findById(id).orElseThrow();
        var src = k8s.resolveK8sPath(id);
        var rendered = k8s.renderForApply(asset, src);
        System.out.println(Out.yaml(Map.of("asset", id, "renderedDir", rendered.toString(), "source", src.toString())));
        return 0;
    }

    @Command(name = "delete", description = "kubectl delete an asset (or its full runtime closure).")
    public Integer delete(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--include", description = "'runtime' includes producers.") String include,
        @Option(names = "--skip", description = "CSV of ids to skip when --include=runtime.") String skip
    ) throws Exception {
        Object res = "runtime".equalsIgnoreCase(include)
            ? composition.deleteComposite(id, parseCsv(skip))
            : k8s.delete(id);
        System.out.println(Out.yaml(res));
        return 0;
    }

    @Command(name = "status", description = "Aggregate kubectl status for the asset.")
    public Integer status(@Parameters(paramLabel = "ID") String id) throws Exception {
        K8sStatus s = k8s.status(id);
        System.out.println(Out.yaml(s));
        return 0;
    }

    @Command(name = "links", description = "Monitoring/Grafana deep-links for the asset.")
    public Integer links(@Parameters(paramLabel = "ID") String id) {
        MonitoringLinks l = k8s.links(id);
        System.out.println(Out.yaml(l));
        return 0;
    }

    @Command(name = "diagnostics", description = "Detailed diagnostic dump for an asset's k8s resources.")
    public Integer diagnostics(@Parameters(paramLabel = "ID") String id) throws Exception {
        K8sDiagnostics d = diagnostics.diagnose(id);
        System.out.println(Out.yaml(d));
        return 0;
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return new HashSet<>(List.of(csv.split("\\s*,\\s*")));
    }
}
