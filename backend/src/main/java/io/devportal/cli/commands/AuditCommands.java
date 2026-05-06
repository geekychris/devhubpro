package io.devportal.cli.commands;

import io.devportal.audit.AuditService;
import io.devportal.audit.dto.AuditReport;
import io.devportal.cli.output.Out;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "audit", description = "Drift report against portal conventions.")
public class AuditCommands {

    private final AuditService svc;

    public AuditCommands(AuditService svc) { this.svc = svc; }

    @Command(name = "run", description = "Audit one asset.")
    public Integer run(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) {
        AuditReport r = svc.audit(id);
        System.out.println(json ? Out.json(r) : Out.yaml(r));
        return 0;
    }
}
