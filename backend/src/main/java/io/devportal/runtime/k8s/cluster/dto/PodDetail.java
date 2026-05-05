package io.devportal.runtime.k8s.cluster.dto;

import java.util.List;
import java.util.Map;

public record PodDetail(
    String name,
    String namespace,
    String phase,            // Pending | Running | Succeeded | Failed | Unknown
    String node,
    String podIp,
    int readyContainers,
    int totalContainers,
    int restartCount,
    String startTime,
    Map<String, String> labels,
    List<ContainerDetail> containers
) {
    public record ContainerDetail(
        String name,
        String image,
        boolean ready,
        int restartCount,
        String state,            // running | waiting | terminated
        String reason,           // for waiting/terminated
        String lastTermReason,
        Integer lastTermExitCode,
        List<EnvVar> env,
        List<Integer> ports
    ) {}
    public record EnvVar(String name, String value) {}
}
