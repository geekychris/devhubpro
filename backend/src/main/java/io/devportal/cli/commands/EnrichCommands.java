package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.enrich.EnrichService;
import io.devportal.enrich.dto.GitInfo;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "git-info", description = "GitHub metadata for an asset (stars, forks, tags, last push).")
public class EnrichCommands {

    private final EnrichService svc;

    public EnrichCommands(EnrichService svc) { this.svc = svc; }

    @Command(name = "show", description = "Fetch fresh GitHub metadata for the asset.")
    public Integer show(@Parameters(paramLabel = "ID") String id) throws Exception {
        GitInfo i = svc.gitInfo(id);
        System.out.println(Out.yaml(i));
        return 0;
    }
}
