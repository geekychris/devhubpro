package io.devportal.state;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devportal.state")
public record StateProperties(String repo) {}
