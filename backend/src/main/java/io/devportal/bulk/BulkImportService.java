package io.devportal.bulk;

import io.devportal.analyze.AnalyzeService;
import io.devportal.analyze.dto.AnalyzeReport;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.AssetService;
import io.devportal.asset.dto.RegisterFromGitHubRequest;
import io.devportal.asset.error.ConflictException;
import io.devportal.bulk.dto.BulkImportRequest;
import io.devportal.bulk.dto.ImportJob;
import io.devportal.discover.ResourceDetector;
import io.devportal.discover.dto.DiscoveredResources;
import io.devportal.github.GitHubClient;
import io.devportal.github.GitHubRepoSummary;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a bulk import: list org repos → filter → register → clone → analyze → auto-wire.
 *
 * <p>Job state lives in memory keyed by id. Progress is updated as each repo is processed; the
 * frontend polls {@link #get(long)} for live updates.
 */
@Service
public class BulkImportService {

    private static final Logger log = LoggerFactory.getLogger(BulkImportService.class);

    private final GitHubClient github;
    private final AssetRepository assets;
    private final AssetService assetService;
    private final AnalyzeService analyze;
    private final ResourceDetector detector;
    private final WorkspaceService workspace;
    private final java.util.concurrent.Executor executor;

    private final Map<Long, MutableJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public BulkImportService(GitHubClient github, AssetRepository assets, AssetService assetService,
                             AnalyzeService analyze, ResourceDetector detector,
                             WorkspaceService workspace,
                             @Qualifier("buildExecutor") java.util.concurrent.Executor executor) {
        this.github = github;
        this.assets = assets;
        this.assetService = assetService;
        this.analyze = analyze;
        this.detector = detector;
        this.workspace = workspace;
        this.executor = executor;
    }

    public ImportJob start(String owner, BulkImportRequest request) {
        // The same import already queued or running (a second click, a second tab) is that job,
        // not another one cloning the same repositories into the same workspaces.
        for (MutableJob j : jobs.values()) {
            if (("queued".equals(j.status) || "running".equals(j.status)) && !j.cancelled
                && j.owner.equalsIgnoreCase(owner) && j.request.equals(request)) {
                return snapshot(j);
            }
        }
        long id = nextId.getAndIncrement();
        MutableJob job = new MutableJob(id, owner, request);
        jobs.put(id, job);
        executor.execute(() -> run(job));
        return snapshot(job);
    }

    public ImportJob get(long id) {
        MutableJob j = jobs.get(id);
        if (j == null) return null;
        return snapshot(j);
    }

    public List<ImportJob> list() {
        return jobs.values().stream()
            .sorted((a, b) -> Long.compare(b.id, a.id))
            .map(this::snapshot)
            .toList();
    }

    /**
     * Asks a job to stop. It stops at the next repository (or language check): the repository
     * being imported finishes first. Returns false for an unknown or finished job.
     */
    public boolean cancel(long id, String reason) {
        MutableJob j = jobs.get(id);
        if (j == null || !("queued".equals(j.status) || "running".equals(j.status))) return false;
        j.cancelled = true;
        j.append("Cancel requested" + (reason == null || reason.isBlank() ? "" : ": " + reason)
            + "; stopping after the current step.");
        return true;
    }

    /** Thrown inside a job when it was cancelled. */
    private static final class Cancelled extends RuntimeException {
        Cancelled() { super("cancelled", null, false, false); }
    }

    private static void checkCancelled(MutableJob job) {
        if (job.cancelled) throw new Cancelled();
    }

    private void run(MutableJob job) {
        job.status = "running";
        job.startedAt = Instant.now();
        try {
            job.phase = "listing";
            job.append("Listing repos in " + job.owner + "…");
            List<GitHubRepoSummary> all = github.listOrgRepos(job.owner, n -> {
                job.phase = "listing (" + n + " so far)";
                job.append("  listed " + n + " so far…");
            });
            job.append("Listed " + all.size() + " repos for " + job.owner);
            checkCancelled(job);

            job.phase = "filtering";
            List<GitHubRepoSummary> matched = filter(all, job.request, job);
            job.totalMatched = matched.size();
            job.append("Filtered to " + matched.size() + " repos matching criteria");

            job.phase = "importing";
            for (GitHubRepoSummary repo : matched) {
                checkCancelled(job);
                job.currentRepo = repo.fullName();
                job.phase = "importing " + (job.processed + 1) + "/" + matched.size();
                processOne(job, repo);
                job.processed++;
            }

            if (Boolean.TRUE.equals(job.request.autoWireMavenDeps())) {
                job.phase = "auto-wiring";
                job.append("Auto-wiring Maven dependencies across imported assets…");
                for (var entry : new ArrayList<>(job.results)) {
                    checkCancelled(job);
                    String assetId = (String) entry.get("assetId");
                    if (assetId == null) continue;
                    try {
                        var wired = analyze.autoWire(assetId);
                        if (wired.added() > 0) {
                            job.autoWired += wired.added();
                            job.append("  " + assetId + ": " + wired.added() + " new edge(s) -> "
                                + String.join(", ", wired.wiredProducers()));
                        }
                    } catch (Exception e) {
                        job.append("  " + assetId + ": auto-wire skipped (" + e.getMessage() + ")");
                    }
                }
            }

            job.currentRepo = null;
            job.phase = "done";
            job.status = "succeeded";
        } catch (Cancelled c) {
            job.currentRepo = null;
            job.phase = "cancelled";
            job.status = "cancelled";
            job.append("Cancelled after " + job.processed + " of " + job.totalMatched + " repos.");
        } catch (Exception e) {
            log.error("Bulk import {} failed", job.id, e);
            job.error = e.getMessage();
            job.phase = "failed";
            job.status = "failed";
        } finally {
            job.finishedAt = Instant.now();
        }
    }

    private void processOne(MutableJob job, GitHubRepoSummary repo) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("fullName", repo.fullName());
        entry.put("language", repo.primaryLanguage());
        try {
            String assetId = slugify(repo.name());
            entry.put("assetId", assetId);

            if (assets.existsById(assetId)) {
                job.alreadyRegistered++;
                entry.put("status", "already-registered");
                job.append(repo.fullName() + " — already registered as " + assetId);
            } else {
                try {
                    assetService.registerFromGitHub(new RegisterFromGitHubRequest(repo.fullName(), assetId));
                    job.registered++;
                    entry.put("status", "registered");
                    job.append(repo.fullName() + " — registered as " + assetId);
                } catch (ConflictException ce) {
                    // Race or asset registered with a different id derived from the manifest.
                    job.alreadyRegistered++;
                    entry.put("status", "already-registered");
                    job.append(repo.fullName() + " — " + ce.getMessage());
                }
            }

            if (Boolean.TRUE.equals(job.request.cloneAndAnalyze())) {
                workspace.syncCheckout(assetId, repo.htmlUrl(), repo.defaultBranch());
                entry.put("cloned", true);

                AnalyzeReport report = analyze.analyze(assetId);
                entry.put("foundPom", report.foundPom());
                entry.put("publishedArtifacts", report.publishedArtifacts().size());
                entry.put("declaredDependencies", report.dependencyMatches().size());
                entry.put("matchedDependencies",
                    report.dependencyMatches().stream().filter(m -> m.matchedAssetId() != null).count());
                if (report.foundPom()) job.analyzed++;

                DiscoveredResources discovered = detector.scan(workspace.workspaceFor(assetId));
                entry.put("dockerfiles", discovered.dockerfiles());
                entry.put("kubernetesPaths", discovered.kubernetesPaths());
                entry.put("helmCharts", discovered.helmCharts());
                entry.put("detectedLanguage", discovered.detectedLanguage());
            }
        } catch (Exception e) {
            entry.put("status", "error");
            entry.put("error", e.getMessage());
            job.append(repo.fullName() + " — error: " + e.getMessage());
        }
        job.results.add(entry);
    }

    private List<GitHubRepoSummary> filter(List<GitHubRepoSummary> repos, BulkImportRequest req, MutableJob job) {
        boolean skipArchived = req.skipArchived() == null || req.skipArchived();
        boolean skipForks = req.skipForks() == null || req.skipForks();
        List<String> langs = req.languages() == null ? List.of()
            : req.languages().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        List<Pattern> include = compile(req.includePatterns());
        List<Pattern> exclude = compile(req.excludePatterns());

        // Inclusion semantics:
        //   keep = (language matches)  OR  (include pattern matches)
        //   then minus exclude pattern, archived (if skip), forks (if skip).
        // If neither languages nor includePatterns is set, keep everything (subject to exclude).
        boolean filtersDefined = !langs.isEmpty() || !include.isEmpty();
        Map<String, Boolean> keep = new LinkedHashMap<>();
        List<GitHubRepoSummary> needLanguages = new ArrayList<>();
        for (GitHubRepoSummary r : repos) {
            if (skipArchived && r.archived()) continue;
            if (skipForks && r.fork()) continue;
            if (!exclude.isEmpty() && exclude.stream().anyMatch(p -> p.matcher(r.name()).find())) continue;
            if (!filtersDefined) { keep.put(r.fullName(), true); continue; }

            boolean languageHit = !langs.isEmpty() && r.primaryLanguage() != null
                && langs.contains(r.primaryLanguage().toLowerCase(Locale.ROOT));
            boolean includeHit = !include.isEmpty()
                && include.stream().anyMatch(p -> p.matcher(r.name()).find());
            if (languageHit || includeHit) {
                keep.put(r.fullName(), true);
            } else if (!langs.isEmpty() && r.primaryLanguage() != null) {
                // GitHub's primaryLanguage is just "biggest byte share". A single CUDA file, big
                // notebook, or vendored asset can flip it: ask for all its languages and accept
                // the repo if any requested one appears. (No primary language means GitHub found
                // no code at all, so there is nothing to ask about.)
                needLanguages.add(r);
            }
        }

        if (!needLanguages.isEmpty()) {
            job.append("Checking all languages of " + needLanguages.size()
                + " repos whose main language is not selected…");
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(LANGUAGE_CHECKS);
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
            Map<String, Boolean> hits = new ConcurrentHashMap<>();
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (GitHubRepoSummary r : needLanguages) {
                    futures.add(pool.submit(() -> {
                        if (job.cancelled) return;
                        try {
                            var allLangs = github.languagesOf(r.fullName());
                            if (langs.stream().anyMatch(allLangs::contains)) hits.put(r.fullName(), true);
                        } catch (IOException ignored) {
                            // network blip / 404: not kept
                        }
                        int n = done.incrementAndGet();
                        job.phase = "filtering: languages " + n + "/" + needLanguages.size();
                        if (n % 25 == 0) job.append("  checked " + n + "/" + needLanguages.size() + "…");
                    }));
                }
                for (var f : futures) {
                    try {
                        f.get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Cancelled();
                    } catch (java.util.concurrent.ExecutionException ignored) {
                        // counted as not kept
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            checkCancelled(job);
            job.append("  " + hits.size() + " of them use a selected language");
            keep.putAll(hits);
        }

        List<GitHubRepoSummary> out = new ArrayList<>();
        for (GitHubRepoSummary r : repos) {
            if (keep.containsKey(r.fullName())) out.add(r);
        }
        return out;
    }

    /** Language lookups run this many at a time. */
    private static final int LANGUAGE_CHECKS = 8;

    private List<Pattern> compile(List<String> patterns) {
        if (patterns == null) return List.of();
        List<Pattern> out = new ArrayList<>();
        for (String p : patterns) {
            if (p == null || p.isBlank()) continue;
            try { out.add(Pattern.compile(p)); }
            catch (PatternSyntaxException e) { /* skip bad pattern */ }
        }
        return out;
    }

    private static String slugify(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-")
            .replaceAll("^-+|-+$", "");
    }

    private ImportJob snapshot(MutableJob j) {
        return new ImportJob(
            j.id, j.owner, j.request, j.status, j.phase, j.startedAt, j.finishedAt, j.currentRepo,
            j.totalMatched, j.processed, j.registered, j.alreadyRegistered,
            j.analyzed, j.autoWired,
            new ArrayList<>(j.logLines), new ArrayList<>(j.results), j.error
        );
    }

    private static class MutableJob {
        final long id;
        final String owner;
        final BulkImportRequest request;
        volatile String status = "queued";
        volatile String phase = "queued";
        volatile boolean cancelled;
        volatile Instant startedAt;
        volatile Instant finishedAt;
        volatile String currentRepo;
        volatile int totalMatched;
        volatile int processed;
        volatile int registered;
        volatile int alreadyRegistered;
        volatile int analyzed;
        volatile int autoWired;
        final List<String> logLines = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Map<String, Object>> results = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String error;

        MutableJob(long id, String owner, BulkImportRequest req) {
            this.id = id;
            this.owner = owner;
            this.request = req;
        }
        void append(String line) {
            logLines.add(Instant.now() + " " + line);
        }
    }
}
