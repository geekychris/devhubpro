package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.prompt.PromptService;
import io.devportal.prompt.dto.HelpPrompt;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "prompt", description = "Build an Ask-Claude prompt for an asset (manifest, deps, recent commits).")
public class PromptCommands {

    private final PromptService svc;

    public PromptCommands(PromptService svc) { this.svc = svc; }

    @Command(name = "help", description = "Build a help-prompt for an asset.")
    public Integer help(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--problem", defaultValue = "general") String problem,
        @Option(names = "--details") String details
    ) {
        HelpPrompt p = svc.build(id, problem, details);
        System.out.println(Out.yaml(p));
        return 0;
    }
}
