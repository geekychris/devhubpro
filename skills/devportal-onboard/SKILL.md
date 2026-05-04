---
name: devportal-onboard
description: Onboard a GitHub repo into dev_portal — detect language/build/docker/k8s, draft devportal.yaml, scaffold the doc skeleton, register the asset, and report. Use when the user says "onboard X", "register X to devportal", or "add X to the catalog".
---

# devportal-onboard

You are onboarding a GitHub repo into dev_portal.

## Inputs the user gives you

- **Required**: a GitHub repo (full name like `geekychris/hitorro-util`, or a URL).
- **Optional**: an asset id (slug) — otherwise derive from the repo name.

## What to do

1. **Read the repo locally** if a clone exists at `~/.devportal/workspace/<id>`. If not, ask the user to run `mcp__devportal__kick_build` with a no-op `commandLine: "true"` to populate the workspace, or clone it yourself with git for inspection.

2. **Detect the shape**:
   - Build tool — pick the first match: `pom.xml` → maven; `build.gradle*` → gradle; `package.json` → pnpm/npm/yarn (read packageManager field); `Makefile` → make; `go.mod` → shell+go; otherwise shell.
   - Language — Java / TypeScript / Go / Python / Shell based on the dominant source files.
   - Docker — `Dockerfile` at root → `spec.docker.enabled: true`.
   - Kubernetes — `k8s/`, `deploy/`, or `manifests/` dir → `spec.kubernetes.enabled: true, manifestPath: <dir>`.
   - Ports — heuristic: look for `EXPOSE` in Dockerfile, `containerPort` in k8s manifests, `server.port` in Spring config, `PORT` env reads in Node code. Convert each to a named slot.

3. **Draft `devportal.yaml`** at the repo root, conforming to `schema/devportal-asset.schema.json`. Use the sample at `schema/sample-manifest.yaml` as a template.

4. **Scaffold the doc skeleton** — copy from `schema/doc-skeleton/*.tmpl` into `README.md`, `docs/ARCHITECTURE.md`, `docs/BUILD.md`, `docs/RUN.md`, and (if docker/k8s enabled) `docs/DEPLOY.md`. Replace `{{name}}`, `{{description}}` placeholders. Don't overwrite files that already have non-template content — leave a comment instead.

5. **Register the asset** via the MCP tool `mcp__devportal__register_from_github` (it will pick up the manifest you just wrote when the user pushes/PRs it). If the user wants to register before pushing, use `mcp__devportal__list_assets` first to check it's not already there.

6. **Report back** — id, type, dependency edges to wire next, and any drift findings via `mcp__devportal__audit_asset`. Suggest concrete follow-ups (e.g., "add `hitorro-util` as a dependency", "claim port slots `http`, `metrics`").

## What to avoid

- Don't register the asset if you don't have enough info — confirm the id and type with the user first.
- Don't invent build commands. If you can't determine one, leave `spec.build.commands` empty and tell the user.
- Don't push to main; create a branch like `devportal-onboarding` and let the user review.
- Don't fabricate ports. Only declare slots you can justify from code or manifests.
