package io.devportal.cli.commands;

import io.devportal.build.BuildService;
import io.devportal.build.dto.BuildProgress;
import io.devportal.build.dto.BuildView;
import io.devportal.build.dto.KickBuildRequest;
import io.devportal.cli.output.Ansi;
import io.devportal.cli.output.Out;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "build", description = "Kick builds (shallow / deep), inspect logs and progress.")
public class BuildCommands {

    private final BuildService svc;

    public BuildCommands(BuildService svc) { this.svc = svc; }

    @Command(name = "kick", description = "Run a build for an asset.")
    public Integer kick(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--mode", description = "shallow | deep") String mode,
        @Option(names = "--command", description = "Named command from manifest (default: build).") String commandName,
        @Option(names = "--cmd-line", description = "Override raw command line.") String commandLine,
        @Option(names = "--ref", description = "Git ref to build.") String ref
    ) throws Exception {
        BuildView b = svc.kick(id, new KickBuildRequest(mode, commandName, commandLine, ref));
        System.out.println("queued build " + b.id() + " (" + b.mode() + ", " + b.commandName() + ")");
        return 0;
    }

    @Command(name = "list", description = "Recent builds for an asset.")
    public Integer list(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--limit", defaultValue = "50") int limit,
        @Option(names = "--json") boolean json
    ) {
        List<BuildView> builds = svc.listFor(id, limit);
        System.out.print(json ? Out.json(builds) + "\n" : Out.tableOf(builds,
            List.of("ID", "STATUS", "MODE", "COMMAND", "STARTED"),
            List.of(BuildView::id, b -> Ansi.status(b.status()), BuildView::mode, BuildView::commandName,
                b -> b.startedAt() == null ? "" : b.startedAt().toString())));
        return 0;
    }

    @Command(name = "get", description = "Show one build.")
    public Integer get(@Parameters(paramLabel = "BUILD_ID") long id, @Option(names = "--json") boolean json) {
        BuildView b = svc.get(id);
        System.out.println(json ? Out.json(b) : Out.yaml(b));
        return 0;
    }

    @Command(name = "log", description = "Print captured stdout/stderr for a build.")
    public Integer log(@Parameters(paramLabel = "BUILD_ID") long id) throws Exception {
        System.out.print(svc.readLog(id));
        return 0;
    }

    @Command(name = "chain", description = "Builds that share this build's chain (parent + siblings).")
    public Integer chain(@Parameters(paramLabel = "BUILD_ID") long id, @Option(names = "--json") boolean json) {
        List<BuildView> chain = svc.chain(id);
        System.out.print(json ? Out.json(chain) + "\n" : Out.tableOf(chain,
            List.of("ID", "ASSET", "STATUS", "MODE", "COMMAND"),
            List.of(BuildView::id, BuildView::assetId, b -> Ansi.status(b.status()), BuildView::mode, BuildView::commandName)));
        return 0;
    }

    @Command(name = "progress", description = "Aggregate progress for the chain containing this build.")
    public Integer progress(@Parameters(paramLabel = "BUILD_ID") long id) {
        BuildProgress p = svc.progress(id);
        System.out.println(Out.yaml(p));
        return 0;
    }

    @Command(name = "recent", description = "Most recent builds across all assets.")
    public Integer recent(@Option(names = "--limit", defaultValue = "50") int limit,
                         @Option(names = "--json") boolean json) {
        List<BuildView> builds = svc.recent(limit);
        System.out.print(json ? Out.json(builds) + "\n" : Out.tableOf(builds,
            List.of("ID", "ASSET", "STATUS", "MODE", "COMMAND"),
            List.of(BuildView::id, BuildView::assetId, b -> Ansi.status(b.status()), BuildView::mode, BuildView::commandName)));
        return 0;
    }

    @Command(name = "delete", description = "Delete a finished build's record + log file.")
    public Integer delete(@Parameters(paramLabel = "BUILD_ID") long id) {
        svc.delete(id);
        System.out.println("deleted build " + id);
        return 0;
    }
}
