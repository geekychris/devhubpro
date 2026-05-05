---
name: devportal-k8s-convert
description: Convert one DevHub Pro asset to standardized Kubernetes manifests, commit on a branch, verify it still boots, and report. Use when the user says "convert X to k8s", "standardize k8s for X", "make X deployable", "fix the k8s for X", or asks to clean up a project's deploy story.
---

# devportal-k8s-convert

Take one asset and bring its Kubernetes manifests to portal conventions, commit the change for review, and verify the asset still works after the change. This is meant to be run once per managed repo as part of the cleanup pass.

## Inputs

- **Required**: an asset id (slug). Example: `hitorro-jvs-example-springboot`.
- **Optional**: a target branch name (default: `devportal/k8s-<timestamp>`).

## Workflow

### 1. Inspect

- `mcp__devportal__audit_asset` — see drift findings for the target.
- `mcp__devportal__get_asset` — confirm repo URL, default branch.
- Read the workspace at `~/.devportal/workspace/<id>/` directly to see what's there: pom.xml, application.yml, Dockerfile, k8s/, deploy/. The portal also exposes this via `GET /api/assets/{id}/discover`.
- `mcp__devportal__allocate_ports` with `scope: "k8s-nodeport"` so the registry has values to substitute into the manifests.

### 2. Decide which path

- **Existing k8s/ dir** → render path. The portal patches Service NodePorts in place. You'll commit the patched files.
- **No k8s/ dir** → scaffold path. The portal generates `k8s/deployment.yaml` + `k8s/service.yaml` from inferred port slots and the conventional image name `devportal/<assetId>:latest`. You'll commit the new files.

### 3. Apply

- **Render**: `POST /api/assets/{id}/k8s/commit-render?branch=<X>&message=<...>&push=false`. Default `push=false` — the user reviews the local commit first.
- **Scaffold**: `POST /api/assets/{id}/k8s/scaffold` to write the files into the workspace, then `POST /api/assets/{id}/k8s/commit-workspace?branch=...&push=false`.

Both endpoints return:
- `branch` — the local branch you committed on
- `commit` — SHA
- `filesChanged` — list of paths
- `prSuggestion` — URL to open a PR (don't push without confirmation)

### 4. Verify boot-up

- `POST /api/assets/{id}/verify?stage=docker` — does a build → run → http probe. Expects an `http` slot allocation (allocate via `mcp__devportal__allocate_ports` if missing).
- The verify endpoint returns a step-by-step result. If the probe fails, examine the build log (`mcp__devportal__get_build_log`) and run output before claiming success.

### 5. Report back

Tell me, in this exact shape:

```
{asset-id}
  scaffold|render: {N files}
  branch: {name}
  commit: {sha7}
  verify: pass | fail at {stage} ({short reason})
  PR: {prSuggestion}
```

If verify failed, **do not push the branch**. Surface the failure, propose what to fix (Dockerfile, manifest, port slot), and either:
1. Loop back to step 1 with a fix, or
2. Hand back to me with the diagnosis.

If verify passed, ask whether to push the branch — only `push=true` after explicit yes.

## Constraints

- **Never push to `main`.** Always work on a branch named `devportal/...`.
- **Never auto-merge a PR.** Stop after pushing the branch; let the human open and review.
- **One asset per invocation.** Bulk loops happen by invoking this skill repeatedly, not by running 20 conversions in one go (so each commit is reviewable on its own).
- **Validate the asset has port slots before scaffolding.** Run `mcp__devportal__allocate_ports` first; if the inferrer returns `http` only, that's enough to scaffold a single-port Service.
- **Preserve existing labels.** When committing render output, only Service `.spec.ports[].nodePort` should change — flag any other diff before committing.
- **Skip read-only / archived repos.** `mcp__devportal__get_asset` shows `lifecycle: deprecated` or the GitHub `archived: true` flag — don't attempt to commit to those.

## Failure recovery

- **Push fails (auth)**: tell the user to set their token via `/settings`, or push manually via `git push origin <branch>`.
- **Verify fails at `build`**: read `mcp__devportal__get_build_log`, propose Dockerfile or manifest changes.
- **Verify fails at `probe`**: the container starts but doesn't serve HTTP on the http slot. Check that the container actually exposes the port and that the app's actual listen port matches `containerPort` in the generated Service. Often the fix is in `containerPort` (the asset listens on 9000 but Service expects 8080).
- **Manifests rendered but no NodePort allocated yet**: call `mcp__devportal__allocate_ports {scope: "k8s-nodeport"}` and re-render.

## Why this skill exists

There are dozens of assets to convert by hand. Doing each one manually is tedious. With this skill the human reviews diffs and verify outputs, but Claude drives the mechanical parts: render/scaffold, commit, verify, report. After 20-30 of these the corpus is consistent.
