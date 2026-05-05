package io.devportal.runtime.k8s.cluster;

import io.devportal.runtime.k8s.cluster.dto.PodDetail;
import io.devportal.runtime.k8s.cluster.dto.PodEvent;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClusterController {

    private final ClusterService cluster;

    public ClusterController(ClusterService cluster) { this.cluster = cluster; }

    @GetMapping("/api/assets/{id}/k8s/pods")
    public List<PodDetail> pods(@PathVariable String id) throws IOException, InterruptedException {
        return cluster.listPods(id);
    }

    @GetMapping(value = "/api/assets/{id}/k8s/pods/{pod}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(
        @PathVariable String id,
        @PathVariable("pod") String podName,
        @RequestParam(required = false) String container,
        @RequestParam(required = false, defaultValue = "200") int tail
    ) throws IOException, InterruptedException {
        return cluster.logs(id, podName, container, tail);
    }

    @GetMapping(value = "/api/assets/{id}/k8s/pods/{pod}/describe", produces = MediaType.TEXT_PLAIN_VALUE)
    public String describe(@PathVariable String id, @PathVariable("pod") String podName)
        throws IOException, InterruptedException {
        return cluster.describe(id, podName);
    }

    @GetMapping("/api/assets/{id}/k8s/events")
    public List<PodEvent> events(@PathVariable String id) throws IOException, InterruptedException {
        return cluster.events(id);
    }
}
