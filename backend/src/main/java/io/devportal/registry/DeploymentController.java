package io.devportal.registry;

import io.devportal.registry.DeploymentRepository.Filter;
import io.devportal.registry.RegistryDtos.DeploymentReport;
import io.devportal.registry.RegistryDtos.ReportRequest;
import io.devportal.registry.RegistryDtos.ReportResult;
import io.devportal.registry.RegistryDtos.TargetSummary;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deployment registry — "what is running where".
 *
 * <ul>
 *   <li>{@code POST /api/deployments/report} — idempotent batch upsert keyed by
 *       (assetId, target, namespace, name), with optional source-scoped reconcile.</li>
 *   <li>{@code POST /api/deployments} — the same for a single deployment.</li>
 *   <li>{@code GET /api/deployments}, {@code /api/deployments/{id}}, {@code /api/deployments/targets},
 *       {@code /api/assets/{id}/deployments} — reads (open, like the rest of the portal).</li>
 *   <li>{@code POST /api/deployments/{id}/retire}, {@code POST /api/deployments/{id}/check},
 *       {@code DELETE /api/deployments/{id}}.</li>
 * </ul>
 * Writes need {@code X-DevHub-Token} when DEVHUB_REGISTRY_TOKEN is configured (see {@link RegistryConfig}).
 */
@RestController
public class DeploymentController {

    private final DeploymentService service;
    private final HealthPoller poller;

    public DeploymentController(DeploymentService service, HealthPoller poller) {
        this.service = service;
        this.poller = poller;
    }

    @GetMapping("/api/deployments")
    public List<Deployment> list(@RequestParam(required = false) String target,
                                 @RequestParam(required = false) String assetId,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String source,
                                 @RequestParam(required = false) String phase,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "false") boolean includeRetired) {
        return service.list(new Filter(blank(target), blank(assetId), blank(status), blank(source),
            blank(phase), blank(q), includeRetired));
    }

    @GetMapping("/api/deployments/targets")
    public List<TargetSummary> targets() {
        return service.targets();
    }

    @GetMapping("/api/deployments/{id:\\d+}")
    public Deployment get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/api/assets/{assetId}/deployments")
    public List<Deployment> forAsset(@PathVariable String assetId,
                                     @RequestParam(defaultValue = "false") boolean includeRetired) {
        return service.forAsset(assetId, includeRetired);
    }

    @PostMapping("/api/deployments/report")
    public ReportResult report(@RequestBody ReportRequest req) {
        ReportResult r = service.report(req);
        poller.checkSoon(r.deployments());
        return r;
    }

    @PostMapping("/api/deployments")
    public Deployment reportOne(@RequestBody DeploymentReport item) {
        ReportResult r = service.report(new ReportRequest(item == null ? null : item.source(), false, null,
            item == null ? null : List.of(item)));
        poller.checkSoon(r.deployments());
        return r.deployments().get(0);
    }

    @PostMapping("/api/deployments/{id:\\d+}/retire")
    public Deployment retire(@PathVariable long id) {
        return service.retire(id);
    }

    /** Probe now (synchronously, bounded by the probe timeout) and return the updated row. */
    @PostMapping("/api/deployments/{id:\\d+}/check")
    public Deployment check(@PathVariable long id) {
        Deployment d = service.get(id);
        String url = d.probeUrl();
        if (url != null && d.retiredAt() == null) {
            HealthPoller.Result r = poller.probe(url);
            service.recordCheck(id, r.status(), r.error(), r.latencyMs());
        }
        return service.get(id);
    }

    @DeleteMapping("/api/deployments/{id:\\d+}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
