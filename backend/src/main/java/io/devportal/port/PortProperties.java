package io.devportal.port;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devportal.ports")
public record PortProperties(Range local, @org.springframework.boot.context.properties.bind.Name("k8s-nodeport") Range k8sNodeport) {
    public record Range(int start, int end) {}
}
