package io.devportal.runtime.docker.dto;

import java.util.List;

public record DockerContainerView(
    String id,
    String name,
    String image,
    String status,
    List<String> ports
) {}
