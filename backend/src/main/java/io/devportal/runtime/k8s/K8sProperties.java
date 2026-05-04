package io.devportal.runtime.k8s;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

@ConfigurationProperties(prefix = "devportal.k8s")
public record K8sProperties(
    @Name("monitoring-base-url") String monitoringBaseUrl,
    String namespace
) {}
