# Lifecycle hooks (test fixtures + setup hooks)

A general mechanism for declaring named, structured commands that an asset exposes — for
test-data generation, post-deploy seeding, credential dumps, smoke probes. They show up in
the **Fixtures** tab in the UI and can be auto-fired after a successful `kubectl apply`.

> The same primitive serves two related needs: **inspect** (e.g. "what are the test admin
> credentials?") and **mutate** (e.g. "seed the database with 200 users"). Treat the
> structured output as a small contract; everything else is just shell.

## Why

Most projects have a stash of seed scripts, credentials docs, and post-deploy commands
scattered across `scripts/`, READMEs, and tribal knowledge. Bringing up a fresh deploy
becomes "apply manifests, hope datagen runs, run seed-tenants.sh, copy credentials from
TEST_ACCOUNTS.md, log in". Each step is brittle on its own.

dev_portal's lifecycle hooks formalize this:

- **Declare** what scripts the asset exposes in `devportal.yaml`.
- **Standardize** their structured output via a single line in stdout.
- **Run** them via the UI (or auto-fire after apply).
- **Surface** credentials, links, and summaries as a clickable table — not buried in logs.

## The contract

A fixture is a named shell command. To produce structured output, the command emits one
line of the form:

```
DEVPORTAL_FIXTURE: {"summary": "...", "credentials": [...], "links": [...]}
```

The portal parses the **last** such line in the command's stdout. Anything else is just
log output (still captured, available in the Fixtures-tab log viewer and as a regular
build row under `command_name=test-fixture`).

### JSON shape

```json
{
  "summary": "Seeded 200 users + 2000 posts in tenant 1.",
  "credentials": [
    {
      "label": "Tenant 1 admin",
      "username": "lamar.lehner",
      "password": "password",
      "role": "Admin",
      "url": "http://localhost:30080/login"
    }
  ],
  "links": [
    {"label": "WorkSphere UI",   "url": "http://localhost:30080/"},
    {"label": "Admin dashboard", "url": "http://localhost:30080/admin"}
  ]
}
```

All three top-level fields are optional; emit only what you have. If you emit no
`DEVPORTAL_FIXTURE:` line at all, the run is still recorded (with exit code + log tail) —
just without the structured affordances.

## Manifest

Add to your asset's `devportal.yaml`:

```yaml
spec:
  test:
    fixtures:
      - name: <kebab-case-id>          # required, URL-safe
        description: One-line summary  # shown above the Run button
        command: <shell command>       # runs in workspace cwd
        runIn: host                    # default; reserved: docker:<image>, pod:<deployment>
        runOnApply: false              # auto-fire after `kubectl apply` when caller passes runHooks=true
        waitForPodsSeconds: 0          # wait for pods labeled app=<asset-id> to be Ready before running
```

## The two roles

### Inspect-only fixture (`runOnApply: false`)

User-triggered. Surfaces existing state. Examples:
- "What are the admin credentials?"
- "List active tenants and their billing tier"
- "Print the current OAuth client_id"

These shouldn't have side effects; they're fast and cheap to run.

### Setup hook (`runOnApply: true`)

Auto-fires after the apply endpoint when the caller opts in. Examples:
- "Seed the database with demo tenants"
- "Wait for postgres to accept connections, then run migrations"
- "Populate AOEE's graph with starter data"

Order: declaration order in the manifest. Failure stops the chain.

Triggering:

```bash
# Single-asset apply with hooks
curl -X POST 'http://localhost:8081/api/assets/<id>/k8s/apply?runHooks=true'

# Composite apply (asset + runtime closure) with hooks
curl -X POST 'http://localhost:8081/api/assets/<id>/k8s/apply?include=runtime&runHooks=true'
```

Response shape with `runHooks=true`:

```json
{
  "apply":  { /* normal apply result */ },
  "hookResults": [
    { "name": "seed-tenants", "status": "succeeded", "summary": "...", "credentials": [...], ... }
  ]
}
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/api/assets/{id}/test-fixtures` | List fixtures from manifest |
| `POST` | `/api/assets/{id}/test-fixtures/{name}/run` | Run one (sync), return parsed result |
| `GET`  | `/api/assets/{id}/test-fixtures/{name}/last-run` | Last result (204 if never run) |
| `POST` | `/api/assets/{id}/k8s/apply?runHooks=true` | Apply + auto-fire `runOnApply: true` fixtures |

Each run is recorded as a regular `build` row with `command_name=test-fixture`, so it's
queryable via the existing `/api/builds/*` endpoints and visible in the Builds tab.

## UI

The **Fixtures** tab on each asset shows one card per declared fixture:

- **Run** button (or "Run again" once a result is cached).
- Description + collapsed command text.
- Result card after running: status pill, exit code + duration + timestamp, summary,
  credentials table with click-to-copy passwords, link buttons, expandable log tail.
- Surfaces `parseError` if the command emitted an invalid `DEVPORTAL_FIXTURE:` line.

## Worked example: enterprise_social_platform

ESP exposes two fixtures wrapping its existing seed scripts. The wrapper script
[`scripts/devportal-fixture-emit.sh`](https://github.com/geekychris/enterprise_social_platform/blob/devportal/setup/scripts/devportal-fixture-emit.sh)
reads `seed-tenants-output.json` and emits the structured `DEVPORTAL_FIXTURE:` line.
It also probes the live `/api/auth/login` endpoint with the documented credentials, so
the fixture's summary tells the user whether the credentials *actually exist in the running
DB* — surfacing the "datagen Job failed silently → DB is empty → nothing logs in" case
as a clear warning rather than a stale-credential surprise.

```yaml
# enterprise-social-platform/devportal.yaml (excerpt)
spec:
  test:
    fixtures:
      # Inspect-only: prints credentials, validates them via live login probe.
      - name: show-accounts
        description: List the test admin / tenant credentials from the seeded dataset
        command: bash scripts/devportal-fixture-emit.sh http://localhost:30080
        runIn: host
      # Setup hook: re-runs seed-tenants.sh against a running social-app, then emits
      # the credentials. runOnApply=true so a `?runHooks=true` apply auto-seeds.
      - name: seed-tenants
        description: Provision 3 demo tenants with admin users
        command: |
          bash scripts/seed-tenants.sh http://localhost:30002 \
            && bash scripts/devportal-fixture-emit.sh http://localhost:30080
        runIn: host
        runOnApply: true
        waitForPodsSeconds: 60
```

After clicking **Apply with dependents** + checking *Run setup hooks*, the response carries
the `hookResults` array, the Fixtures tab caches the last result, and the credentials table
shows up immediately:

| Label | Username | Password | Role | URL |
|---|---|---|---|---|
| Lamar Lehner | `lamar.lehner` | `password` | Tenant 1 Admin | open |
| Joshua Padberg | `joshua.padberg` | `password` | Tenant 1 Admin | open |
| Cecilia Watsica | `cecilia.watsica` | `password` | Tenant 1 Admin | open |
| Nexus Tenant Admin | `sarah.chen` | `nexus123` | Tenant 2 Admin | open |
| Meridian Tenant Admin | `dr.emily.ross` | `meridian123` | Tenant 3 Admin | open |

## Patterns to copy

### 1. Wrap an existing script

If the project already has `scripts/seed-data.sh` that prints credentials in some
loose format, write a wrapper that runs it and converts the output:

```bash
#!/usr/bin/env bash
# scripts/devportal-fixture-emit.sh
set -euo pipefail

bash scripts/seed-data.sh "$@" > /tmp/seed.out 2>&1

python3 - <<'PY'
import json, re, sys
text = open('/tmp/seed.out').read()
creds = []
for m in re.finditer(r'CREATED user "(\w+)" with password "(\S+)"', text):
    creds.append({"label": m.group(1), "username": m.group(1), "password": m.group(2)})
print("DEVPORTAL_FIXTURE: " + json.dumps({
    "summary": f"Seeded {len(creds)} users",
    "credentials": creds,
    "links": [{"label": "App", "url": "http://localhost:8080"}],
}))
PY
```

Then declare it:

```yaml
spec:
  test:
    fixtures:
      - name: seed
        command: bash scripts/devportal-fixture-emit.sh
        runOnApply: true
        waitForPodsSeconds: 60
```

### 2. Multiple fixtures sharing one wrapper

When the same wrapper script handles several scenarios (seed-small, seed-large,
seed-multi-tenant), pass the variant as an arg:

```yaml
spec:
  test:
    fixtures:
      - name: seed-small
        command: bash scripts/devportal-fixture-emit.sh small
      - name: seed-large
        command: bash scripts/devportal-fixture-emit.sh large
```

### 3. Read existing JSON output

If the project's seed script already emits a JSON manifest (like ESP's
`seed-tenants-output.json`), re-export it through the fixture contract:

```bash
python3 -c "
import json
data = json.load(open('seed-tenants-output.json'))
print('DEVPORTAL_FIXTURE: ' + json.dumps({
    'summary': f'{len(data[\"users\"])} users created',
    'credentials': [
        {'label': name, 'username': name, 'password': 'password'}
        for name in list(data['users'])[:3]
    ],
}))
"
```

### 4. Live probe to surface staleness

A fixture's `summary` can include the result of probing live state, so users see whether
the credentials/output reflect reality. ESP's wrapper does this with a `curl` to
`/api/auth/login` — when it returns 401/404 the summary leads with a clear warning that
the DB hasn't been populated, rather than handing out credentials that won't work.

### 5. Pre-flight wait for pods

Use `waitForPodsSeconds` so the hook can assume the cluster is live:

```yaml
- name: seed
  command: bash scripts/devportal-fixture-emit.sh
  runOnApply: true
  waitForPodsSeconds: 90
```

The portal will run `kubectl wait --for=condition=ready pod -l app=<asset-id>` for that
duration before invoking the command. If pods don't become Ready within the budget the
hook fires anyway (best effort) — your script should idempotently retry.

## What's not (yet) supported

- `runIn: docker:<image>` and `runIn: pod:<deployment>` are reserved but not implemented.
  Today everything runs `host` — i.e. on the dev_portal host shell, in the workspace dir.
- Pre-apply hooks (run *before* `kubectl apply`). If you need this, sequence with
  `kubectl apply -f` in the hook's command itself.
- Scheduled / recurring fixtures. Today they fire on demand or on apply.
- Per-fixture `dependsOn` for parallel execution. Today: serial in declaration order,
  stop on first failure.

## Anti-patterns

- **Don't put secrets in the structured payload.** Test/dev creds are fine; production
  secrets shouldn't flow through the build log file (which is world-readable on the host).
- **Don't make a setup hook idempotent at runtime by deleting+recreating data** unless
  the asset's data is genuinely cheap to recreate. Surprise data loss on apply is
  worse than a hook that no-ops when state already matches.
- **Don't use `runOnApply: true` for slow operations** without `waitForPodsSeconds` —
  the hook can fire before the app is reachable.
- **Don't emit credentials with hard-coded URLs** — accept the UI URL as `$1` so the
  command works against any deploy target.
