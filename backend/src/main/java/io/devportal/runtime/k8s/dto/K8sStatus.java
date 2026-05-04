package io.devportal.runtime.k8s.dto;

import java.util.List;

public record K8sStatus(
    String namespace,
    List<Pod> pods,
    List<Service> services
) {
    public record Pod(String name, String phase, String node, String startTime) {}
    public record Service(String name, String type, String clusterIp, List<Integer> ports) {}
}
