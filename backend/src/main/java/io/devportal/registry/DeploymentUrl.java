package io.devportal.registry;

/** A link a deployment exposes. Stored exactly as reported — never rewritten to an IP. */
public record DeploymentUrl(String label, String url, boolean primary) {}
