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

    public PanelService(AssetRepository assets, WorkspaceService workspace,
                        ManifestParser manifestParser, AuditService audit,
                        ResourceDetector detector) {
        this.assets = assets;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        this.audit = audit;
        this.detector = detector;
    }

    public List<Panel> panelsFor(String assetId) {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));
        List<Panel> out = new ArrayList<>();
        out.add(githubPanel(asset));
        out.add(workspacePanel(asset));
        out.add(manifestPanel(asset));
        out.add(resourcesPanel(asset));
        out.add(auditPanel(asset));
        return out;
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
