package io.devportal.cloudstack;

import io.devportal.cloudstack.CloudStackDtos.AssetActivity;
import io.devportal.cloudstack.CloudStackDtos.Status;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * cloud-stack integration (reads only; the browser never sees the cloud-stack token).
 *
 * <ul>
 *   <li>{@code GET /api/assets/{id}/cloudstack} — the asset's open PRs, latest Jenkins result,
 *       agent runs and "New task" link; {@code available:false} plus a reason when it cannot.</li>
 *   <li>{@code GET /api/cloudstack/status} — configured? token set? reachable?</li>
 * </ul>
 */
@RestController
@EnableConfigurationProperties(CloudStackProperties.class)
public class CloudStackController {

    private final CloudStackService service;

    public CloudStackController(CloudStackService service) {
        this.service = service;
    }

    @GetMapping("/api/assets/{assetId}/cloudstack")
    public AssetActivity forAsset(@PathVariable String assetId) {
        return service.forAsset(assetId);
    }

    @GetMapping("/api/cloudstack/status")
    public Status status() {
        return service.status();
    }
}
