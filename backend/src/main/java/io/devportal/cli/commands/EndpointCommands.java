package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.runtime.endpoints.AssetEndpoints;
import io.devportal.runtime.endpoints.EndpointsService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "endpoint", description = "Discovered URLs (host-accessible + in-cluster) for an asset.")
public class EndpointCommands {

    private final EndpointsService svc;

    public EndpointCommands(EndpointsService svc) { this.svc = svc; }

    @Command(name = "list", description = "All endpoints for an asset.")
    public Integer list(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) throws Exception {
        AssetEndpoints e = svc.discover(id);
        System.out.println(json ? Out.json(e) : Out.yaml(e));
        return 0;
    }
}
