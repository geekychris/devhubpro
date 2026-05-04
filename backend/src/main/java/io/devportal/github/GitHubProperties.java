package io.devportal.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devportal.github")
public record GitHubProperties(String token) {}
