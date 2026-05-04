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

    private void run(MutableJob job) {
        job.status = "running";
        job.startedAt = Instant.now();
        try {
            job.append("Listing repos in " + job.owner + " (this can take 30-60s for large orgs)…");
            List<GitHubRepoSummary> all = github.listOrgRepos(job.owner);
            job.append("Listed " + all.size() + " repos for " + job.owner);

            List<GitHubRepoSummary> matched = filter(all, job.request);
            job.totalMatched = matched.size();
            job.append("Filtered to " + matched.size() + " repos matching criteria");

            for (GitHubRepoSummary repo : matched) {
                job.currentRepo = repo.fullName();
                processOne(job, repo);
                job.processed++;
            }

            if (Boolean.TRUE.equals(job.request.autoWireMavenDeps())) {
                job.append("Auto-wiring Maven dependencies across imported assets…");
                for (var entry : new ArrayList<>(job.results)) {
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
            job.status = "succeeded";
        } catch (Exception e) {
            log.error("Bulk import {} failed", job.id, e);
            job.error = e.getMessage();
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

    private List<GitHubRepoSummary> filter(List<GitHubRepoSummary> repos, BulkImportRequest req) {
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
        List<GitHubRepoSummary> out = new ArrayList<>();
        for (GitHubRepoSummary r : repos) {
            if (skipArchived && r.archived()) continue;
            if (skipForks && r.fork()) continue;
            if (!exclude.isEmpty() && exclude.stream().anyMatch(p -> p.matcher(r.name()).find())) continue;

            boolean filtersDefined = !langs.isEmpty() || !include.isEmpty();
            if (!filtersDefined) { out.add(r); continue; }

            boolean languageHit = !langs.isEmpty() && r.primaryLanguage() != null
                && langs.contains(r.primaryLanguage().toLowerCase(Locale.ROOT));
            boolean includeHit  = !include.isEmpty()
                && include.stream().anyMatch(p -> p.matcher(r.name()).find());
            if (languageHit || includeHit) out.add(r);
        }
        return out;
    }

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
            j.id, j.owner, j.request, j.status, j.startedAt, j.finishedAt, j.currentRepo,
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
