package io.devportal.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code devportal.registry.*}.
 *
 * @param token  shared secret required in {@code X-DevHub-Token} for writes to
 *               {@code /api/deployments/**} when non-blank (env DEVHUB_REGISTRY_TOKEN).
 *               Reads stay open, like the rest of the portal.
 * @param health background health poller settings
 */
@ConfigurationProperties(prefix = "devportal.registry")
public record RegistryProperties(String token, Health health) {

    public RegistryProperties {
        if (health == null) health = new Health(true, 60_000, 15_000, 3_000, 8);
    }

    public boolean tokenRequired() {
        return token != null && !token.isBlank();
    }

    /**
     * @param enabled        run the poller at all
     * @param intervalMs     delay between the end of one sweep and the start of the next
     * @param initialDelayMs delay after startup before the first sweep
     * @param timeoutMs      connect + request timeout per probe (keep short)
     * @param concurrency    probes in flight at once
     */
    public record Health(boolean enabled, long intervalMs, long initialDelayMs, long timeoutMs, int concurrency) {}
}
