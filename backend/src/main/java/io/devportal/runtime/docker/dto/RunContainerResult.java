package io.devportal.runtime.docker.dto;

import java.util.List;

public record RunContainerResult(
    String containerId,
    String name,
    String image,
    List<PortMapping> portMappings,
    String message
) {
    public record PortMapping(String slot, int hostPort, int containerPort, String protocol) {}
}
