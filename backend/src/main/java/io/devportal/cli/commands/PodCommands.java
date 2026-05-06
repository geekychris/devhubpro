package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.runtime.k8s.cluster.ClusterService;
import io.devportal.runtime.k8s.cluster.dto.PodDetail;
import io.devportal.runtime.k8s.cluster.dto.PodEvent;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "pod", description = "Pods, logs, describe, events for an asset's namespace.")
public class PodCommands {

    private final ClusterService cluster;

    public PodCommands(ClusterService cluster) { this.cluster = cluster; }

    @Command(name = "list", description = "Pods belonging to an asset.")
    public Integer list(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) throws Exception {
        List<PodDetail> pods = cluster.listPods(id);
        System.out.println(json ? Out.json(pods) : Out.yaml(pods));
        return 0;
    }

    @Command(name = "logs", description = "kubectl logs for one pod.")
    public Integer logs(
        @Parameters(paramLabel = "ID") String id,
        @Parameters(paramLabel = "POD") String pod,
        @Option(names = "--container") String container,
        @Option(names = "--tail", defaultValue = "200") int tail
    ) throws Exception {
        System.out.print(cluster.logs(id, pod, container, tail));
        return 0;
    }

    @Command(name = "describe", description = "kubectl describe pod.")
    public Integer describe(@Parameters(paramLabel = "ID") String id, @Parameters(paramLabel = "POD") String pod) throws Exception {
        System.out.print(cluster.describe(id, pod));
        return 0;
    }

    @Command(name = "events", description = "Recent events in the asset's namespace.")
    public Integer events(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) throws Exception {
        List<PodEvent> evts = cluster.events(id);
        System.out.println(json ? Out.json(evts) : Out.yaml(evts));
        return 0;
    }
}
