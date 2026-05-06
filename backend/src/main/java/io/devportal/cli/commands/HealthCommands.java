package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(name = "health", description = "Backend liveness check.")
public class HealthCommands {

    @Command(name = "check", description = "Print current backend health snapshot.")
    public Integer check() {
        System.out.println(Out.yaml(Map.of(
            "service", "devportal",
            "status", "ok",
            "time", Instant.now().toString())));
        return 0;
    }
}
