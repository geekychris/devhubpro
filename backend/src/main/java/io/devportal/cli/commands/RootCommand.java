package io.devportal.cli.commands;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

/**
 * Top-level CLI command. The interactive shell is implicit (each typed line is a
 * subcommand of this root), so this class itself does nothing — it just enumerates the
 * domain command groups available in the shell.
 *
 * <p>To add a new command group, write a new {@code @Component @Command} class (e.g. with
 * {@code name = "mything"} and {@code @Command}-annotated methods for each subcommand) and
 * append its class to the {@code subcommands} list below. That's it — Spring will inject
 * its dependencies, picocli will route input to it, and tab completion picks it up.
 */
@Component
@Command(
    name = "devportal",
    description = "DevPortal CLI — type 'help' to list commands, 'help <cmd>' for detail.",
    mixinStandardHelpOptions = true,
    subcommands = {
        HelpCommand.class,
        AssetCommands.class,
        AnalyzeCommands.class,
        AuditCommands.class,
        BackupCommands.class,
        BuildCommands.class,
        BulkImportCommands.class,
        DashboardCommands.class,
        DiscoverCommands.class,
        DocsCommands.class,
        DockerCommands.class,
        EnrichCommands.class,
        EndpointCommands.class,
        FixtureCommands.class,
        ForwardCommands.class,
        GraphCommands.class,
        HealthCommands.class,
        K8sCommands.class,
        MacroCommands.class,
        MetaAssetCommands.class,
        PanelCommands.class,
        PodCommands.class,
        PortCommands.class,
        PromptCommands.class,
        ScaffoldCommands.class,
        SearchCommands.class,
        SettingsCommands.class,
        StateCommands.class,
        TagCommands.class,
        TelegramCommands.class,
        VerifyCommands.class,
        WorkspaceCommands.class,
    }
)
public class RootCommand {
}
