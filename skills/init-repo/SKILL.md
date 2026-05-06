---
name: init-repo
description: Bootstrap a fresh project directory into a git repo. Detect languages, write a comprehensive .gitignore, ensure a README.md with Design / Architecture / Build / Run sections, run git init / add / commit, and optionally chain into the containerize-service skill for Dockerfile + k8s artifacts. Use when the user says "init this repo", "set up git for this project", "make this a repo named X", "initialise X", or starts working in a fresh directory and asks for the first commit.
---

# init-repo

You are turning a working directory into a clean first commit on `main`. Your job is to detect the project shape, prevent build artifacts from being checked in, ensure the README is informative, and make the first commit reproducible.

## Inputs the user gives you

- **Required**: a repository name (e.g. `auth-service`, `widget-core`, `aoee-spring`).
- **Optional**: a one-line description (otherwise infer from the directory or ask).
- **Optional**: confirmation about whether this is a service that needs Docker + k8s artifacts (otherwise ask after the first commit).

## Workflow

### 1. Sanity checks

- `pwd` to confirm the working directory.
- `ls -la` to see what's already there.
- If `.git/` already exists: stop. Do not re-init. Surface that the dir is already a repo and ask whether the user wants to add a `.gitignore` / README without re-init.
- If the directory is empty: ask the user whether they intended this — there's nothing to commit. Offer to scaffold a minimal `README.md` and commit that.

### 2. Detect languages and build tooling

Walk the tree (depth ~3 is plenty for detection) and classify by the presence of these markers. Prioritize whatever produces the most signal — most projects are mono-language but be ready for hybrids.

| Marker file                                   | Language / stack         | Default ignore patterns                                                                                  |
|-----------------------------------------------|--------------------------|----------------------------------------------------------------------------------------------------------|
| `pom.xml`                                     | Java + Maven             | `target/`, `*.class`, `*.jar`, `*.war`, `*.ear`, `.mvn/wrapper/maven-wrapper.jar`, `dependency-reduced-pom.xml` |
| `build.gradle`, `build.gradle.kts`, `settings.gradle*` | Java/Kotlin + Gradle     | `build/`, `.gradle/`, `out/`, `*.class`, `bin/`                                                          |
| `package.json`                                | Node / TypeScript        | `node_modules/`, `dist/`, `build/`, `.next/`, `.nuxt/`, `.svelte-kit/`, `.turbo/`, `coverage/`, `*.tsbuildinfo`, `pnpm-debug.log*`, `npm-debug.log*`, `yarn-error.log*` |
| `Cargo.toml`                                  | Rust                     | `target/`, `*.rs.bk`, `*.pdb`                                                                            |
| `pyproject.toml`, `setup.py`, `requirements.txt` | Python                  | `__pycache__/`, `*.py[cod]`, `*.egg-info/`, `*.egg`, `.venv/`, `venv/`, `.pytest_cache/`, `.mypy_cache/`, `.ruff_cache/`, `.tox/`, `dist/`, `build/` |
| `go.mod`                                      | Go                       | `bin/`, `*.exe`, `*.test`, `*.prof`, `*.out`                                                             |
| `Gemfile`                                     | Ruby                     | `.bundle/`, `vendor/bundle/`, `*.gem`                                                                    |
| `composer.json`                               | PHP                      | `vendor/`, `composer.phar`                                                                               |
| `mix.exs`                                     | Elixir                   | `_build/`, `deps/`, `*.beam`, `erl_crash.dump`                                                           |
| `Dockerfile`                                  | Container build present  | (see step 4 — defer to containerize-service for what to add/check)                                       |
| `docker-compose.yml`, `compose.yaml`          | Docker compose           | (no extra ignore beyond what languages add)                                                              |
| `k8s/`, `manifests/`, `deploy/`, `helm/`      | Kubernetes / Helm        | (no extra ignore; Helm chart `charts/` directory should be tracked unless user says otherwise)           |
| `.next/`, `.nuxt/`, `.svelte-kit/`            | Specific JS framework    | already covered by package.json bucket, but worth detecting for the README "how to run" hint            |
| `vite.config.*`, `next.config.*`, `astro.config.*` | Frontend framework | shapes the build/run instructions in the README                                                          |

Cross-cutting (always ignore regardless of language):
- `.DS_Store`, `Thumbs.db`, `desktop.ini`
- `*.swp`, `*.swo`, `*~` (editor artifacts)
- `.idea/`, `*.iml`, `.iws`, `.ipr` (IntelliJ — debatable; default to ignore unless user keeps shared `.idea/runConfigurations/`)
- `.vscode/` (debatable; default to ignore but call out and let user opt-in to keeping `.vscode/settings.json` shared)
- `*.log`, `logs/`
- `tmp/`, `temp/`
- `.env`, `.env.local`, `.env.*.local` — *never* commit
- `*.pem`, `*.key`, `id_rsa*` — secrets sniff guard

If the user has IDE-specific configs they want tracked (e.g. `.idea/runConfigurations/`, `.vscode/launch.json`), ask before excluding the whole directory.

### 3. Compose the .gitignore

- If `.gitignore` already exists, **read it first**, append only the missing sections, and de-duplicate. Don't clobber.
- Group entries by section with comments — humans grep this file:
  ```
  # macOS / Windows
  .DS_Store
  Thumbs.db

  # Editors
  .idea/
  .vscode/
  *.swp

  # Java (Maven)
  target/
  *.class

  # Java (Gradle)
  build/
  .gradle/

  # Secrets — never commit
  .env
  .env.*.local
  *.pem
  ```
- After writing, run `git status --ignored` to confirm the artifact directories you intended to ignore are actually ignored.

### 4. Ensure the README is useful

If `README.md` is missing, empty, or obviously a template (single line, generic placeholders), draft one with this structure. If `README.md` exists with non-trivial content, **don't overwrite** — add missing sections instead at the bottom under a `## TODO` heading.

```markdown
# {{repoName}}

{{one-line description from the user — required}}

## Design

{{What is this? What problem does it solve? Who calls it? Mention the rough architecture: monolith vs library vs daemon, sync vs async, in-process vs networked, etc. 2-5 sentences.}}

## Architecture

{{Component diagram or description. If there's a UI + backend, describe each tier and how they talk. If it depends on infra (postgres, redis, kafka), call them out. Mermaid diagrams welcome — render them as ```mermaid blocks.}}

## Build

{{The exact commands to build the artifact. Must be copy-pasteable. Examples:
- Maven:        `./mvnw clean package` or `mvn clean package`
- Gradle:       `./gradlew build`
- Cargo:        `cargo build --release`
- Node:         `pnpm install && pnpm build` (or npm/yarn — match what's there)
- Go:           `go build ./...`
- Python:       `pip install -e .` or `uv sync`}}

## Run

{{The exact commands to start the app and the ports it exposes. Examples:
- Spring Boot:  `./mvnw spring-boot:run` (port 8080)
- Vite dev:     `pnpm dev` (port 5173)
- Cargo binary: `./target/release/{{binname}}`
- Python:       `python -m {{module}}` or `uvicorn app:app`
Mention any required environment variables and where they're documented (often a separate `.env.example`).}}
```

Detection rules:
- Build/run command inference comes from the marker files. Use the wrapper if present (`./mvnw` over `mvn`, `./gradlew` over `gradle`).
- Determine the dev server port from common conventions and existing config — Spring's `application.yml/server.port`, Next/Vite defaults, etc.
- If you can't determine a section's content with confidence, write `<!-- TODO: describe X -->` rather than fabricate.

### 5. First commit

```sh
git init -b main
git add .
git status                                  # show the user before committing
git commit -m "Initial commit"
```

Show the user the staged file list and the size summary (`git ls-files | wc -l`, total tracked size) before the commit. If something looks wrong (build artifacts staged, secrets staged), stop and surface it — don't push past a `.env` file landing in a public repo.

### 6. Containerization handoff

After the first commit, ask the user:

> This looks like a {{detected-shape}}. Do you want me to add Dockerfile + k8s manifests (and verify they actually build + run) by invoking the **containerize-service** skill? [yes / no / "what would it do"]

- **yes**: invoke the `containerize-service` skill. When it returns successfully, make a follow-up commit ("Add Docker and Kubernetes artifacts") with the new files and report both commits to the user.
- **no**: skip. Note in your final report that containerization was deliberately deferred (so the next time the user asks "did I containerize X?" you have a record).
- **what would it do**: summarize the containerize-service skill's behaviour (Dockerfile, k8s/deployment.yaml, k8s/service.yaml, optional Ingress, build + run + probe), then re-prompt yes/no.

Detection helper for the question:
- **Service candidate** — has a port, has an entry-point that long-runs (`@SpringBootApplication`, `app.listen(...)`, `actix-web`/`axum`/`tokio::main`, `uvicorn`/`gunicorn`).
- **Library candidate** — pom packaging is `jar` and there's no `main` method, Cargo `[lib]` only, npm `"main"` exports library entry. Probably skip containerization.
- **CLI tool** — Cargo `[[bin]]` with no port, Go single-binary, Python entry-point script. Probably skip unless they want a runnable container image.
- **UI candidate** — Vite/Next/Astro/React/Vue config + dev server. Containerize as static-served via nginx (the containerize skill knows the pattern).

Don't make this decision unilaterally — surface what you detected and ask. The user knows whether this is a library, CLI, or service.

### 7. Final report

End with a short report:

```
{{repoName}}
  branch:        main
  commit:        {{sha7}} (Initial commit)
  files:         {{N}} tracked, {{M}} ignored
  languages:     {{detected list}}
  README:        wrote / updated / left untouched
  containerize:  yes (commit {{sha7}}) | declined | deferred
```

## What to avoid

- **Don't `git add` before `.gitignore` is written.** Otherwise build artifacts and secrets land in the index and need to be `git rm --cached`'d.
- **Don't push.** This skill stops at the first local commit. Pushing requires the user to set up the remote and decide on visibility.
- **Don't fabricate content in the README.** Use `<!-- TODO -->` markers when you can't infer with confidence — better a visible gap than a confidently wrong description.
- **Don't run `rm -rf` on detected artifact dirs.** Just ignore them via `.gitignore`. The user's previous build outputs aren't yours to delete.
- **Don't auto-set a remote.** `git remote add origin …` is the user's call once they create the repo on GitHub / GitLab / etc.
- **Don't commit `.env`, `*.pem`, `id_rsa*`, or anything else that looks like a secret.** If you spot one in the working tree, stop and ask before adding anything.

## Failure recovery

- **`git init` fails** because the directory is inside a git submodule or an existing repo's working tree → surface the parent repo's path and stop. Don't nest a new repo inside another.
- **A `.gitignore` change leaves files staged** that should be ignored → run `git rm --cached -r <path>` for each, re-stage, and re-commit. Tell the user.
- **The README has substantive existing content but missing sections** → append a `## TODO` section listing the missing ones rather than overwriting; let the user decide if they want help filling them in.
- **The containerize-service skill returns failure** at build/probe time → leave the Dockerfile/k8s files in place uncommitted, hand the failure back to the user with the diagnosis from the containerize skill, and let them decide whether to commit, revert, or fix.
