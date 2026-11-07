package io.devportal.registry;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure helpers for the deployment registry: keys, slugs, validation, health classification. */
public final class Deployments {

    private Deployments() {}

    public static final Set<String> TARGET_KINDS = Set.of("k8s-cluster", "host", "faas", "docker", "mac", "other");
    public static final Set<String> PHASES = Set.of("running", "delivered");
    /** Statuses a reporter may claim. 'retired' is set by the registry only. */
    public static final Set<String> REPORTABLE_STATUSES = Set.of("reported", "healthy", "degraded", "down", "unknown");
    public static final Set<String> ASSET_TYPES = Set.of("library", "service", "meta-asset");

    private static final Pattern ASSET_ID = Pattern.compile("^[a-z][a-z0-9-]{1,62}[a-z0-9]$");

    public static String key(String assetId, String target, String namespace, String name) {
        return assetId + "|" + target + "|" + nz(namespace) + "|" + nz(name);
    }

    public static boolean validAssetId(String id) {
        return id != null && ASSET_ID.matcher(id).matches();
    }

    /**
     * Turns a name or repo URL into an asset id matching {@code ^[a-z][a-z0-9-]{1,62}[a-z0-9]$}:
     * "Lantern Market" -> "lantern-market", "https://github.com/me/Swift_Hello.git" -> "swift-hello".
     * Returns null when nothing usable is left.
     */
    public static String slugify(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.contains("://") || s.startsWith("git@")) {
            s = s.replaceAll("[/]+$", "");
            s = s.substring(Math.max(s.lastIndexOf('/'), s.lastIndexOf(':')) + 1);
            if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        }
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        s = s.replaceAll("^[0-9-]+", "");          // must start with a letter
        if (s.length() > 64) s = s.substring(0, 64).replaceAll("-+$", "");
        if (s.length() < 3) return null;
        return s;
    }

    /** The URL to probe: explicit health URL, else the primary (or first) http(s) URL of a running deployment. */
    public static String probeUrl(String healthUrl, String phase, List<DeploymentUrl> urls) {
        if (isHttp(healthUrl)) return healthUrl;
        if (!"running".equals(phase) || urls == null) return null;
        for (DeploymentUrl u : urls) {
            if (u != null && u.primary() && isHttp(u.url())) return u.url();
        }
        for (DeploymentUrl u : urls) {
            if (u != null && isHttp(u.url())) return u.url();
        }
        return null;
    }

    public static boolean isHttp(String url) {
        if (url == null) return false;
        String l = url.trim().toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    /** Health check outcome for an HTTP status code. */
    public record Verdict(String status, String error) {}

    /**
     * 2xx/3xx: healthy. 401/403: healthy (it answered; it just wants a login).
     * 502/503/504: down (usually a proxy or NodePort in front of nothing ready).
     * Anything else: degraded.
     */
    public static Verdict classify(int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 400) return new Verdict("healthy", null);
        if (httpStatus == 401 || httpStatus == 403) return new Verdict("healthy", null);
        if (httpStatus == 502 || httpStatus == 503 || httpStatus == 504) return new Verdict("down", "HTTP " + httpStatus);
        return new Verdict("degraded", "HTTP " + httpStatus);
    }

    static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
