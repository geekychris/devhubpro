package io.devportal.cli.commands;

import io.devportal.asset.AssetService;
import io.devportal.asset.dto.AssetGraphView;
import io.devportal.cli.output.Out;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "graph", description = "Reachable producer/consumer graph for an asset.")
public class GraphCommands {

    private final AssetService svc;

    public GraphCommands(AssetService svc) { this.svc = svc; }

    @Command(name = "show", description = "Reachable graph from an asset.")
    public Integer show(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--direction", defaultValue = "both", description = "producers | consumers | both") String direction,
        @Option(names = "--producer-depth", defaultValue = "-1") int producerDepth,
        @Option(names = "--consumer-depth", defaultValue = "1") int consumerDepth,
        @Option(names = "--json") boolean json
    ) {
        AssetGraphView g = svc.graph(id, direction, producerDepth, consumerDepth);
        System.out.println(json ? Out.json(g) : Out.yaml(g));
        return 0;
    }
}
