package io.devportal.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devportal.workspace")
public record WorkspaceProperties(String dir) {}
