package io.devportal.cli.commands;

import io.devportal.backup.BackupService;
import io.devportal.backup.dto.BackupSummary;
import io.devportal.cli.output.Ansi;
import io.devportal.cli.output.Out;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "backup", description = "Snapshot + restore portal state.")
public class BackupCommands {

    private final BackupService svc;

    public BackupCommands(BackupService svc) { this.svc = svc; }

    @Command(name = "create", description = "Create a new backup folder under the configured root.")
    public Integer create(
        @Option(names = "--dir", description = "Override the default backup root for this call.") String dir,
        @Option(names = "--secrets", description = "Include ~/.devportal/secrets/ (tokens, host key, password).") boolean secrets,
        @Option(names = "--logs", description = "Include ~/.devportal/logs/ (build logs — can be large).") boolean logs,
        @Option(names = "--commit", description = "Force a git commit even if auto-commit is off.") boolean commit,
        @Option(names = "--push", description = "Push after committing (requires a configured remote).") boolean push,
        @Option(names = "--message", description = "Commit message (default: \"backup <stamp>\").") String message
    ) throws Exception {
        BackupSummary s = svc.create(new BackupService.CreateOptions(
            secrets, logs,
            dir == null || dir.isBlank() ? null : Path.of(dir),
            commit, push, message));
        System.out.println(Ansi.green("created ") + s.dir());
        System.out.print(Out.kv(List.of(
            new String[]{"stamp",          s.stamp()},
            new String[]{"assets",         String.valueOf(s.assetCount())},
            new String[]{"logs",           String.valueOf(s.logCount())},
            new String[]{"secrets",        s.includesSecrets() ? Ansi.yellow("yes") : "no"},
            new String[]{"commit",         s.commitSha() == null ? Ansi.gray("(none)") : Ansi.cyan(s.commitSha().substring(0, Math.min(10, s.commitSha().length())))},
            new String[]{"pruned",         String.valueOf(s.prunedOldBackups())}
        )));
        return 0;
    }

    @Command(name = "list", description = "List backups under the configured root, newest first.")
    public Integer list(@Option(names = "--dir") String dir, @Option(names = "--json") boolean json) throws Exception {
        List<BackupSummary> items = dir == null || dir.isBlank()
            ? svc.list()
            : svc.list(Path.of(dir));
        if (json) { System.out.println(Out.json(items)); return 0; }
        if (items.isEmpty()) { System.out.println(Ansi.gray("no backups yet")); return 0; }
        System.out.print(Out.tableOf(items,
            List.of("STAMP", "ASSETS", "LOGS", "SECRETS", "CREATED"),
            List.<java.util.function.Function<BackupSummary, ?>>of(
                BackupSummary::stamp,
                BackupSummary::assetCount,
                BackupSummary::logCount,
                s -> s.includesSecrets() ? Ansi.yellow("✓") : Ansi.gray("·"),
                s -> s.createdAt() == null ? "" : s.createdAt().toString())));
        return 0;
    }

    @Command(name = "restore", description = "Restore from a backup folder. Destructive — wipes existing state first.")
    public Integer restore(
        @Parameters(paramLabel = "PATH", description = "Path to the timestamped backup folder.") String path,
        @Option(names = "--secrets", description = "Also restore secrets.") boolean secrets,
        @Option(names = "--logs", description = "Also restore build logs.") boolean logs
    ) throws Exception {
        BackupService.RestoreResult r = svc.restore(new BackupService.RestoreOptions(
            Path.of(path), secrets, logs));
        System.out.println(Ansi.green("restored from ") + r.fromStamp());
        System.out.print(Out.kv(List.of(
            new String[]{"assets",   String.valueOf(r.assetsRestored())},
            new String[]{"edges",    String.valueOf(r.edgesRestored())},
            new String[]{"secrets",  r.secretsRestored() ? Ansi.yellow("yes") : "no"},
            new String[]{"logs",     r.logsRestored() ? "yes" : "no"}
        )));
        return 0;
    }
}
