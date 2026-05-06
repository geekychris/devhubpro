package io.devportal.cli.commands;

import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.cli.output.Out;
import io.devportal.discover.ResourceDetector;
import io.devportal.discover.dto.DiscoveredResources;
import io.devportal.workspace.WorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "discover", description = "Scan a workspace for build / docker / k8s artifacts.")
public class DiscoverCommands {

    private final ResourceDetector detector;
    private final AssetRepository assets;
    private final WorkspaceService workspace;

    public DiscoverCommands(ResourceDetector detector, AssetRepository assets, WorkspaceService workspace) {
        this.detector = detector;
        this.assets = assets;
        this.workspace = workspace;
    }

    @Command(name = "run", description = "Discover resources in an asset's workspace.")
    public Integer run(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) throws Exception {
        if (!assets.existsById(id)) throw new NotFoundException("Asset '" + id + "' not found");
        Path ws = workspace.workspaceFor(id);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace empty for '" + id + "' — run a build to clone first");
        }
        DiscoveredResources r = detector.scan(ws);
        System.out.println(json ? Out.json(r) : Out.yaml(r));
        return 0;
    }
}
