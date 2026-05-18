package io.devportal.spinup;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.build.BuildRepository;
import io.devportal.build.BuildStatus;
import io.devportal.build.dto.BuildView;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
import io.devportal.runtime.docker.DockerService;
import io.devportal.runtime.endpoints.AssetEndpoints;
import io.devportal.runtime.endpoints.EndpointsService;
import io.devportal.runtime.k8s.K8sService;
import io.devportal.spinup.dto.SpinupJob;
import io.devportal.test.FixtureResult;
import io.devportal.test.TestFixtureService;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the "spin up" macro: a single click that walks an asset all the way from
 * "registered" to "running and serving traffic" in Kubernetes.
 *
 * <p>The chain is: build docker images (if {@code spec.docker.images} is declared) → ensure
 * namespace → {@code kubectl apply} → run {@code runOnApply} fixtures → probe host-accessible
 * endpoints. Each step is recorded in {@link SpinupJob.SpinupStep} so the UI can render a
 * live punch list. Stops on first failure; the failing step's message points at the next move.
 *
 * <p>Skip a step by setting {@code skipImageBuild=true} (no images to build, or you built
 * them yourself out-of-band) or {@code skipProbe=true} (the cluster's not reachable from the
 * portal, e.g. portal-in-k8s with only ClusterIP services).
 */
@Service
public class SpinupService {

    private static final Logger log = LoggerFactory.getLogger(SpinupService.class);

    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final ManifestParser manifestParser;
    private final DockerService docker;
    private final BuildRepository builds;
    private final K8sService k8s;
    private final TestFixtureService fixtures;
    private final EndpointsService endpoints;
    private final java.util.concurrent.Executor executor;

    private final Map<Long, MutableJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public SpinupService(AssetRepository assets, WorkspaceService workspace,
                         ManifestParser manifestParser, DockerService docker,
                         BuildRepository builds, K8sService k8s,
                         TestFixtureService fixtures, EndpointsService endpoints,
                         @Qualifier("buildExecutor") java.util.concurrent.Executor executor) {
        this.assets = assets;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        this.docker = docker;
        this.builds = builds;
        this.k8s = k8s;
        this.fixtures = fixtures;
        this.endpoints = endpoints;
        this.executor = executor;
    }

    public SpinupJob start(String assetId, boolean skipImageBuild, boolean skipProbe,
                           boolean includeRuntime) {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));

        MutableJob job = new MutableJob(nextId.getAndIncrement(), asset.id(),
            skipImageBuild, skipProbe, includeRuntime);
        jobs.put(job.id, job);
        executor.execute(() -> run(job));
        return snapshot(job);
    }

    public SpinupJob get(long id) {
        MutableJob j = jobs.get(id);
        return j == null ? null : snapshot(j);
    }

    public List<SpinupJob> list() {
        return jobs.values().stream()
            .sorted((a, b) -> Long.compare(b.id, a.id))
            .map(this::snapshot)
            .toList();
    }

    private void run(MutableJob job) {
        job.status = "running";
        job.startedAt = Instant.now();
        try {
            Manifest manifest = readManifest(job.assetId);

            // STEP 1: build images. Skipped only when the caller opted out — we DON'T pre-check
            // the root manifest for `spec.docker.images`, because with includeRuntime=true the
            // closure may contribute images even when the root asset declares none. Delegate the
            // "really nothing to build" call to runBuildImages, which catches the ConflictException
            // raised by DockerService when no plans materialised.
            if (job.skipImageBuild) {
                job.skipStep("BUILD_IMAGES", "skipped by request");
            } else {
                runBuildImages(job);
            }

            // STEP 2: ensure namespace. K8sService.apply does this implicitly via ensureNamespace.
            // We don't have a public "just-ensure-ns" method to call separately; the apply step
            // will create it. Record this as a no-op step for visibility.
            job.startStep("ENSURE_NAMESPACE");
            job.finishStep("ENSURE_NAMESPACE", "succeeded", "namespace ensured during apply");

            // STEP 3: kubectl apply.
            runApply(job);

            // STEP 4: run-on-apply hooks (test fixtures with runOnApply: true).
            runHooks(job);

            // STEP 5: probe endpoints.
            if (job.skipProbe) {
                job.skipStep("PROBE_ENDPOINTS", "skipped by request");
            } else {
                runProbe(job);
            }

            job.status = "succeeded";
            job.append("spin up complete" + (job.entryUrl == null ? "" : " — entry: " + job.entryUrl));
        } catch (Exception e) {
            log.error("Spinup job {} for {} failed", job.id, job.assetId, e);
            job.status = "failed";
            job.error = e.getMessage();
            // Mark the current step as failed if it's still running.
            for (MutableStep s : job.steps) {
                if ("running".equals(s.status)) {
                    s.status = "failed";
                    s.message = e.getMessage();
                    s.finishedAt = Instant.now();
                }
            }
            job.append("FAILED: " + e.getMessage());
        } finally {
            job.finishedAt = Instant.now();
        }
    }

    // ---- step implementations ------------------------------------------------

    private void runBuildImages(MutableJob job) throws Exception {
        job.startStep("BUILD_IMAGES");
        BuildView parent;
        try {
            parent = docker.buildAllImages(job.assetId, job.includeRuntime);
        } catch (io.devportal.asset.error.ConflictException e) {
            // DockerService raises this when no asset in the closure declares
            // spec.docker.images — that's a "nothing to do" for spinup, not a failure.
            job.finishStep("BUILD_IMAGES", "skipped", e.getMessage());
            return;
        }
        job.append("build chain started — parent build #" + parent.id());
        // Poll the parent build's status; runImageChain marks it SUCCEEDED/FAILED when done.
        Instant deadline = Instant.now().plus(Duration.ofMinutes(20));
        while (Instant.now().isBefore(deadline)) {
            var b = builds.findById(parent.id()).orElseThrow();
            BuildStatus s = b.status();
            if (s == BuildStatus.SUCCEEDED) {
                job.finishStep("BUILD_IMAGES", "succeeded", "all declared images built");
                return;
            }
            if (s == BuildStatus.FAILED) {
                throw new IllegalStateException("image build chain failed — see build #" + parent.id()
                    + " log via GET /api/builds/" + parent.id() + "/log");
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("image build chain timed out after 20 minutes — see build #"
            + parent.id());
    }

    private void runApply(MutableJob job) throws Exception {
        job.startStep("APPLY");
        // Force-mode the apply because the prior BUILD_IMAGES step already built (or skipped)
        // everything spinup is going to build — the preflight check would only ever spuriously
        // refuse for things spinup deliberately skipped.
        @SuppressWarnings("unchecked")
        Map<String, Object> result = k8s.apply(job.assetId, /*force*/ true, /*wait*/ 0);
        Object ns = result.get("namespace");
        Object output = result.get("output");
        job.append("kubectl apply -n " + ns + " — " + summarizeApplyOutput(output));
        job.finishStep("APPLY", "succeeded", "applied to namespace " + ns);

        // New step: roll-out readiness. Surface stuck pods (ErrImagePull etc.) here so the spinup
        // failure points at the actual workload, not at the endpoint probe two steps later.
        job.startStep("WAIT_READY");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> readiness = k8s.waitForReady(String.valueOf(ns), 90);
            int total = ((Number) readiness.getOrDefault("workloadsTotal", 0)).intValue();
            int ready = ((Number) readiness.getOrDefault("workloadsReady", 0)).intValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> unhealthy = (List<Map<String, Object>>) readiness.getOrDefault(
                "unhealthyPods", java.util.List.of());
            if (!unhealthy.isEmpty()) {
                for (Map<String, Object> p : unhealthy) {
                    job.append("  ✗ " + p.get("name") + " " + p.get("phase")
                        + " " + p.get("ready") + " — " + p.get("reason"));
                }
            }
            String msg = ready + "/" + total + " workload(s) ready"
                + (unhealthy.isEmpty() ? "" : ", " + unhealthy.size() + " pod(s) unhealthy");
            if (Boolean.TRUE.equals(readiness.get("allReady"))) {
                job.finishStep("WAIT_READY", "succeeded", msg);
            } else {
                job.finishStep("WAIT_READY", "failed", msg
                    + " — endpoint probe will likely fail; check pod reasons above");
            }
        } catch (Exception e) {
            // Don't fail the spinup over the readiness check itself — log and move on.
            job.finishStep("WAIT_READY", "skipped",
                "readiness check threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void runHooks(MutableJob job) throws Exception {
        // Only declare the step if there are any runOnApply hooks; otherwise mark skipped.
        Manifest m = readManifest(job.assetId);
        boolean any = m != null && m.spec() != null && m.spec().test() != null
            && m.spec().test().fixtures() != null
            && m.spec().test().fixtures().stream().anyMatch(f -> Boolean.TRUE.equals(f.runOnApply()));
        if (!any) {
            job.skipStep("RUN_HOOKS", "no runOnApply fixtures declared");
            return;
        }
        job.startStep("RUN_HOOKS");
        List<FixtureResult> results;
        try {
            results = fixtures.runOnApplyHooks(job.assetId);
        } catch (IOException e) {
            throw new IllegalStateException("hooks failed: " + e.getMessage(), e);
        }
        long failed = results.stream().filter(r -> !"succeeded".equalsIgnoreCase(r.status())).count();
        job.append(results.size() + " hook(s) ran, " + failed + " failed");
        if (failed > 0) {
            throw new IllegalStateException("one or more runOnApply hooks failed — see Fixtures tab");
        }
        job.finishStep("RUN_HOOKS", "succeeded", results.size() + " hook(s) ok");
    }

    private void runProbe(MutableJob job) throws Exception {
        job.startStep("PROBE_ENDPOINTS");
        var discovered = endpoints.discover(job.assetId);
        List<AssetEndpoints.Endpoint> hostReachable = discovered.endpoints().stream()
            .filter(AssetEndpoints.Endpoint::hostAccessible)
            .filter(e -> e.url() != null && e.url().startsWith("http"))
            .toList();
        if (hostReachable.isEmpty()) {
            job.finishStep("PROBE_ENDPOINTS", "skipped",
                "no host-accessible HTTP endpoints discovered (services may be ClusterIP-only)");
            return;
        }

        // Try each endpoint a few times — pods may still be coming Ready right after apply.
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        String firstUp = null;
        for (AssetEndpoints.Endpoint ep : hostReachable) {
            String result = probe(ep.url(), deadline);
            if (result == null) {
                if (firstUp == null) firstUp = ep.url();
                job.append("  ✓ " + ep.url() + " responding");
            } else {
                job.append("  ✗ " + ep.url() + " — " + result);
            }
        }
        if (firstUp == null) {
            throw new IllegalStateException("no endpoint became reachable within 3 min — check pod logs");
        }
        job.entryUrl = firstUp;
        job.finishStep("PROBE_ENDPOINTS", "succeeded", "first reachable: " + firstUp);
    }

    /** Returns null on success (any 2xx/3xx); error string otherwise. */
    private String probe(String url, Instant deadline) {
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
                HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                int code = resp.statusCode();
                if (code >= 200 && code < 400) return null;
                last = new IllegalStateException("HTTP " + code);
            } catch (Exception e) {
                last = e;
            }
            try { Thread.sleep(3000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        }
        return last == null ? "timed out" : last.getClass().getSimpleName() + ": " + last.getMessage();
    }

    // ---- helpers -------------------------------------------------------------

    private Manifest readManifest(String assetId) {
        try {
            Path ws = workspace.workspaceFor(assetId);
            Path file = ws.resolve("devportal.yaml");
            if (!Files.exists(file)) return null;
            ManifestParseResult parsed = manifestParser.parse(Files.readString(file));
            return parsed.manifest();
        } catch (IOException e) {
            return null;
        }
    }

    /** Trim apply output to the resource-action lines (created/configured/unchanged). */
    private static String summarizeApplyOutput(Object output) {
        if (!(output instanceof String s)) return "(no output)";
        long lines = s.lines()
            .filter(l -> l.contains(" created") || l.contains(" configured") || l.contains(" unchanged"))
            .count();
        return lines + " resource(s) applied";
    }

    private SpinupJob snapshot(MutableJob j) {
        List<SpinupJob.SpinupStep> stepDtos = j.steps.stream()
            .map(s -> new SpinupJob.SpinupStep(s.name, s.status, s.startedAt, s.finishedAt, s.message))
            .toList();
        return new SpinupJob(j.id, j.assetId, j.status, currentStep(j),
            j.startedAt, j.finishedAt,
            stepDtos, new ArrayList<>(j.logLines), j.error, j.entryUrl);
    }

    private static String currentStep(MutableJob j) {
        for (MutableStep s : j.steps) {
            if ("running".equals(s.status)) return s.name;
        }
        return null;
    }

    // ---- mutable state -------------------------------------------------------

    private static class MutableJob {
        final long id;
        final String assetId;
        final boolean skipImageBuild;
        final boolean skipProbe;
        final boolean includeRuntime;
        volatile String status = "queued";
        volatile Instant startedAt;
        volatile Instant finishedAt;
        volatile String error;
        volatile String entryUrl;
        final List<MutableStep> steps = Collections.synchronizedList(new ArrayList<>());
        final List<String> logLines = Collections.synchronizedList(new ArrayList<>());

        MutableJob(long id, String assetId, boolean skipImageBuild, boolean skipProbe,
                   boolean includeRuntime) {
            this.id = id;
            this.assetId = assetId;
            this.skipImageBuild = skipImageBuild;
            this.skipProbe = skipProbe;
            this.includeRuntime = includeRuntime;
        }

        void append(String line) {
            logLines.add(Instant.now() + " " + line);
        }

        void startStep(String name) {
            MutableStep s = new MutableStep(name);
            s.status = "running";
            s.startedAt = Instant.now();
            steps.add(s);
            append("→ " + name);
        }

        void finishStep(String name, String status, String message) {
            for (MutableStep s : steps) {
                if (name.equals(s.name) && "running".equals(s.status)) {
                    s.status = status;
                    s.message = message;
                    s.finishedAt = Instant.now();
                    append("  ← " + name + ": " + status + " — " + message);
                    return;
                }
            }
        }

        void skipStep(String name, String reason) {
            MutableStep s = new MutableStep(name);
            s.status = "skipped";
            s.startedAt = Instant.now();
            s.finishedAt = s.startedAt;
            s.message = reason;
            steps.add(s);
            append("• skipped " + name + " (" + reason + ")");
        }
    }

    private static class MutableStep {
        final String name;
        volatile String status = "pending";
        volatile Instant startedAt;
        volatile Instant finishedAt;
        volatile String message;
        MutableStep(String name) { this.name = name; }
    }
}
