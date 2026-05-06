package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.docs.DocsService;
import io.devportal.docs.dto.DocFile;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "docs", description = "Per-asset markdown browser.")
public class DocsCommands {

    private final DocsService svc;

    public DocsCommands(DocsService svc) { this.svc = svc; }

    @Command(name = "list", description = "Markdown files in the asset's workspace.")
    public Integer list(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) throws Exception {
        List<DocFile> files = svc.list(id);
        System.out.println(json ? Out.json(files) : Out.yaml(files));
        return 0;
    }

    @Command(name = "read", description = "Print one doc file.")
    public Integer read(@Parameters(paramLabel = "ID") String id, @Parameters(paramLabel = "PATH") String path) throws Exception {
        System.out.print(svc.read(id, path));
        return 0;
    }
}
