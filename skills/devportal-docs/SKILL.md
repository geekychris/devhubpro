---
name: devportal-docs
description: Scaffold or normalize the dev_portal doc skeleton in a managed asset's repo (README, docs/ARCHITECTURE.md, docs/BUILD.md, docs/RUN.md, docs/DEPLOY.md). Use when the user says "doc this", "normalize docs for X", "scaffold docs in X", or after onboarding a new asset.
---

# devportal-docs

Scaffold the dev_portal doc skeleton into an asset's repo.

## Templates

The canonical templates live at `<dev_portal_repo>/schema/doc-skeleton/*.tmpl`:

| Template            | Output path              | When to write                                       |
|---------------------|--------------------------|-----------------------------------------------------|
| `README.md.tmpl`    | `README.md`              | always                                              |
| `ARCHITECTURE.md.tmpl` | `docs/ARCHITECTURE.md` | always                                              |
| `BUILD.md.tmpl`     | `docs/BUILD.md`          | always                                              |
| `RUN.md.tmpl`       | `docs/RUN.md`            | always                                              |
| `DEPLOY.md.tmpl`    | `docs/DEPLOY.md`         | only if `spec.docker.enabled` or `spec.kubernetes.enabled` |

Placeholders use Mustache-style `{{name}}`, `{{description}}`, `{{build.commands.*}}`. Substitute from the asset's `devportal.yaml`. If a value is missing, leave a `<!-- TODO: ... -->` comment instead of fabricating content.

## Behavior

1. **Find the asset's `devportal.yaml`** (workspace at `~/.devportal/workspace/<id>/devportal.yaml`).
2. **For each output path that doesn't exist**, render the template and write it.
3. **For each output path that already exists**:
   - If it's already the rendered template (i.e., you find the placeholder comments still present), regenerate.
   - Otherwise, do **not** overwrite. Tell the user the file exists with custom content; offer to diff and merge if asked.
4. **Run the audit** afterwards (`mcp__devportal__audit_asset`) and confirm the `docs.missing.*` findings are gone.
5. **Suggest a commit** — propose a branch + commit message (don't push without confirmation).

## Quality bar

- Headings should match the template exactly (downstream tooling may look for them).
- Don't dump the entire manifest into ARCHITECTURE.md — extract the high-level shape only.
- Quote real build commands from the manifest in BUILD.md, not inferred ones.

## Output

Report which files were created vs. preserved, plus the proposed commit message. Don't paste full file contents in chat unless the user asks.
