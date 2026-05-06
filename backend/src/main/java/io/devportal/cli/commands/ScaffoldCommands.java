package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.runtime.k8s.scaffold.CommitResult;
import io.devportal.runtime.k8s.scaffold.FrontendScaffolder;
import io.devportal.runtime.k8s.scaffold.K8sCommitService;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "scaffold", description = "Generate Dockerfile / k8s manifests / frontend tier scaffolds.")
public class ScaffoldCommands {

    private final K8sCommitService svc;

    public ScaffoldCommands(K8sCommitService svc) { this.svc = svc; }

    @Command(name = "k8s", description = "Scaffold default k8s manifests in the asset's workspace.")
    public Integer scaffoldK8s(@Parameters(paramLabel = "ID") String id) throws Exception {
        List<String> files = svc.scaffold(id);
        System.out.println(Out.yaml(files));
        return 0;
    }

    @Command(name = "frontend-tiers", description = "Detected frontend tiers in the workspace.")
    public Integer frontendTiers(@Parameters(paramLabel = "ID") String id) throws Exception {
        List<FrontendScaffolder.Tier> tiers = svc.detectFrontendTiers(id);
        System.out.println(Out.yaml(tiers));
        return 0;
    }

    @Command(name = "frontend", description = "Scaffold a Dockerfile + nginx.conf + k8s manifest for one tier.")
    public Integer frontend(@Parameters(paramLabel = "ID") String id, @Parameters(paramLabel = "PATH") String path) throws Exception {
        FrontendScaffolder.ScaffoldResult r = svc.scaffoldFrontend(id, path);
        System.out.println(Out.yaml(r));
        return 0;
    }

    @Command(name = "runtime", description = "Scaffold both Dockerfile and k8s manifests in one call.")
    public Integer runtime(@Parameters(paramLabel = "ID") String id) throws Exception {
        K8sCommitService.ScaffoldFullResult r = svc.scaffoldFull(id);
        System.out.println(Out.yaml(r));
        return 0;
    }

    @Command(name = "commit-render", description = "Apply rendered (port-patched) manifests as repo edits, commit on a branch.")
    public Integer commitRender(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--branch") String branch,
        @Option(names = "--message") String message,
        @Option(names = "--push") boolean push
    ) throws Exception {
        CommitResult r = svc.commitRender(id, branch, message, push);
        System.out.println(Out.yaml(r));
        return 0;
    }

    @Command(name = "commit-workspace", description = "Commit current workspace edits on a branch.")
    public Integer commitWorkspace(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--branch") String branch,
        @Option(names = "--message") String message,
        @Option(names = "--push") boolean push
    ) throws Exception {
        CommitResult r = svc.commitWorkspace(id, branch, message, push);
        System.out.println(Out.yaml(r));
        return 0;
    }
}
