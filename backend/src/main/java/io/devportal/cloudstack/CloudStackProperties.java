package io.devportal.cloudstack;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cloudstack.*} — the link to cloud-stack, the agent/SDLC platform.
 *
 * @param apiUrl     cloud-stack API base URL (env CLOUDSTACK_API_URL), e.g.
 *                   {@code http://cloudstack-api.cloudstack.svc.cluster.local:8080}. Blank = feature off.
 * @param token      bearer token for the cloud-stack API (env CLOUDSTACK_TOKEN, from a Secret).
 *                   Never logged or returned: {@link #toString()} masks it.
 * @param uiUrl      cloud-stack UI base URL (env CLOUDSTACK_UI_URL), used to build "New task" links
 *                   when cloud-stack does not send one.
 * @param timeoutMs  connect + request timeout per call to cloud-stack (keep short; page loads wait on it)
 * @param cacheTtlMs how long one repo's answer is reused
 */
@ConfigurationProperties(prefix = "cloudstack")
public record CloudStackProperties(String apiUrl, String token, String uiUrl, Long timeoutMs, Long cacheTtlMs) {

    public CloudStackProperties {
        apiUrl = trimSlash(apiUrl);
        uiUrl = trimSlash(uiUrl);
        token = token == null ? null : token.trim();
        if (timeoutMs == null || timeoutMs <= 0) timeoutMs = 3_000L;
        if (cacheTtlMs == null || cacheTtlMs < 0) cacheTtlMs = 30_000L;
    }

    public boolean configured() {
        return apiUrl != null && !apiUrl.isBlank();
    }

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    private static String trimSlash(String s) {
        if (s == null) return null;
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** Records print every component; the token must never reach a log line. */
    @Override
    public String toString() {
        return "CloudStackProperties[apiUrl=" + apiUrl + ", token=" + (hasToken() ? "***" : "<unset>")
            + ", uiUrl=" + uiUrl + ", timeoutMs=" + timeoutMs + ", cacheTtlMs=" + cacheTtlMs + "]";
    }
}
