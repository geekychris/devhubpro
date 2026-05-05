package io.devportal.audit;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.audit.dto.AuditFinding;
import io.devportal.audit.dto.AuditReport;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
import io.devportal.runtime.k8s.scaffold.FrontendScaffolder;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Drift-detection over a registered asset against the dev_portal conventions. */
@Service
public class AuditService {

    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final ManifestParser manifestParser;
    private final FrontendScaffolder frontendScaffolder;

    public AuditService(AssetRepository assets, WorkspaceService workspace,
                        ManifestParser manifestParser, FrontendScaffolder frontendScaffolder) {
        this.assets = assets;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        this.frontendScaffolder = frontendScaffolder;
    }

    public AuditReport audit(String assetId) {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));

        List<AuditFinding> findings = new ArrayList<>();
        Path ws = workspace.workspaceFor(assetId);

        if (!Files.isDirectory(ws.resolve(".git"))) {
            findings.add(new AuditFinding(
                "workspace.empty", "info", "workspace",
                "No local checkout for this asset.",
                "Run any build to populate the workspace at " + ws));
        } else {
            auditManifest(asset, ws, findings);
            auditDocs(ws, findings);
            auditFrontendTiers(ws, findings);
        }

        int errors = (int) findings.stream().filter(f -> "error".equals(f.severity())).count();
        int warns = (int) findings.stream().filter(f -> "warn".equals(f.severity())).count();
        int info = (int) findings.stream().filter(f -> "info".equals(f.severity())).count();
        return new AuditReport(assetId, errors, warns, info, findings, Instant.now());
    }

    private void auditManifest(Asset asset, Path ws, List<AuditFinding> findings) {
        Path manifestFile = ws.resolve("devportal.yaml");
        if (!Files.exists(manifestFile)) {
            findings.add(new AuditFinding(
                "manifest.missing", "error", "manifest",
                "No devportal.yaml at the repo root.",
                "Run the devportal-onboard skill or create one matching schema/devportal-asset.schema.json"));
            return;
        }
        try {
            String yaml = Files.readString(manifestFile);
            ManifestParseResult parsed = manifestParser.parse(yaml);
            if (!parsed.valid()) {
                findings.add(new AuditFinding(
                    "manifest.invalid", "error", "manifest",
                    "devportal.yaml fails schema validation: " + parsed.errors().size() + " issue(s).",
                    "Fix per JSON Schema; first issue: "
                        + (parsed.errors().isEmpty() ? "(none)" : parsed.errors().get(0).message())));
            }
            if (parsed.manifest() != null && parsed.manifest().metadata() != null
                && !asset.id().equals(parsed.manifest().metadata().id())) {
                findings.add(new AuditFinding(
                    "manifest.id-mismatch", "warn", "manifest",
                    "Manifest id '" + parsed.manifest().metadata().id()
                        + "' does not match registered asset id '" + asset.id() + "'.",
                    "Reconcile by editing devportal.yaml metadata.id or re-registering with the manifest's id"));
            }
            if (parsed.manifest() != null && parsed.manifest().spec() != null) {
                Manifest.Spec s = parsed.manifest().spec();
                if (s.runtime() == null || s.runtime().ports() == null || s.runtime().ports().isEmpty()) {
                    findings.add(new AuditFinding(
                        "ports.no-slots", "info", "ports",
                        "Manifest declares no port slots.",
                        "Add spec.runtime.ports[] (named slots like 'http', 'metrics') so the port registry can allocate"));
                }
                if (s.docker() != null && Boolean.TRUE.equals(s.docker().enabled())) {
                    String df = s.docker().dockerfile() == null ? "Dockerfile" : s.docker().dockerfile();
                    if (!Files.exists(ws.resolve(df))) {
                        findings.add(new AuditFinding(
                            "docker.missing-dockerfile", "error", "docker",
                            "docker.enabled=true but " + df + " not found in repo.",
                            "Create the Dockerfile or set docker.enabled=false"));
                    }
                }
                if (s.kubernetes() != null && Boolean.TRUE.equals(s.kubernetes().enabled())) {
                    String mp = s.kubernetes().manifestPath() == null ? "k8s/" : s.kubernetes().manifestPath();
                    if (!Files.exists(ws.resolve(mp))) {
                        findings.add(new AuditFinding(
                            "k8s.missing-manifests", "error", "k8s",
                            "kubernetes.enabled=true but " + mp + " not found.",
                            "Create the k8s manifest dir or set kubernetes.enabled=false"));
                    }
                }
            }
        } catch (IOException e) {
            findings.add(new AuditFinding(
                "manifest.read-failed", "error", "manifest",
                "Could not read devportal.yaml: " + e.getMessage(),
                "Check file permissions"));
        }
    }

    /**
     * Surface React/Vite/Next/Vue/Angular tiers — and flag any that don't have a Dockerfile yet,
     * so the user can one-click scaffold them.
     */
    private void auditFrontendTiers(Path ws, List<AuditFinding> findings) {
        for (FrontendScaffolder.Tier t : frontendScaffolder.detectTiers(ws)) {
            if (!t.hasDockerfile()) {
                findings.add(new AuditFinding(
                    "frontend.no-dockerfile",
                    "warn", "frontend",
                    t.framework() + " tier at " + t.relPath() + " has no Dockerfile.",
                    "POST /api/assets/<id>/scaffold-frontend?path=" + t.relPath()
                        + " writes a multi-stage Dockerfile + nginx.conf + k8s manifest."));
            } else {
                findings.add(new AuditFinding(
                    "frontend.detected",
                    "info", "frontend",
                    t.framework() + " tier at " + t.relPath()
                        + " (" + t.packageManager() + ", out=" + t.outputDir() + ") — Dockerfile present.",
                    "Already scaffolded."));
            }
        }
    }

    private void auditDocs(Path ws, List<AuditFinding> findings) {
        String[] required = {"README.md", "docs/ARCHITECTURE.md", "docs/BUILD.md", "docs/RUN.md"};
        for (String rel : required) {
            if (!Files.exists(ws.resolve(rel))) {
                findings.add(new AuditFinding(
                    "docs.missing." + rel.toLowerCase(),
                    "warn", "docs",
                    "Missing doc skeleton file: " + rel,
                    "Run the devportal-docs skill, or copy from schema/doc-skeleton/"));
            }
        }
    }
}
