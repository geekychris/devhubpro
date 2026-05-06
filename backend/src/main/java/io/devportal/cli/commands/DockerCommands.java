package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.runtime.docker.DockerService;
import io.devportal.runtime.docker.dto.DockerContainerView;
import io.devportal.runtime.docker.dto.RunContainerResult;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "docker", description = "Build images, run containers, list / stop them.")
public class DockerCommands {

    private final DockerService docker;

    public DockerCommands(DockerService docker) { this.docker = docker; }

    @Command(name = "build", description = "Build one image for the asset.")
    public Integer build(@Parameters(paramLabel = "ID") String id) throws Exception {
        var b = docker.buildImage(id);
        System.out.println("queued docker build " + b.id());
        return 0;
    }

    @Command(name = "build-images", description = "Build the asset image plus optionally its runtime producers.")
    public Integer buildImages(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--include", description = "'runtime' to include producers.") String include
    ) throws Exception {
        boolean withRuntime = "runtime".equalsIgnoreCase(include);
        var b = docker.buildAllImages(id, withRuntime);
        System.out.println("queued image-chain build " + b.id() + " (withRuntime=" + withRuntime + ")");
        return 0;
    }

    @Command(name = "run", description = "Run a container for the asset's image.")
    public Integer run(@Parameters(paramLabel = "ID") String id) throws Exception {
        RunContainerResult r = docker.runContainer(id);
        System.out.println(Out.yaml(r));
        return 0;
    }

    @Command(name = "ps", description = "Containers running for an asset.")
    public Integer ps(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) throws Exception {
        List<DockerContainerView> cs = docker.listContainers(id);
        System.out.print(json ? Out.json(cs) + "\n" : Out.tableOf(cs,
            List.of("NAME", "IMAGE", "STATUS", "PORTS"),
            List.of(DockerContainerView::name, DockerContainerView::image, DockerContainerView::status, DockerContainerView::ports)));
        return 0;
    }

    @Command(name = "stop", description = "Stop and remove a container.")
    public Integer stop(@Parameters(paramLabel = "ID") String id, @Parameters(paramLabel = "NAME") String name) throws Exception {
        docker.stopAndRemove(name);
        System.out.println("stopped " + name);
        return 0;
    }
}
