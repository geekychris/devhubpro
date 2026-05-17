package io.devportal.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures the host part of URLs the portal surfaces back to users (endpoint discovery,
 * port-forward sessions, verify probes, prompt-context examples, …).
 *
 * <p>Defaults to {@code localhost} which works for direct-mode installs (portal runs on the
 * same machine the user is browsing from). For installs accessed from a different machine —
 * the portal in k8s mode on a server like {@code spark.local}, with the user's browser on
 * their laptop — set {@code devportal.urls.host=spark.local} (or the env var
 * {@code DEVPORTAL_URLS_HOST=spark.local}) so the URLs the UI shows are reachable.
 *
 * <p>Per-asset overrides remain possible via {@code spec.runtime.proxy.host} in the asset's
 * {@code devportal.yaml}; this property is only the global default.
 */
@ConfigurationProperties(prefix = "devportal.urls")
public record UrlsProperties(String host) {
    public UrlsProperties {
        if (host == null || host.isBlank()) host = "localhost";
    }
}
