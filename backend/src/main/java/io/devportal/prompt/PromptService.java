package io.devportal.prompt;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.audit.AuditService;
import io.devportal.audit.dto.AuditFinding;
import io.devportal.audit.dto.AuditReport;
import io.devportal.discover.ResourceDetector;
import io.devportal.discover.dto.DiscoveredResources;
import io.devportal.prompt.dto.HelpPrompt;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Builds copy-pasteable prompts that the user hands to Claude Code (via MCP) to drive the portal
 * through fixing common issues. The prompt is self-contained: it states the goal, dumps the
 * relevant context (audit findings, workspace state, repo URL), names the MCP tools available,
 * and suggests one or two paths forward.
 */
@Service
public class PromptService {

    private final AssetRepository assets;
    private final AuditService audit;
    private final WorkspaceService workspace;
    private final ResourceDetector detector;

    public PromptService(AssetRepository assets, AuditService audit,
                         WorkspaceService workspace, ResourceDetector detector) {
        this.assets = assets;
        this.audit = audit;
        this.workspace = workspace;
        this.detector = detector;
    }

    public HelpPrompt build(String assetId, String problem, String details) {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));
        String key = problem == null ? "general" : problem.toLowerCase(Locale.ROOT);

        StringBuilder out = new StringBuilder();
        out.append("# DevHub Pro — help with `").append(asset.id()).append("`\n\n");

        switch (key) {
            case "manifest.missing", "ports.allocate-failed", "deploy.no-manifest" -> {
                out.append("## Problem\n");
                out.append("Port / runtime / deploy operation failed because the asset has no ");
                out.append("`devportal.yaml` at the root of its workspace. ");
                out.append("The portal needs that manifest to know which named port slots to ");
                out.append("allocate, which Dockerfile / k8s path to use, and which build commands ");
                out.append("to run.\n\n");
            }
            case "workspace.empty" -> {
                out.append("## Problem\n");
                out.append("The workspace at `~/.devportal/workspace/").append(asset.id()).append("/` ");
                out.append("hasn't been cloned yet. The portal needs the repo on disk to analyze ");
                out.append("its build files, allocate ports, or run builds.\n\n");
            }
            case "build.failed" -> {
                out.append("## Problem\n");
                out.append("A build of `").append(asset.id()).append("` failed. ");
                out.append("Inspect the build log, identify the root cause, and either fix the build ");
                out.append("commands in `devportal.yaml` (preferred) or fix the underlying repo.\n\n");
            }
            case "manifest.invalid" -> {
                out.append("## Problem\n");
                out.append("The `devportal.yaml` is present but fails JSON Schema validation. ");
                out.append("Reconcile it against `schema/devportal-asset.schema.json` in this monorepo.\n\n");
            }
            default -> {
                out.append("## Problem\n");
                out.append("General help requested for this asset.\n\n");
            }
        }

        if (details != null && !details.isBlank()) {
            out.append("**Details from the portal:** ").append(details).append("\n\n");
        }

        out.append("## Asset context\n");
        out.append("- **id**: `").append(asset.id()).append("`\n");
        out.append("- **name**: ").append(nullSafe(asset.name())).append("\n");
        out.append("- **type**: ").append(asset.type()).append("\n");
        out.append("- **language**: ").append(nullSafe(asset.language())).append("\n");
        out.append("- **repo**: ").append(asset.repoUrl()).append("\n");
        out.append("- **default branch**: ").append(asset.repoDefaultBranch()).append("\n");
        out.append("- **workspace**: `~/.devportal/workspace/").append(asset.id()).append("/`\n\n");

        Path ws = workspace.workspaceFor(asset.id());
        boolean cloned = Files.isDirectory(ws.resolve(".git"));
        out.append("## Workspace state\n");
        out.append("- cloned: ").append(cloned ? "yes" : "no").append("\n");
        if (cloned) {
            out.append("- has `devportal.yaml`: ").append(Files.exists(ws.resolve("devportal.yaml")) ? "yes" : "**no**").append("\n");
            out.append("- has `pom.xml`: ").append(Files.exists(ws.resolve("pom.xml")) ? "yes" : "no").append("\n");
            out.append("- has `build.gradle*`: ").append(
                Files.exists(ws.resolve("build.gradle")) || Files.exists(ws.resolve("build.gradle.kts")) ? "yes" : "no").append("\n");
            out.append("- has `package.json`: ").append(Files.exists(ws.resolve("package.json")) ? "yes" : "no").append("\n");
            try {
                DiscoveredResources d = detector.scan(ws);
                if (!d.dockerfiles().isEmpty()) {
                    out.append("- Dockerfiles: ").append(String.join(", ", d.dockerfiles())).append("\n");
                }
                if (!d.kubernetesPaths().isEmpty()) {
                    out.append("- k8s paths: ").append(String.join(", ", d.kubernetesPaths())).append("\n");
                }
                if (!d.helmCharts().isEmpty()) {
                    out.append("- helm charts: ").append(String.join(", ", d.helmCharts())).append("\n");
                }
            } catch (IOException ignored) {}
        }
        out.append("\n");

        try {
            AuditReport report = audit.audit(asset.id());
            if (!report.findings().isEmpty()) {
                out.append("## Drift findings (audit)\n");
                for (AuditFinding f : report.findings()) {
                    String prefix = switch (f.severity()) {
                        case "error" -> "🔴";
                        case "warn"  -> "🟠";
                        default      -> "ℹ️";
                    };
                    out.append("- ").append(prefix).append(" **[").append(f.area()).append("]** ");
                    out.append(f.message()).append("\n");
                    if (f.fixHint() != null && !f.fixHint().isBlank()) {
                        out.append("  - hint: ").append(f.fixHint()).append("\n");
                    }
                }
                out.append("\n");
            }
        } catch (Exception ignored) {}

        out.append("## What I'd like you to do\n");
        switch (key) {
            case "manifest.missing", "ports.allocate-failed", "deploy.no-manifest" -> {
                out.append("1. Run the **`/devportal-onboard`** skill (or `mcp__devportal__audit_asset` first ");
                out.append("to confirm the drift). Detect the build tool, language, Spring Boot ports, ");
                out.append("Dockerfile presence, k8s manifests in `k8s/` or `deploy/`. Draft a ");
                out.append("`devportal.yaml` matching `schema/devportal-asset.schema.json` from this ");
                out.append("monorepo.\n");
                out.append("2. **Specifically declare the port slots** in `spec.runtime.ports`. ");
                out.append("For Spring Boot, look for `server.port` / `management.server.port` in ");
                out.append("`application.yml` / `application.properties`, and named slots like `http`, ");
                out.append("`management`, `metrics`, `debug`.\n");
                out.append("3. Commit on a branch named `devportal-onboarding` and propose a PR — ");
                out.append("don't push to main.\n");
                out.append("4. After the manifest is written, ask me to re-run **Allocate ports** in ");
                out.append("the UI (`mcp__devportal__allocate_ports {assetId, scope: \"local\"}` or ");
                out.append("`scope: \"k8s-nodeport\"`).\n");
            }
            case "workspace.empty" -> {
                out.append("1. Run `mcp__devportal__kick_build` with `commandLine: \"true\"` to ");
                out.append("populate the workspace with a fresh clone (no actual build).\n");
                out.append("2. Then `mcp__devportal__audit_asset` to see what's missing.\n");
                out.append("3. If a `devportal.yaml` is missing, invoke `/devportal-onboard`.\n");
            }
            case "build.failed" -> {
                out.append("1. Use `mcp__devportal__list_builds` and `mcp__devportal__get_build_log` ");
                out.append("to read the failing log.\n");
                out.append("2. Diagnose: missing dep, version mismatch, environment, etc.\n");
                out.append("3. Propose a fix (manifest tweak, env var, version pin, etc.) and ");
                out.append("re-run with `mcp__devportal__kick_build`.\n");
            }
            case "manifest.invalid" -> {
                out.append("1. Read `schema/devportal-asset.schema.json` from this monorepo.\n");
                out.append("2. Fetch the offending `devportal.yaml` from the repo.\n");
                out.append("3. Show me a diff that fixes the schema errors.\n");
            }
            default -> {
                out.append("1. Use `mcp__devportal__audit_asset` to see drift.\n");
                out.append("2. Use `mcp__devportal__get_asset` and `mcp__devportal__get_graph` ");
                out.append("for context.\n");
                out.append("3. Propose a concrete next action and confirm with me before doing it.\n");
            }
        }
        out.append("\n");

        out.append("## MCP tools available (under `mcp__devportal__*`)\n");
        for (Map.Entry<String, String> tool : MCP_TOOLS.entrySet()) {
            out.append("- `").append(tool.getKey()).append("` — ").append(tool.getValue()).append("\n");
        }
        out.append("\n");

        out.append("## Skills you can invoke\n");
        out.append("- `/devportal-onboard` — detect language/build/docker/k8s, draft `devportal.yaml`, ");
        out.append("scaffold doc skeleton, register.\n");
        out.append("- `/devportal-audit` — surface drift, propose concrete fixes.\n");
        out.append("- `/devportal-docs` — render the doc-skeleton templates into the repo.\n\n");

        out.append("## Constraints\n");
        out.append("- The DevHub Pro REST API lives at `http://localhost:8081`.\n");
        out.append("- Don't push to `main`; always use a branch and confirm with me before opening a PR.\n");
        out.append("- The JSON Schema for the manifest is at `schema/devportal-asset.schema.json` in ");
        out.append("the dev_portal monorepo (you'll find it locally if you `cd` to that repo).\n");

        return new HelpPrompt(asset.id(), key, out.toString());
    }

    private static String nullSafe(String s) { return s == null ? "—" : s; }

    private static final Map<String, String> MCP_TOOLS = Map.ofEntries(
        Map.entry("list_assets", "filter by query / type / lifecycle"),
        Map.entry("get_asset", "fetch one asset"),
        Map.entry("register_from_github", "onboard a repo (uses devportal.yaml if present)"),
        Map.entry("add_dependency", "add an edge between assets"),
        Map.entry("get_graph", "reachable dep graph (direction + depth supported)"),
        Map.entry("kick_build", "shallow or deep build"),
        Map.entry("list_builds", "recent builds"),
        Map.entry("get_build_log", "captured stdout/stderr"),
        Map.entry("allocate_ports", "allocate registry ports per manifest slots"),
        Map.entry("list_meta_assets", "shared-infra inventory"),
        Map.entry("attach_consumes", "wire asset → meta-asset (role)"),
        Map.entry("audit_asset", "drift report against portal conventions"),
        Map.entry("state_git_sync", "YAML export + commit to state repo")
    );
}
