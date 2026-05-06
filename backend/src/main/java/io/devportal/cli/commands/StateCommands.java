package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.state.StateGitService;
import io.devportal.state.StateService;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(name = "state", description = "Export / import portal state to YAML, sync to a git repo.")
public class StateCommands {

    private final StateService state;
    private final StateGitService git;

    public StateCommands(StateService state, StateGitService git) {
        this.state = state;
        this.git = git;
    }

    @Command(name = "export", description = "Dump current DB state to the YAML directory.")
    public Integer export() throws Exception {
        Path dir = state.export();
        System.out.println(Out.yaml(Map.of("dir", dir.toString())));
        return 0;
    }

    @Command(name = "import", description = "Wipe + re-load assets from the YAML directory.")
    public Integer importTree() throws Exception {
        StateService.ImportResult r = state.importTree();
        System.out.println(Out.yaml(Map.of("assets", r.assets(), "edges", r.edges())));
        return 0;
    }

    @Command(name = "git-sync", description = "Export then commit to the state git repo.")
    public Integer gitSync(@Option(names = "--message", defaultValue = "snapshot") String message) throws Exception {
        Path dir = state.export();
        boolean inited = git.ensureRepo(dir);
        String sha = git.commitAll(dir, message);
        System.out.println(Out.yaml(Map.of("dir", dir.toString(), "initialized", inited, "commit", sha)));
        return 0;
    }
}
