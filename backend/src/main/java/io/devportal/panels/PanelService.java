package io.devportal.panels;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.audit.AuditService;
import io.devportal.audit.dto.AuditFinding;
import io.devportal.audit.dto.AuditReport;
import io.devportal.discover.ResourceDetector;
import io.devportal.discover.dto.DiscoveredResources;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Service;

/** Builds the server-driven panel feed for an asset detail view. */
@Service
public class PanelService {

    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final ManifestParser manifestParser;
    private final AuditService audit;
    private final ResourceDetector detector;
    private final io.devportal.build.BuildRepository builds;
    private final io.devportal.test.TestFixtureService fixtures;
    private final io.devportal.runtime.k8s.K8sDiagnosticsService diagnostics;
    private final io.devportal.workspace.WorkspaceStatusService workspaceStatus;
    private final io.devportal.runtime.endpoints.EndpointsService endpoints;
    private final io.devportal.runtime.k8s.K8sService k8s;
    private final io.devportal.secret.SecretService secrets;
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(1)).build();

    public PanelService(AssetRepository assets, WorkspaceService workspace,
                        ManifestParser manifestParser, AuditService audit,
                        ResourceDetector detector,
                        io.devportal.build.BuildRepository builds,
                        io.devportal.test.TestFixtureService fixtures,
                        io.devportal.runtime.k8s.K8sDiagnosticsService diagnostics,
                        io.devportal.workspace.WorkspaceStatusService workspaceStatus,
                        io.devportal.runtime.endpoints.EndpointsService endpoints,
                        io.devportal.runtime.k8s.K8sService k8s,
                        io.devportal.secret.SecretService secrets) {
        this.assets = assets;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        this.audit = audit;
        this.detector = detector;
        this.builds = builds;
        this.fixtures = fixtures;
        this.diagnostics = diagnostics;
        this.workspaceStatus = workspaceStatus;
        this.endpoints = endpoints;
        this.k8s = k8s;
        this.secrets = secrets;
    }

    public List<Panel> panelsFor(String assetId) {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));
        List<Panel> out = new ArrayList<>();
        // Original "static" facts panels at the top.
        out.add(githubPanel(asset));
        out.add(workspacePanel(asset));
        out.add(manifestPanel(asset));
        // High-value live panels next.
        out.add(healthSummaryPanel(asset));
        out.add(quickActionsPanel(asset));
        out.add(recentActivityPanel(asset));
        out.add(endpointsTestPanel(asset));
        out.add(driftPanel(asset));
        out.add(resourceUsagePanel(asset));
        out.add(producersStatusPanel(asset));
        out.add(recentCommitsPanel(asset));
        out.add(openPrsPanel(asset));
        // Existing scanners after.
        out.add(resourcesPanel(asset));
        out.add(auditPanel(asset));
        return out;
    }

    // ---------- new panels ----------

    /** Pods running / total + diagnostics findings + dirty workspace files in one glance. */
    private Panel healthSummaryPanel(Asset a) {
        List<String> lines = new ArrayList<>();
        // Diagnostics: pod counts + finding totals.
        try {
            var d = diagnostics.diagnose(a.id());
            var s = d.summary();
            lines.add(String.format("pods:        %d running · %d pending · %d broken (of %d)",
                s.podsRunning(), s.podsPending(), s.podsBroken(), s.podsTotal()));
            lines.add(String.format("findings:    %d errors · %d warnings",
                s.findingsError(), s.findingsWarn()));
        } catch (Exception e) {
            lines.add("k8s:         not reachable (" + e.getClass().getSimpleName() + ")");
        }
        // Workspace dirtiness.
        try {
            var ws = workspaceStatus.status(a.id());
            lines.add(String.format("workspace:   branch=%s · %s · %d dirty file(s)",
                ws.branch(), ws.clean() ? "clean" : "dirty", ws.files().size()));
        } catch (Exception ignored) {
            lines.add("workspace:   not cloned yet");
        }
        return Panel.list("health-summary", "Health summary", lines);
    }

    /** API reference for the most-used per-asset operations. */
    private Panel quickActionsPanel(Asset a) {
        List<String> lines = new ArrayList<>();
        lines.add("# Run a deep build (mvn/cargo/etc per manifest):");
        lines.add("POST /api/assets/" + a.id() + "/builds  body={\"mode\":\"deep\",\"commandName\":\"package\"}");
        lines.add("");
        lines.add("# Apply k8s manifests, auto-fire setup hooks (runOnApply fixtures):");
        lines.add("POST /api/assets/" + a.id() + "/k8s/apply?runHooks=true");
        lines.add("");
        lines.add("# Apply with runtime closure (this asset + its kind=runtime producers):");
        lines.add("POST /api/assets/" + a.id() + "/k8s/apply?include=runtime");
        lines.add("");
        lines.add("# Build all local-tagged images declared in spec.docker.images:");
        lines.add("POST /api/assets/" + a.id() + "/docker/build-images");
        lines.add("");
        lines.add("# Diagnostics — punch list of broken pods + fix hints:");
        lines.add("GET  /api/assets/" + a.id() + "/k8s/diagnostics");
        lines.add("");
        lines.add("# Workspace state (branch, dirty files, ahead/behind origin):");
        lines.add("GET  /api/assets/" + a.id() + "/workspace/status");
        return Panel.list("quick-actions", "Quick actions (API)", lines);
    }

    /** Last 5 builds + last fixture run + last successful apply. */
    private Panel recentActivityPanel(Asset a) {
        List<String> lines = new ArrayList<>();
        var recent = builds.findByAsset(a.id(), 5);
        if (recent.isEmpty()) {
            lines.add("(no builds yet)");
        } else {
            for (var b : recent) {
                String pill = switch (b.status().dbValue()) {
                    case "succeeded" -> "✅";
                    case "failed"    -> "❌";
                    case "running"   -> "▶️";
                    case "queued"    -> "⏳";
                    default          -> "·";
                };
                String dur = (b.startedAt() == null || b.finishedAt() == null) ? ""
                    : String.format(" %.1fs",
                        java.time.Duration.between(b.startedAt(), b.finishedAt()).toMillis() / 1000.0);
                lines.add(String.format("%s #%d %s%s — %s",
                    pill, b.id(), b.commandName(), dur,
                    b.startedAt() == null ? "queued" : b.startedAt().toString()));
            }
        }
        // Last fixture run (any fixture).
        var fxList = fixtures.list(a.id());
        for (var fx : fxList) {
            var last = fixtures.lastRun(a.id(), fx.name()).orElse(null);
            if (last != null) {
                String pill = "succeeded".equals(last.status()) ? "✅" : "❌";
                lines.add(pill + " fixture " + fx.name() + " — last ran "
                    + (last.finishedAt() == null ? "?" : last.finishedAt().toString()));
                break;
            }
        }
        return Panel.list("recent-activity", "Recent activity", lines);
    }

    /** HTTP probe each host-accessible endpoint; report status code + latency. */
    private Panel endpointsTestPanel(Asset a) {
        List<String> lines = new ArrayList<>();
        try {
            var ep = endpoints.discover(a.id());
            int probed = 0;
            for (var e : ep.endpoints()) {
                if (!e.hostAccessible()) continue;
                if ("external".equalsIgnoreCase(e.scope())) continue;
                if ("convention".equalsIgnoreCase(e.scope())) continue;
                if (probed++ >= 6) { lines.add("(more truncated)"); break; }
                long started = System.currentTimeMillis();
                String status;
                try {
                    var r = httpClient.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(e.url()))
                            .timeout(java.time.Duration.ofMillis(800))
                            .method("GET", java.net.http.HttpRequest.BodyPublishers.noBody())
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding());
                    int code = r.statusCode();
                    long dur = System.currentTimeMillis() - started;
                    String mark = (code >= 200 && code < 400) ? "✅" : "⚠️";
                    status = String.format("%s HTTP %d (%dms)", mark, code, dur);
                } catch (Exception x) {
                    long dur = System.currentTimeMillis() - started;
                    status = String.format("❌ %s (%dms)", x.getClass().getSimpleName(), dur);
                }
                lines.add(status + " — " + e.url() + " · " + e.label());
            }
            if (probed == 0) lines.add("(no host-accessible endpoints declared)");
        } catch (Exception e) {
            lines.add("probe failed: " + e.getMessage());
        }
        return Panel.list("endpoints-test", "Endpoints health probe", lines);
    }

    /** Configuration drift: declared in manifest vs running in cluster. */
    private Panel driftPanel(Asset a) {
        List<String> lines = new ArrayList<>();
        try {
            String ns = k8s.effectiveNamespace(a.id());
            // Read declared replicas + image per Deployment from rendered manifests.
            var srcDir = k8s.resolveK8sPath(a.id());
            var declared = readDeployments(srcDir);
            // Cross-reference live Deployments via kubectl.
            var live = liveDeployments(ns);
            for (var entry : declared.entrySet()) {
                String name = entry.getKey();
                DeployFacts d = entry.getValue();
                DeployFacts l = live.get(name);
                if (l == null) {
                    lines.add("⚠ " + name + ": declared but missing in cluster");
                    continue;
                }
                if (!java.util.Objects.equals(d.replicas, l.replicas)) {
                    lines.add(String.format("⚠ %s: replicas declared=%s live=%s",
                        name, d.replicas, l.replicas));
                }
                if (d.image != null && l.image != null && !d.image.equals(l.image)) {
                    lines.add(String.format("⚠ %s: image declared=%s live=%s",
                        name, d.image, l.image));
                }
            }
            for (var entry : live.entrySet()) {
                if (!declared.containsKey(entry.getKey())) {
                    lines.add("ℹ " + entry.getKey() + ": running in cluster but not in manifest tree");
                }
            }
            if (lines.isEmpty()) lines.add("✅ no drift detected");
        } catch (Exception e) {
            lines.add("drift check skipped: " + e.getClass().getSimpleName());
        }
        return Panel.list("drift", "Configuration drift", lines);
    }

    /** Sum CPU/memory across asset's pods using kubectl top. */
    private Panel resourceUsagePanel(Asset a) {
        try {
            String ns = k8s.effectiveNamespace(a.id());
            ProcResult r = exec("kubectl", "top", "pods", "-n", ns, "--no-headers");
            if (r.exitCode != 0) {
                return Panel.list("resource-usage", "Resource usage",
                    List.of("metrics-server unavailable: " + r.output.lines().findFirst().orElse("")));
            }
            long cpuMilli = 0;
            long memMi = 0;
            int pods = 0;
            for (String line : r.output.split("\n")) {
                String[] cols = line.trim().split("\\s+");
                if (cols.length < 3) continue;
                cpuMilli += parseCpuMilli(cols[1]);
                memMi += parseMemMi(cols[2]);
                pods++;
            }
            return Panel.kv("resource-usage", "Resource usage", List.of(
                new Panel.KvItem("namespace", ns),
                new Panel.KvItem("pods (running)", String.valueOf(pods)),
                new Panel.KvItem("CPU (sum)", cpuMilli + "m"),
                new Panel.KvItem("Memory (sum)", memMi + "Mi")
            ));
        } catch (Exception e) {
            return Panel.list("resource-usage", "Resource usage",
                List.of("not available: " + e.getMessage()));
        }
    }

    /** Runtime-edge producer status — for each kind=runtime dep, pod count + endpoints. */
    private Panel producersStatusPanel(Asset a) {
        List<String> lines = new ArrayList<>();
        var deps = assets.findDependenciesOf(a.id());
        boolean any = false;
        for (var d : deps) {
            if (!"runtime".equals(d.kind())) continue;
            any = true;
            String pill;
            try {
                var diag = diagnostics.diagnose(d.producerId());
                int running = diag.summary().podsRunning();
                int total = diag.summary().podsTotal();
                pill = total == 0 ? "·" : (running == total ? "✅" : (running > 0 ? "⚠️" : "❌"));
                lines.add(String.format("%s %s — %d/%d pods running",
                    pill, d.producerId(), running, total));
            } catch (Exception e) {
                lines.add("· " + d.producerId() + " — status unavailable");
            }
        }
        if (!any) lines.add("(no runtime-edge dependencies)");
        return Panel.list("producers-status", "Runtime dependencies", lines);
    }

    /** git log --oneline -5 over the workspace HEAD. */
    private Panel recentCommitsPanel(Asset a) {
        Path ws = workspace.workspaceFor(a.id());
        if (!Files.isDirectory(ws.resolve(".git"))) {
            return Panel.code("recent-commits", "Recent commits", "(workspace not cloned)");
        }
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(ws.resolve(".git").toFile()).build();
             org.eclipse.jgit.api.Git g = new org.eclipse.jgit.api.Git(repo)) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (var c : g.log().setMaxCount(5).call()) {
                String firstLine = c.getShortMessage();
                sb.append(c.getName().substring(0, 8)).append("  ")
                  .append(firstLine.length() > 70 ? firstLine.substring(0, 70) + "…" : firstLine)
                  .append('\n');
                n++;
            }
            if (n == 0) sb.append("(no commits yet)");
            return Panel.code("recent-commits", "Recent commits", sb.toString());
        } catch (Exception e) {
            return Panel.code("recent-commits", "Recent commits", "log failed: " + e.getMessage());
        }
    }

    /** Detect local branches that are ahead of origin and look like devportal/* fix branches. */
    private Panel openPrsPanel(Asset a) {
        Path ws = workspace.workspaceFor(a.id());
        if (!Files.isDirectory(ws.resolve(".git"))) {
            return Panel.list("open-prs", "Branches ready to PR", List.of("(workspace not cloned)"));
        }
        List<Panel.Link> links = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(ws.resolve(".git").toFile()).build()) {
            String fullName = io.devportal.analyze.GitHubUrlParser.fullName(a.repoUrl());
            for (var branch : repo.getRefDatabase().getRefsByPrefix("refs/heads/")) {
                String name = branch.getName().substring("refs/heads/".length());
                if (name.equals("main") || name.equals("master")) continue;
                // Only suggest branches that have local commits beyond origin/<name>.
                org.eclipse.jgit.lib.ObjectId remote = repo.resolve("refs/remotes/origin/" + name);
                org.eclipse.jgit.lib.ObjectId local = branch.getObjectId();
                int ahead;
                try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
                    if (remote == null) {
                        ahead = -1; // never pushed
                    } else if (local.equals(remote)) {
                        ahead = 0;
                    } else {
                        ahead = org.eclipse.jgit.revwalk.RevWalkUtils.count(walk,
                            walk.parseCommit(local), walk.parseCommit(remote));
                    }
                }
                if (ahead == 0) continue;
                if (fullName != null) {
                    String url = "https://github.com/" + fullName + "/pull/new/" + name;
                    String label = ahead < 0
                        ? "Open PR for " + name + " (not pushed yet)"
                        : "Open PR for " + name + " (" + ahead + " ahead of origin)";
                    links.add(new Panel.Link(label, url));
                } else {
                    notes.add(name + " is " + ahead + " ahead of origin");
                }
            }
        } catch (Exception e) {
            return Panel.list("open-prs", "Branches ready to PR",
                List.of("scan failed: " + e.getMessage()));
        }
        if (links.isEmpty() && notes.isEmpty()) {
            return Panel.list("open-prs", "Branches ready to PR",
                List.of("(no branches ahead of origin)"));
        }
        if (!links.isEmpty()) return Panel.links("open-prs", "Branches ready to PR", links);
        return Panel.list("open-prs", "Branches ready to PR", notes);
    }

    // ---------- helpers for new panels ----------

    private record DeployFacts(Integer replicas, String image) {}

    private static java.util.Map<String, DeployFacts> readDeployments(Path src) throws IOException {
        java.util.Map<String, DeployFacts> out = new java.util.LinkedHashMap<>();
        if (!Files.isDirectory(src)) return out;
        var yaml = new com.fasterxml.jackson.databind.ObjectMapper(
            new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        try (var stream = Files.walk(src)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return (n.endsWith(".yaml") || n.endsWith(".yml"))
                        && !n.equals("kustomization.yaml") && !n.equals("kustomization.yml");
                })
                .forEach(p -> {
                    try (var parser = yaml.getFactory().createParser(Files.newBufferedReader(p))) {
                        com.fasterxml.jackson.databind.MappingIterator<com.fasterxml.jackson.databind.JsonNode> it =
                            yaml.readerFor(com.fasterxml.jackson.databind.JsonNode.class).readValues(parser);
                        while (it.hasNext()) {
                            var doc = it.next();
                            if (doc == null || !doc.isObject()) continue;
                            String kind = doc.path("kind").asText("");
                            if (!"Deployment".equalsIgnoreCase(kind)) continue;
                            String name = doc.path("metadata").path("name").asText(null);
                            if (name == null) continue;
                            Integer replicas = doc.path("spec").path("replicas").asInt(1);
                            String image = doc.path("spec").path("template").path("spec")
                                .path("containers").path(0).path("image").asText(null);
                            out.put(name, new DeployFacts(replicas, image));
                        }
                    } catch (IOException ignored) {}
                });
        }
        return out;
    }

    private java.util.Map<String, DeployFacts> liveDeployments(String ns) {
        java.util.Map<String, DeployFacts> out = new java.util.LinkedHashMap<>();
        try {
            // Use -o json + Jackson rather than -o jsonpath so we don't depend on shell tab-escape
            // semantics (\t in args isn't reliably translated to tab by ProcessBuilder).
            ProcResult r = exec("kubectl", "get", "deployments", "-n", ns, "-o", "json");
            if (r.exitCode != 0) return out;
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = json.readTree(r.output);
            var items = root.path("items");
            if (!items.isArray()) return out;
            for (var d : items) {
                String name = d.path("metadata").path("name").asText(null);
                if (name == null) continue;
                Integer reps = d.path("spec").has("replicas") ? d.path("spec").get("replicas").asInt() : null;
                String image = d.path("spec").path("template").path("spec")
                    .path("containers").path(0).path("image").asText(null);
                out.put(name, new DeployFacts(reps, image));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static long parseCpuMilli(String s) {
        try {
            if (s.endsWith("m")) return Long.parseLong(s.substring(0, s.length() - 1));
            return Math.round(Double.parseDouble(s) * 1000);
        } catch (NumberFormatException e) { return 0; }
    }

    private static long parseMemMi(String s) {
        try {
            if (s.endsWith("Mi")) return Long.parseLong(s.substring(0, s.length() - 2));
            if (s.endsWith("Gi")) return Long.parseLong(s.substring(0, s.length() - 2)) * 1024;
            if (s.endsWith("Ki")) return Long.parseLong(s.substring(0, s.length() - 2)) / 1024;
            return Long.parseLong(s) / 1024 / 1024;
        } catch (NumberFormatException e) { return 0; }
    }

    private record ProcResult(int exitCode, String output) {}

    private static ProcResult exec(String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return new ProcResult(p.waitFor(), sb.toString());
        } catch (Exception e) {
            return new ProcResult(-1, e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private Panel resourcesPanel(Asset a) {
        Path ws = workspace.workspaceFor(a.id());
        if (!Files.isDirectory(ws.resolve(".git"))) {
            return Panel.list("resources", "Discovered resources",
                List.of("Workspace not cloned yet."));
        }
        try {
            DiscoveredResources d = detector.scan(ws);
            List<String> lines = new ArrayList<>();
            lines.add("language guess: " + (d.detectedLanguage() == null ? "—" : d.detectedLanguage()));
            lines.add("dockerfiles:    " + (d.dockerfiles().isEmpty() ? "(none)" : String.join(", ", d.dockerfiles())));
            lines.add("compose files:  " + (d.dockerComposeFiles().isEmpty() ? "(none)" : String.join(", ", d.dockerComposeFiles())));
            lines.add("k8s paths:      " + (d.kubernetesPaths().isEmpty() ? "(none)" : String.join(", ", d.kubernetesPaths())));
            lines.add("helm charts:    " + (d.helmCharts().isEmpty() ? "(none)" : String.join(", ", d.helmCharts())));
            lines.add("kustomizations: " + (d.kustomizations().isEmpty() ? "(none)" : String.join(", ", d.kustomizations())));
            return Panel.list("resources", "Discovered resources", lines);
        } catch (java.io.IOException e) {
            return Panel.list("resources", "Discovered resources", List.of("scan failed: " + e.getMessage()));
        }
    }

    private Panel githubPanel(Asset a) {
        return Panel.kv("github", "GitHub", List.of(
            new Panel.KvItem("Repo", a.repoUrl()),
            new Panel.KvItem("Default branch", a.repoDefaultBranch()),
            new Panel.KvItem("Owner", a.owner() == null ? "—" : a.owner()),
            new Panel.KvItem("Lifecycle", a.lifecycle())
        ));
    }

    private Panel workspacePanel(Asset a) {
        Path ws = workspace.workspaceFor(a.id());
        boolean cloned = Files.isDirectory(ws.resolve(".git"));
        StringBuilder code = new StringBuilder();
        code.append("workspace: ").append(ws).append('\n');
        code.append("cloned:    ").append(cloned).append('\n');
        if (cloned) {
            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(ws.resolve(".git").toFile()).build()) {
                code.append("HEAD:      ").append(
                    repo.resolve("HEAD") == null ? "(unset)" : repo.resolve("HEAD").name()).append('\n');
                code.append("branch:    ").append(repo.getBranch()).append('\n');
            } catch (IOException e) {
                code.append("error:     ").append(e.getMessage()).append('\n');
            }
        } else {
            code.append("hint:      run a build to clone\n");
        }
        return Panel.code("workspace", "Workspace", code.toString());
    }

    private Panel manifestPanel(Asset a) {
        Path manifestFile = workspace.workspaceFor(a.id()).resolve("devportal.yaml");
        if (!Files.exists(manifestFile)) {
            return Panel.list("manifest", "Manifest",
                List.of("⚠ No devportal.yaml in workspace.",
                        "Run the devportal-onboard skill to scaffold one."));
        }
        try {
            String yaml = Files.readString(manifestFile);
            ManifestParseResult parsed = manifestParser.parse(yaml);
            if (!parsed.valid() || parsed.manifest() == null) {
                List<String> errs = new ArrayList<>();
                errs.add("⚠ Manifest is invalid:");
                parsed.errors().forEach(e -> errs.add("  • " + e.path() + ": " + e.message()));
                return Panel.list("manifest", "Manifest", errs);
            }
            Manifest m = parsed.manifest();
            List<Panel.KvItem> items = new ArrayList<>();
            items.add(new Panel.KvItem("apiVersion", m.apiVersion()));
            items.add(new Panel.KvItem("kind", m.kind()));
            if (m.metadata() != null) {
                items.add(new Panel.KvItem("metadata.id", str(m.metadata().id())));
                items.add(new Panel.KvItem("metadata.name", str(m.metadata().name())));
                if (m.metadata().tags() != null && !m.metadata().tags().isEmpty()) {
                    items.add(new Panel.KvItem("metadata.tags", String.join(", ", m.metadata().tags())));
                }
            }
            if (m.spec() != null) {
                items.add(new Panel.KvItem("spec.type", str(m.spec().type())));
                items.add(new Panel.KvItem("spec.language", str(m.spec().language())));
                if (m.spec().build() != null && m.spec().build().tool() != null) {
                    items.add(new Panel.KvItem("spec.build.tool", m.spec().build().tool()));
                }
                if (m.spec().runtime() != null && m.spec().runtime().ports() != null) {
                    items.add(new Panel.KvItem("spec.runtime.ports",
                        m.spec().runtime().ports().size() + " slot(s)"));
                }
                if (m.spec().dependencies() != null) {
                    items.add(new Panel.KvItem("spec.dependencies",
                        m.spec().dependencies().size() + " dep(s)"));
                }
            }
            return Panel.kv("manifest", "Manifest", items);
        } catch (IOException e) {
            return Panel.list("manifest", "Manifest", List.of("error: " + e.getMessage()));
        }
    }

    private Panel auditPanel(Asset a) {
        AuditReport report = audit.audit(a.id());
        List<String> lines = new ArrayList<>();
        lines.add(String.format("errors: %d  warnings: %d  info: %d",
            report.errors(), report.warnings(), report.info()));
        if (report.findings().isEmpty()) {
            lines.add("✅ no findings");
        } else {
            for (AuditFinding f : report.findings()) {
                String prefix = switch (f.severity()) {
                    case "error" -> "🔴";
                    case "warn"  -> "🟠";
                    default      -> "ℹ️";
                };
                lines.add(prefix + " [" + f.area() + "] " + f.message());
                if (f.fixHint() != null && !f.fixHint().isBlank()) {
                    lines.add("    → " + f.fixHint());
                }
            }
        }
        return Panel.list("audit", "Audit", lines);
    }

    private static String str(String s) { return s == null ? "—" : s; }
}
