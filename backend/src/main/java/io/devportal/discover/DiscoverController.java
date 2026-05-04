package io.devportal.discover;

import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.discover.dto.DiscoveredResources;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DiscoverController {

    private final ResourceDetector detector;
    private final AssetRepository assets;
    private final WorkspaceService workspace;

    public DiscoverController(ResourceDetector detector, AssetRepository assets, WorkspaceService workspace) {
        this.detector = detector;
        this.assets = assets;
        this.workspace = workspace;
    }

    @GetMapping("/api/assets/{id}/discover")
    public DiscoveredResources discover(@PathVariable String id) throws IOException {
        if (!assets.existsById(id)) throw new NotFoundException("Asset '" + id + "' not found");
        Path ws = workspace.workspaceFor(id);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace empty for '" + id + "' — run a build to clone first");
        }
        return detector.scan(ws);
    }
}
