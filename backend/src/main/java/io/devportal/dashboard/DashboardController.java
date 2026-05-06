package io.devportal.dashboard;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.manifest.Manifest;
import io.devportal.runtime.endpoints.AssetEndpoints;
import io.devportal.runtime.endpoints.EndpointsService;
import io.devportal.test.TestFixtureService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard view: every asset that has a live endpoint, grouped with its description, the URLs
 * to talk to it, fixtures that surface credentials, and detected Swagger/OpenAPI URLs.
 *
 * <p>Swagger discovery: for each host-accessible URL with role "API" or "Web UI", we probe a
 * short list of conventional Swagger paths (concurrent, 800ms each). Detected results are cached
 * per host:port for 60s so dashboard refreshes don't hammer the cluster.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private static final List<String> SWAGGER_PROBES = List.of(
        "/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html",
        "/swagger.json", "/openapi.json"
    );
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build();

    /** Cache: "host:port" -> swagger url (or empty marker) -> expiresAt. */
    private final Map<String, CachedSwagger> swaggerCache = new java.util.concurrent.ConcurrentHashMap<>();

    private final AssetRepository assets;
    private final EndpointsService endpoints;
    private final TestFixtureService fixtures;

    public DashboardController(AssetRepository assets, EndpointsService endpoints,
                               TestFixtureService fixtures) {
        this.assets = assets;
        this.endpoints = endpoints;
        this.fixtures = fixtures;
    }

    public record DashboardEntry(
        AssetSummary asset,
        // Endpoints from EndpointsService, filtered to the host-accessible ones.
        List<AssetEndpoints.Endpoint> endpoints,
        // Web UI URLs (role == "Web UI" or "Admin UI") — promoted in the UI as the "open the app"
        // buttons. Subset of endpoints[].
        List<UiEndpoint> uiEndpoints,
        // API URLs (role == "API" / "WebSocket"). Subset of endpoints[].
        List<UiEndpoint> apiEndpoints,
        // Detected Swagger/OpenAPI URL or null. Probed per-base-URL.
        String swaggerUrl,
        // Fixtures that emit credentials — surfaced as quick-links to the Fixtures tab.
        List<Manifest.TestFixture> credentialFixtures,
        boolean live          // any endpoint marked live
    ) {}

    public record AssetSummary(
        String id,
        String name,
        String description,
        String type,
        String language,
        String lifecycle,
        String repoUrl,
        Boolean favorite,
        Integer rating,
        Boolean dashboardPinned,
        List<String> tags
    ) {
        static AssetSummary of(Asset a) {
            return new AssetSummary(a.id(), a.name(), a.description(), a.type(), a.language(),
                a.lifecycle(), a.repoUrl(), a.favorite(), a.rating(), a.dashboardPinned(), a.tags());
        }
    }

    public record UiEndpoint(String label, String url, String role, boolean live) {}

    @GetMapping("/running")
    public List<DashboardEntry> running() {
        List<DashboardEntry> out = new ArrayList<>();
        for (Asset a : assets.findAll(null, null, null)) {
            AssetEndpoints ep;
            try {
                ep = endpoints.discover(a.id());
            } catch (Exception e) {
                // For pinned assets we still want to show the card so the user can hit Start.
                if (a.dashboardPinned()) {
                    out.add(emptyEntry(a));
                }
                continue;
            }
            // Filter to host-accessible endpoints only, excluding the GitHub-repo "external" entry
            // (every asset has one — including it would dump all 130+ assets into the dashboard).
            // Also drop the noisy convention paths since the Swagger probe already covers them.
            List<AssetEndpoints.Endpoint> hostAcc = ep.endpoints().stream()
                .filter(AssetEndpoints.Endpoint::hostAccessible)
                .filter(e -> !"external".equalsIgnoreCase(e.scope()))
                .filter(e -> !"convention".equalsIgnoreCase(e.scope()))
                .toList();
            // Include the asset when:
            //   - the user pinned it to the dashboard (always show, even if everything's down), OR
            //   - the asset has a runtime surface (registered ports / running containers).
            if (hostAcc.isEmpty() && !a.dashboardPinned()) continue;
            boolean anyLive = hostAcc.stream().anyMatch(AssetEndpoints.Endpoint::live);

            // Promote Web UI / Admin UI / API endpoints into typed lists. Skip the "convention path"
            // rows (/, /actuator/health, /swagger-ui — already covered by Swagger detection).
            List<UiEndpoint> uiList = new ArrayList<>();
            List<UiEndpoint> apiList = new ArrayList<>();
            for (AssetEndpoints.Endpoint e : hostAcc) {
                if ("convention".equalsIgnoreCase(e.scope())) continue;
                String role = parseRole(e.label());
                if ("Web UI".equals(role) || "Admin UI".equals(role)) {
                    uiList.add(new UiEndpoint(strippedLabel(e.label()), e.url(), role, e.live()));
                } else if ("API".equals(role) || "WebSocket".equals(role)) {
                    apiList.add(new UiEndpoint(strippedLabel(e.label()), e.url(), role, e.live()));
                }
            }

            String swaggerUrl = anyLive ? detectSwagger(apiList, uiList) : null;

            // Fixtures: prefer ones marked runOnApply (lifecycle hooks) but include all that look
            // like credential surfacers. Cheap heuristic — look at the name/description.
            List<Manifest.TestFixture> credFixtures = fixtures.list(a.id()).stream()
                .filter(f -> looksLikeCredentialFixture(f))
                .toList();

            out.add(new DashboardEntry(
                AssetSummary.of(a), hostAcc, uiList, apiList, swaggerUrl, credFixtures, anyLive
            ));
        }
        return out;
    }

    private DashboardEntry emptyEntry(Asset a) {
        return new DashboardEntry(
            AssetSummary.of(a), List.of(), List.of(), List.of(), null, List.of(), false
        );
    }

    // ---------- helpers ----------

    /** Pull the role hint out of "frontend — web (Web UI)" → "Web UI". */
    private static String parseRole(String label) {
        if (label == null) return null;
        int open = label.lastIndexOf('(');
        int close = label.lastIndexOf(')');
        if (open < 0 || close <= open) return null;
        return label.substring(open + 1, close).trim();
    }

    /** "social-app — http (API)" → "social-app — http". */
    private static String strippedLabel(String label) {
        if (label == null) return "";
        int open = label.lastIndexOf('(');
        return open > 0 ? label.substring(0, open).trim() : label.trim();
    }

    private static boolean looksLikeCredentialFixture(Manifest.TestFixture f) {
        String name = (f.name() == null ? "" : f.name().toLowerCase());
        String desc = (f.description() == null ? "" : f.description().toLowerCase());
        return name.contains("account") || name.contains("credential") || name.contains("login")
            || name.contains("seed") || name.contains("user")
            || desc.contains("credential") || desc.contains("login") || desc.contains("admin");
    }

    /** Probe a small list of Swagger paths under each base URL, return first match or null. */
    private String detectSwagger(List<UiEndpoint> apis, List<UiEndpoint> uis) {
        // Try API URLs first (most likely to host /v3/api-docs), then UI URLs.
        List<UiEndpoint> all = new ArrayList<>();
        all.addAll(apis); all.addAll(uis);
        for (UiEndpoint e : all) {
            if (e.url() == null) continue;
            String base = baseOf(e.url());
            if (base == null) continue;
            CachedSwagger c = swaggerCache.get(base);
            if (c != null && c.expiresAt > System.currentTimeMillis()) {
                if (c.url != null && !c.url.isEmpty()) return c.url;
                continue;
            }
            String found = probeSwaggerOn(base);
            swaggerCache.put(base,
                new CachedSwagger(found, System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60)));
            if (found != null) return found;
        }
        return null;
    }

    private static String baseOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) { return null; }
    }

    /** Try each conventional path; return the first that responds 2xx. Concurrent. 800ms each. */
    private String probeSwaggerOn(String base) {
        List<CompletableFuture<String>> futures = new ArrayList<>(SWAGGER_PROBES.size());
        for (String path : SWAGGER_PROBES) {
            String url = base + path;
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(800))
                .GET().build();
            futures.add(HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenApply(r -> {
                    int code = r.statusCode();
                    return (code >= 200 && code < 300) ? url : null;
                })
                .exceptionally(t -> null));
        }
        for (CompletableFuture<String> f : futures) {
            try {
                String r = f.get(1, TimeUnit.SECONDS);
                if (r != null) return r;
            } catch (InterruptedException | ExecutionException | TimeoutException ignored) {}
        }
        return null;
    }

    private record CachedSwagger(String url, long expiresAt) {}
}
