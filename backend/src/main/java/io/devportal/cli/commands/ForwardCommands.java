package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.runtime.forward.PortForwardService;
import io.devportal.runtime.forward.PortForwardSession;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "forward", description = "kubectl port-forward sessions managed by the portal.")
public class ForwardCommands {

    private final PortForwardService svc;

    public ForwardCommands(PortForwardService svc) { this.svc = svc; }

    @Command(name = "list", description = "All port-forward sessions.")
    public Integer list(@Option(names = "--json") boolean json) {
        List<PortForwardSession> ss = svc.listAll();
        System.out.println(json ? Out.json(ss) : Out.yaml(ss));
        return 0;
    }

    @Command(name = "for", description = "Port-forwards for one asset.")
    public Integer forAsset(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) {
        List<PortForwardSession> ss = svc.listForAsset(id);
        System.out.println(json ? Out.json(ss) : Out.yaml(ss));
        return 0;
    }

    @Command(name = "start", description = "Start a port-forward to a pod.")
    public Integer start(
        @Parameters(paramLabel = "ID") String id,
        @Parameters(paramLabel = "POD") String pod,
        @Parameters(paramLabel = "CONTAINER_PORT") int containerPort,
        @Option(names = "--host-port") Integer hostPort
    ) throws Exception {
        PortForwardSession s = svc.start(id, pod, containerPort, hostPort);
        System.out.println(Out.yaml(s));
        return 0;
    }

    @Command(name = "stop", description = "Stop a port-forward session by id.")
    public Integer stop(@Parameters(paramLabel = "SESSION_ID") long id) {
        svc.stop(id);
        System.out.println("stopped " + id);
        return 0;
    }
}
