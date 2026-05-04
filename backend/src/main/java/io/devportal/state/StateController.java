package io.devportal.state;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/state")
public class StateController {

    private final StateService state;
    private final StateGitService git;

    public StateController(StateService state, StateGitService git) {
        this.state = state;
        this.git = git;
    }

    @PostMapping("/export")
    public Map<String, Object> export() throws IOException {
        Path dir = state.export();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", dir.toString());
        return out;
    }

    @PostMapping("/import")
    public Map<String, Object> importTree() throws IOException {
        StateService.ImportResult r = state.importTree();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assets", r.assets());
        out.put("edges", r.edges());
        return out;
    }

    @PostMapping("/git-sync")
    public Map<String, Object> gitSync(
        @RequestParam(required = false, defaultValue = "snapshot") String message
    ) throws IOException, GitAPIException {
        Path dir = state.export();
        boolean inited = git.ensureRepo(dir);
        String sha = git.commitAll(dir, message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", dir.toString());
        out.put("initialized", inited);
        out.put("commit", sha);
        return out;
    }
}
