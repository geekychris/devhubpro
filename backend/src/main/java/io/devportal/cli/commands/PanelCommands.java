package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.panels.Panel;
import io.devportal.panels.PanelService;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "panel", description = "Server-driven panels for an asset.")
public class PanelCommands {

    private final PanelService svc;

    public PanelCommands(PanelService svc) { this.svc = svc; }

    @Command(name = "list", description = "All panels for an asset.")
    public Integer list(@Parameters(paramLabel = "ID") String id) {
        List<Panel> ps = svc.panelsFor(id);
        System.out.println(Out.yaml(ps));
        return 0;
    }
}
