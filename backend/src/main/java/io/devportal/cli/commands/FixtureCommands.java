package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.manifest.Manifest;
import io.devportal.test.FixtureResult;
import io.devportal.test.TestFixtureService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "fixture", description = "Run/inspect lifecycle hooks (devportal.yaml spec.test.fixtures).")
public class FixtureCommands {

    private final TestFixtureService svc;

    public FixtureCommands(TestFixtureService svc) { this.svc = svc; }

    @Command(name = "list", description = "Fixtures declared by the asset's manifest.")
    public Integer list(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) {
        List<Manifest.TestFixture> items = svc.list(id);
        System.out.println(json ? Out.json(items) : Out.yaml(items));
        return 0;
    }

    @Command(name = "run", description = "Run one fixture and stream its parsed result.")
    public Integer run(@Parameters(paramLabel = "ID") String id, @Parameters(paramLabel = "NAME") String name) throws Exception {
        FixtureResult r = svc.run(id, name);
        System.out.println(Out.yaml(r));
        return 0;
    }

    @Command(name = "last-run", description = "Last cached run result for a fixture.")
    public Integer lastRun(@Parameters(paramLabel = "ID") String id, @Parameters(paramLabel = "NAME") String name) {
        Optional<FixtureResult> r = svc.lastRun(id, name);
        if (r.isEmpty()) {
            System.out.println("never run");
            return 0;
        }
        System.out.println(Out.yaml(r.get()));
        return 0;
    }
}
