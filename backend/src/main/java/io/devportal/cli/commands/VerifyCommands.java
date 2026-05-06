package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.runtime.verify.VerifyResult;
import io.devportal.runtime.verify.VerifyService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "verify", description = "Boot-check an asset at a specific runtime stage (docker, k8s).")
public class VerifyCommands {

    private final VerifyService svc;

    public VerifyCommands(VerifyService svc) { this.svc = svc; }

    @Command(name = "run", description = "Verify a stage.")
    public Integer run(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--stage", defaultValue = "docker") String stage
    ) {
        VerifyResult r = svc.verify(id, stage);
        System.out.println(Out.yaml(r));
        return 0;
    }
}
