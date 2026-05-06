package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.runtime.k8s.scaffold.CommitResult;
import io.devportal.workspace.WorkspaceCommitService;
import io.devportal.workspace.WorkspaceStatus;
import io.devportal.workspace.WorkspaceStatusService;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "workspace", description = "Local workspace status, diff, commit, push.")
public class WorkspaceCommands {

    private final WorkspaceStatusService status;
    private final WorkspaceCommitService committer;

    public WorkspaceCommands(WorkspaceStatusService status, WorkspaceCommitService committer) {
        this.status = status;
        this.committer = committer;
    }

    @Command(name = "status", description = "Working-tree status for an asset.")
    public Integer status(@Parameters(paramLabel = "ID") String id) throws Exception {
        WorkspaceStatus s = status.status(id);
        System.out.println(Out.yaml(s));
        return 0;
    }

    @Command(name = "diff", description = "Diff for one path.")
    public Integer diff(@Parameters(paramLabel = "ID") String id, @Parameters(paramLabel = "PATH") String path) throws Exception {
        System.out.print(status.diff(id, path));
        return 0;
    }

    @Command(name = "commit", description = "Commit listed paths on a branch.")
    public Integer commit(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--branch", required = true) String branch,
        @Option(names = "--message", required = true) String message,
        @Option(names = "--path", description = "Repeatable path to include (default: all changes).") List<String> paths,
        @Option(names = "--push") boolean push
    ) throws Exception {
        CommitResult r = committer.commit(id, branch, message,
            paths == null ? List.of() : paths, push);
        System.out.println(Out.yaml(r));
        return 0;
    }

    @Command(name = "push", description = "Push a branch upstream.")
    public Integer push(@Parameters(paramLabel = "ID") String id, @Option(names = "--branch", required = true) String branch) throws Exception {
        CommitResult r = committer.push(id, branch);
        System.out.println(Out.yaml(r));
        return 0;
    }
}
