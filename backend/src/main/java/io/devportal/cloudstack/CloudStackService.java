package io.devportal.cloudstack;

import io.devportal.analyze.GitHubUrlParser;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.cloudstack.CloudStackClient.CloudStackException;
import io.devportal.cloudstack.CloudStackDtos.Activity;
import io.devportal.cloudstack.CloudStackDtos.AssetActivity;
import io.devportal.cloudstack.CloudStackDtos.Jenkins;
import io.devportal.cloudstack.CloudStackDtos.PullRequest;
import io.devportal.cloudstack.CloudStackDtos.Reason;
import io.devportal.cloudstack.CloudStackDtos.Run;
import io.devportal.cloudstack.CloudStackDtos.Status;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * An asset's activity in cloud-stack: open PRs and what they need, the latest Jenkins result,
 * active and recent agent runs, and a "New task" link. Calls cloud-stack server-side with a
 * short timeout and keeps each repo's answer for {@code cloudstack.cache-ttl-ms}; failures are
 * kept for at most {@link #FAILURE_TTL_MS} so a down cloud-stack does not slow every page load.
 */
@Service
public class CloudStackService {

    private static final Logger log = LoggerFactory.getLogger(CloudStackService.class);
    static final long FAILURE_TTL_MS = 10_000;

    private final CloudStackProperties props;
    private final CloudStackClient client;
    private final AssetRepository assets;
    private final LongSupplier clockMs;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(long atMs, Activity activity, CloudStackException error) {}

    @Autowired
    public CloudStackService(CloudStackProperties props, CloudStackClient client, AssetRepository assets) {
        this(props, client, assets, System::currentTimeMillis);
    }

    CloudStackService(CloudStackProperties props, CloudStackClient client, AssetRepository assets, LongSupplier clockMs) {
        this.props = props;
        this.client = client;
        this.assets = assets;
        this.clockMs = clockMs;
    }

    public AssetActivity forAsset(String assetId) {
        Asset a = assets.findById(assetId)
            .orElseThrow(() -> new NotFoundException("Asset '" + assetId + "' not found"));
        return forRepoUrl(a.id(), a.repoUrl());
    }

    AssetActivity forRepoUrl(String assetId, String repoUrl) {
        String repo = githubRepo(repoUrl);
        String now = Instant.ofEpochMilli(clockMs.getAsLong()).toString();
        if (repo == null) {
            String why = repoUrl == null || repoUrl.isBlank()
                ? "This asset has no repository URL, so cloud-stack cannot be asked about it."
                : "This asset's repository is not on GitHub (" + repoUrl + "); cloud-stack works with GitHub repos.";
            return unavailable(assetId, Reason.NO_GITHUB_REPO, why, null, repoUrl, now);
        }
        String canonicalUrl = "https://github.com/" + repo;
        if (!props.configured()) {
            return unavailable(assetId, Reason.NOT_CONFIGURED,
                "cloud-stack is not configured: set CLOUDSTACK_API_URL (and CLOUDSTACK_TOKEN) on the backend.",
                repo, canonicalUrl, now);
        }
        Entry e = lookup(repo);
        if (e.error() != null) {
            return unavailable(assetId, e.error().reason(), e.error().getMessage(), repo, canonicalUrl, now);
        }
        Activity act = e.activity();
        String newTask = safeUrl(act.newTaskUrl());
        if (newTask == null) newTask = buildNewTaskUrl(canonicalUrl);
        return new AssetActivity(assetId, true, null, null, repo, canonicalUrl,
            act.openPRs().stream().map(CloudStackService::clean).toList(),
            clean(act.jenkins()),
            act.runs().stream().map(CloudStackService::clean).toList(),
            newTask, props.uiUrl(), Instant.ofEpochMilli(e.atMs()).toString());
    }

    public Status status() {
        String now = Instant.ofEpochMilli(clockMs.getAsLong()).toString();
        if (!props.configured()) {
            return new Status(false, props.hasToken(), null, props.uiUrl(), null,
                "CLOUDSTACK_API_URL is not set", null, now);
        }
        try {
            int ms = client.ping();
            return new Status(true, props.hasToken(), props.apiUrl(), props.uiUrl(), true, null, ms, now);
        } catch (CloudStackException ex) {
            return new Status(true, props.hasToken(), props.apiUrl(), props.uiUrl(), false, ex.getMessage(), null, now);
        }
    }

    /** Drops cached answers (all repos). */
    public void invalidate() {
        cache.clear();
    }

    private Entry lookup(String repo) {
        String key = repo.toLowerCase(Locale.ROOT);
        long now = clockMs.getAsLong();
        Entry hit = cache.get(key);
        if (hit != null) {
            long ttl = hit.error() == null ? props.cacheTtlMs() : Math.min(props.cacheTtlMs(), FAILURE_TTL_MS);
            if (now - hit.atMs() < ttl) return hit;
        }
        Entry fresh;
        try {
            fresh = new Entry(now, client.activity(repo), null);
        } catch (CloudStackException ex) {
            log.debug("cloud-stack activity for {} unavailable: {}", repo, ex.getMessage());
            fresh = new Entry(now, null, ex);
        }
        cache.put(key, fresh);
        return fresh;
    }

    private AssetActivity unavailable(String assetId, Reason reason, String why, String repo, String repoUrl, String now) {
        String newTask = repoUrl != null && repo != null ? buildNewTaskUrl(repoUrl) : null;
        return new AssetActivity(assetId, false, reason.code, why, repo, repoUrl, List.of(), null, List.of(),
            newTask, props.uiUrl(), now);
    }

    /** {@code <ui-url>/#/new?repo=<url-encoded https://github.com/owner/name>}, or null without a UI URL. */
    String buildNewTaskUrl(String repoUrl) {
        String ui = safeUrl(props.uiUrl());
        if (ui == null) return null;
        return ui + "/#/new?repo=" + URLEncoder.encode(repoUrl, StandardCharsets.UTF_8);
    }

    /** owner/name for a GitHub repo URL (https, ssh, with or without .git), else null. */
    static String githubRepo(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return null;
        return GitHubUrlParser.fullName(repoUrl.trim().replaceFirst("^(https?://)www\\.", "$1"));
    }

    /** Only http(s) links reach the browser (no javascript:, data:, …). */
    static String safeUrl(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            URI u = URI.create(s.trim());
            String scheme = u.getScheme();
            if (scheme == null || u.getHost() == null) return null;
            String sc = scheme.toLowerCase(Locale.ROOT);
            return sc.equals("http") || sc.equals("https") ? s.trim() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PullRequest clean(PullRequest p) {
        return new PullRequest(safeUrl(p.url()), p.number(), p.title(), p.needs(), p.why(),
            lower(p.checks(), "none"), p.runId());
    }

    private static Jenkins clean(Jenkins j) {
        if (j == null) return null;
        return new Jenkins(lower(j.status(), "none"), j.jobName(), j.buildNumber(), j.sha(), safeUrl(j.link()), j.at());
    }

    private static Run clean(Run r) {
        return new Run(r.id(), lower(r.state(), null), r.workflow(), r.title(), safeUrl(r.url()), r.createdAt());
    }

    private static String lower(String s, String dflt) {
        return s == null ? dflt : s.toLowerCase(Locale.ROOT);
    }
}
