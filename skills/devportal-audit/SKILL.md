---
name: devportal-audit
description: Audit one or more dev_portal assets for drift against portal conventions, summarize findings with actionable fix suggestions. Use when the user says "audit X", "check drift on X", "what's wrong with X", or asks for a portal-wide health report.
---

# devportal-audit

Run drift detection on dev_portal assets and report.

## How to use

- For one asset: call `mcp__devportal__audit_asset` with the asset id.
- For all assets: call `mcp__devportal__list_assets` first, then audit each in turn.

## What to report

Group findings by **severity** (errors first, then warnings, then info), and within each group cluster by **area** (`manifest`, `docs`, `docker`, `k8s`, `ports`, `workspace`).

For each finding, show:
- The finding's `code` (machine-readable, useful when the user wants to suppress).
- The `message` (what's wrong).
- The `fixHint` (one-line how-to).
- A concrete proposal — propose the actual edit, not just "fix it":
  - `manifest.missing` → invoke `devportal-onboard` to draft one, OR show a minimal devportal.yaml inline.
  - `manifest.invalid` → quote the schema error, point at the offending lines.
  - `docs.missing.*` → invoke `devportal-docs` to scaffold from the templates.
  - `ports.no-slots` → ask the user which named slots to declare (http? metrics?).
  - `docker.missing-dockerfile` → either disable `docker.enabled` or write a Dockerfile.
  - `k8s.missing-manifests` → either disable `kubernetes.enabled` or scaffold k8s/.

## Output shape

When the user audits one asset, return a tight bulleted report:

```
{asset-id} — {N errors / M warnings / K info}
🔴 [manifest] devportal.yaml missing
   → Run /devportal-onboard {asset-id}
🟠 [docs] missing docs/ARCHITECTURE.md
   → Run /devportal-docs {asset-id}
ℹ️ [ports] no slot declarations
   → I can add `http` + `metrics` slots if you confirm
```

When auditing many, lead with a one-line table summarizing counts per asset, then dive into the top offenders. Don't dump the whole report unless asked.

## What not to do

- Do not silently auto-fix. Always propose and wait for user confirmation, except when the user explicitly says "fix everything".
- Do not skip findings to keep the report short — surface counts even if you summarize details.
