# CLI / SSH access

DevPortal exposes an embedded SSH server inside the backend JVM. Connecting to it lands you in an interactive `devportal>` shell with full parity with the REST API — every controller endpoint has a corresponding command, plus a few macro / OS-side operations.

This document is the deep reference. The README has a short summary; this is the one to bookmark.

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Architecture](#architecture)
- [Connecting](#connecting)
  - [Password auth](#password-auth)
  - [Public-key auth](#public-key-auth)
  - [Mixing both](#mixing-both)
  - [LAN exposure](#lan-exposure)
- [Configuration reference](#configuration-reference)
- [Command catalog](#command-catalog)
  - [Asset](#asset)
  - [Build](#build)
  - [Port](#port)
  - [K8s + cluster](#k8s--cluster)
  - [Docker](#docker)
  - [Port-forward](#port-forward)
  - [Endpoints](#endpoints)
  - [Fixtures](#fixtures)
  - [Dashboard](#dashboard)
  - [Search + tags](#search--tags)
  - [Analyze + audit + verify](#analyze--audit--verify)
  - [Bulk import](#bulk-import)
  - [Meta-assets + consumes](#meta-assets--consumes)
  - [State + workspace](#state--workspace)
  - [Settings](#settings)
  - [Scaffold + commit](#scaffold--commit)
  - [Macro](#macro)
  - [Misc — discover, docs, git-info, panel, prompt, graph, health](#misc--discover-docs-git-info-panel-prompt-graph-health)
- [Output formats](#output-formats)
- [Colors](#colors)
- [Tab completion and history](#tab-completion-and-history)
- [Composing with shell tools](#composing-with-shell-tools)
- [Concurrency model](#concurrency-model)
- [Extending the CLI](#extending-the-cli)
- [Worked example: a new "metrics-snapshot" macro](#worked-example-a-new-metrics-snapshot-macro)
- [OS-side operations](#os-side-operations)
- [Security notes](#security-notes)
- [Troubleshooting](#troubleshooting)

---

## Why this exists

The web UI handles the day-to-day, the REST API handles automation, the MCP server handles Claude Code. The CLI fills a gap none of those quite cover:

- **No browser** — when you're SSH'd into a remote box managing the portal, popping a browser is awkward. `ssh devportal` and you're done.
- **Composable** — pipe `--json` output through `jq`, drive sequences with shell scripts, kick a deep build and `tail -f` its log all from the same prompt.
- **Macros** — composite operations that span multiple services (allocate ports → apply k8s → run hooks → print endpoints) deserve a single command. Macros live next to the service code that does the work.
- **OS-side ops** — sometimes you need to grep a workspace, run a one-off `kubectl get all -A`, or shell into a pod with full TTY. `macro sh` does the first two; pod exec lives in the websocket layer for the third.
- **Single binary** — the SSH server is part of the backend; nothing extra to install or version-pin.

---

## Architecture

```
┌──────────────────┐ ssh ┌────────────────────────────────┐
│ Your local       │────▶│ Mina SSHD listener              │
│ ssh client       │     │ (127.0.0.1:2222 by default)     │
└──────────────────┘     │                                  │
                         │  per-channel:                    │
                         │  ┌────────────────────────────┐ │
                         │  │ JLine ExternalTerminal      │ │
                         │  │  ↓                          │ │
                         │  │ LineReader (history,        │ │
                         │  │   tab-completion)           │ │
                         │  │  ↓                          │ │
                         │  │ picocli CommandLine         │ │
                         │  │  ↓                          │ │
                         │  │ @Command method dispatch    │ │
                         │  │  ↓                          │ │
                         │  │ AssetService, BuildService… │ │
                         │  │  (constructor-injected)     │ │
                         │  └────────────────────────────┘ │
                         │                                  │
                         │  System.out/err redirected via   │
                         │  SessionStream (ThreadLocal) so  │
                         │  command println goes back to    │
                         │  the right SSH session.          │
                         └────────────────────────────────┘
```

Key choices:

- **Apache Mina SSHD** for the SSH transport. Pure-Java, no native deps. Generates a host key on first run at `~/.devportal/secrets/ssh-host-key`.
- **JLine 3 `ExternalTerminal`** (not `TerminalBuilder`'s default) — `system(false).streams(...)` in JLine 3.27 picks `PosixPtyTerminal`, which allocates a real OS PTY and spawns a pump thread that races with SSH channel close. `ExternalTerminal` is a pure stream wrapper, the right primitive for SSH.
- **picocli** for command parsing — annotation-driven (`@Command`, `@Option`, `@Parameters`), tab-completion via `picocli-shell-jline3`, ANSI color via `Help.Ansi.ON` (forced because `System.console()` returns null over SSH).
- **Direct service injection** — commands are Spring `@Component`s; they call `AssetService.list(...)` instead of issuing in-process HTTP. Cheaper, simpler, and doesn't need port 8081.
- **`SessionStream`** — a `PrintStream` subclass that delegates to a per-thread target if set. Installed once as `System.out` so the existing `System.out.println(...)` calls in command code automatically write to the right SSH session without each command threading a writer through manually.

---

## Connecting

The SSH server starts when the backend boots. Watch the log for:

```
INFO  io.devportal.cli.ssh.SshLifecycle : devportal CLI: ssh://127.0.0.1:2222 (publickey=true, password=true)
```

Username is ignored (single-user local mode). `chris`, `devportal`, `whoever` — any string works.

### Password auth

On first start, if no `~/.devportal/secrets/ssh-password` exists, the portal generates one:

```
WARN  io.devportal.cli.ssh.SshLifecycle : devportal CLI: generated SSH password at /Users/you/.devportal/secrets/ssh-password
```

The file is written mode 0600. To get the password:

```sh
cat ~/.devportal/secrets/ssh-password
```

To set your own, replace the file's contents. The SSH server reads it on every auth attempt — no restart needed.

```sh
echo "hunter2" > ~/.devportal/secrets/ssh-password
chmod 600 ~/.devportal/secrets/ssh-password
ssh -o PreferredAuthentications=password chris@127.0.0.1 -p 2222
```

To disable password auth entirely:

```yaml
devportal:
  cli:
    ssh:
      allow-password: false
```

### Public-key auth

Drop one or more public keys into `~/.devportal/secrets/authorized_keys` (same format as OpenSSH's authorized_keys — one key per line, optional comment, supports RSA / ECDSA / Ed25519):

```sh
cat ~/.ssh/id_ed25519.pub >> ~/.devportal/secrets/authorized_keys
ssh -i ~/.ssh/id_ed25519 chris@127.0.0.1 -p 2222
```

The portal re-reads the file on every auth attempt — adding or removing keys takes effect immediately, no restart.

To disable public-key auth entirely:

```yaml
devportal:
  cli:
    ssh:
      allow-publickey: false
```

### Mixing both

Default config has both enabled. The SSH client picks the auth method (most modern clients try public-key first, fall back to password). To force one over the other:

```sh
ssh -o PreferredAuthentications=publickey chris@127.0.0.1 -p 2222   # key only
ssh -o PreferredAuthentications=password  chris@127.0.0.1 -p 2222   # password only
```

### LAN exposure

The default bind is `127.0.0.1` — loopback only. To expose on the LAN:

```yaml
devportal:
  cli:
    ssh:
      host: 0.0.0.0
      port: 2222
```

When you do this, **make sure password auth is either off or the password is non-trivial**, since anyone on the LAN can now reach the SSH port.

For zero-trust setups, prefer `allow-password: false` plus an `authorized_keys` file containing only your own keys.

---

## Configuration reference

Every key under `devportal.cli.*` in `application.yml`:

| Key                                         | Default                                              | Purpose                                                                                                                                              |
|---------------------------------------------|------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `enabled`                                   | `true`                                               | Set to `false` to skip binding the SSH port (useful in tests).                                                                                       |
| `ssh.host`                                  | `127.0.0.1`                                          | Bind address. `0.0.0.0` exposes on the LAN.                                                                                                          |
| `ssh.port`                                  | `2222`                                               | TCP port. Pick anything ≥1024; ports <1024 require root.                                                                                              |
| `ssh.host-key`                              | `${user.home}/.devportal/secrets/ssh-host-key`       | Where the persistent host key lives. Auto-generated on first start.                                                                                   |
| `ssh.authorized-keys`                       | `${user.home}/.devportal/secrets/authorized_keys`    | Public keys allowed to log in. Same format as `~/.ssh/authorized_keys`.                                                                              |
| `ssh.password-file`                         | `${user.home}/.devportal/secrets/ssh-password`       | Plain-text file containing the password. One line. Mode 0600.                                                                                        |
| `ssh.allow-publickey`                       | `true`                                               | Toggle public-key auth.                                                                                                                              |
| `ssh.allow-password`                        | `true`                                               | Toggle password auth.                                                                                                                                |
| `ssh.generate-password-if-missing`          | `true`                                               | If no `password-file` exists at startup and password auth is enabled, write a random one (mode 0600) so the portal is never accidentally locked out. |

Override any of these with environment variables in standard Spring Boot fashion: `DEVPORTAL_CLI_SSH_PORT=2200 ./gradlew bootRun`.

---

## Command catalog

Every controller domain has a top-level command group. Commands are listed below in their typical order of use; see `help <command>` inside the shell for full per-command help with all options.

### Asset

```
asset list [-q TERM] [--type T] [--lifecycle L] [--favorites] [--json]
asset get ID [--json]
asset create --id ID --name NAME --repo-url URL [--type T] [--owner O] [--language L] [--branch B] [--tag T...]
asset register OWNER/REPO [--id OVERRIDE]              # picks up devportal.yaml at HEAD
asset update ID [--name N] [--type T] [--lifecycle L] [--favorite true|false] [--rating 0-5] [--pin true|false] [--tag T...]
asset delete ID
asset deps ID [--json]                                 # what this asset depends on
asset consumers ID [--json]                            # who depends on this asset
asset add-dep CONSUMER PRODUCER [--kind build|runtime] [--version V]
asset rm-dep CONSUMER PRODUCER [--kind ...]
```

`asset list` defaults to a coloured table — green/yellow/dim lifecycles, ★ ratings, ♥ favorites, 📌 pinned. Add `--json` for raw output suitable for `jq`.

```sh
# All Java services pinned to dashboard:
asset list --type service --json | jq '.[] | select(.dashboardPinned == true and .language == "java") | .id'
```

### Build

```
build kick ID [--mode shallow|deep] [--command NAME] [--cmd-line OVERRIDE] [--ref BRANCH]
build list ID [--limit 50] [--json]
build get BUILD_ID [--json]
build log BUILD_ID                                     # raw stdout/stderr
build chain BUILD_ID [--json]                          # parent + sibling chain
build progress BUILD_ID                                # aggregate progress YAML
build recent [--limit 50] [--json]
build delete BUILD_ID
```

Status field is colour-coded: `succeeded` green, `failed` red, `running` yellow, `queued` dim.

```sh
# Watch a deep build:
build kick my-svc --mode deep
# (note the build id, e.g. 240)
build progress 240
# tail the root log:
build log 240
```

### Port

```
port list [--json]                                     # all reservations across scopes
port for ID [--json]                                   # one asset's reservations
port allocate ID [--scope local|k8s-nodeport] [--reallocate]
port release ID [--scope ...]
```

Slot names are read from `devportal.yaml` `spec.runtime.ports` if present, otherwise inferred from Spring `server.port`, Dockerfile `EXPOSE`, k8s `containerPort`.

### K8s + cluster

`k8s` covers manifest apply/delete/render/status/diagnostics; `pod` covers the Kubernetes runtime view.

```
k8s apply ID [--include runtime] [--skip CSV] [--run-hooks]
k8s plan ID                                            # composite runtime plan
k8s render ID                                          # write port-patched manifests, no apply
k8s delete ID [--include runtime] [--skip CSV]
k8s status ID
k8s links ID                                           # Grafana / monitoring deep-links
k8s diagnostics ID

pod list ID [--json]
pod logs ID POD [--container C] [--tail 200]
pod describe ID POD
pod events ID [--json]
```

`--include runtime` walks the runtime-producer closure (this asset and every asset it depends on at runtime). `--skip a,b,c` omits specific ids from the closure when you want to apply most-but-not-all. `--run-hooks` fires every `runOnApply: true` fixture in declaration order after the apply succeeds.

### Docker

```
docker build ID                                        # build image for asset
docker build-images ID [--include runtime]             # build image + producers
docker run ID
docker ps ID [--json]                                  # containers belonging to asset
docker stop ID NAME
```

### Port-forward

```
forward list [--json]
forward for ID [--json]
forward start ID POD CONTAINER_PORT [--host-port N]
forward stop SESSION_ID
```

Sessions die on portal restart — port-forwards are dev-time only. Host port is auto-allocated from the local pool unless you pass `--host-port`.

### Endpoints

```
endpoint list ID [--json]
```

Discovers every host-reachable URL (local docker, NodePort, port-forward) plus in-cluster URLs with `ExposeHint`s for promoting them to host-accessible.

### Fixtures

```
fixture list ID [--json]
fixture run ID NAME                                    # synchronous; prints parsed result
fixture last-run ID NAME                               # cached last result, 'never run' otherwise
```

`fixture run` returns the structured result parsed from the fixture's `DEVPORTAL_FIXTURE: {json}` line — credentials, links, summary — plus exit code and log tail.

### Dashboard

```
dashboard running [--json]
```

The same view as the web dashboard: pinned + live assets with their live URLs, Swagger detection, and credential-fixture quick-links. ● green = live, ○ dim = down.

### Search + tags

```
search run "QUERY" [--no-docs] [--json]
tag list [-q PREFIX] [--json]
```

Search hits cover asset metadata + every workspace's `.md` files. `--no-docs` skips the doc full-text scan.

### Analyze + audit + verify

```
analyze validate ID
analyze run ID                                         # re-parse pom / build files
analyze auto-wire ID                                   # reconcile dep edges from pom
analyze artifacts ID [--json]

audit run ID [--json]                                  # drift report against conventions
verify run ID [--stage docker|k8s]                     # boot-check the asset at one stage
```

### Bulk import

```
import preview OWNER [--json]                          # list org repos, no registration
import start OWNER [--lang L...] [--include RE...] [--exclude RE...] [--skip-archived] [--skip-forks]
import list [--json]
import get JOB_ID
```

### Meta-assets + consumes

```
meta list [--json]
meta get ID
meta create '{"id":"redis","kind":"cache",...}'        # JSON request body inline
meta update ID '{...}'
meta delete ID
meta consumes ID                                       # consumes edges for an asset
meta attach ASSET META [--role R]
meta detach ASSET META [--role R]
```

### State + workspace

```
state export                                           # dump DB to YAML
state import                                           # wipe + re-load assets from YAML tree
state git-sync [--message "snapshot"]                  # export + commit to state git repo

workspace status ID
workspace diff ID PATH
workspace commit ID --branch B --message M [--path P...] [--push]
workspace push ID --branch B
```

### Settings

```
settings github-show
settings github-set TOKEN
settings github-clear
settings github-test                                   # ping GitHub API with current token
```

### Scaffold + commit

```
scaffold k8s ID                                        # default k8s manifests in workspace
scaffold frontend-tiers ID                             # detected React/Vite/Next/Vue tiers
scaffold frontend ID PATH                              # Dockerfile + nginx + k8s for one tier
scaffold runtime ID                                    # Dockerfile + k8s in one call
scaffold commit-render ID [--branch B] [--message M] [--push]
scaffold commit-workspace ID [--branch B] [--message M] [--push]
```

### Macro

Composite operations and OS-side tasks. Drop new macros into `MacroCommands.java` — see [Extending the CLI](#extending-the-cli) below.

```
macro spinup ID [--scope k8s-nodeport]                 # ports + apply + hooks + endpoints
macro teardown ID [--scope ...]                        # delete + release ports
macro audit-all [--json]                               # audit every asset, summary table
macro sh ID -- CMD ARG...                              # run shell command in asset's workspace
```

### Misc — discover, docs, git-info, panel, prompt, graph, health

```
discover run ID [--json]                               # scan workspace for build/docker/k8s
docs list ID
docs read ID PATH                                      # print one .md file
git-info show ID                                       # GitHub stars/forks/tags/last-push
panel list ID                                          # server-driven panels
prompt help ID [--problem TYPE] [--details TEXT]       # build an Ask-Claude prompt
graph show ID [--direction both|producers|consumers] [--producer-depth N] [--consumer-depth N]
health check                                           # liveness snapshot
help [COMMAND]                                         # picocli's built-in
```

---

## Output formats

Three flavours:

1. **Table** — default for `list`-style commands. Bold headers, dim separator, ANSI-coloured value cells where appropriate.
2. **YAML** — default for single-object views (`asset get`, `build get`, `audit run`). Easier to read than JSON when the field set is large.
3. **JSON** — `--json` flag on most commands. Pretty-printed, suitable for piping to `jq`.
4. **Raw text** — for inherently text-shaped output (`build log`, `pod logs`, `docs read`, `workspace diff`).

Examples:

```sh
asset list --type service              # table
asset get my-svc                       # YAML
asset get my-svc --json | jq .         # JSON
build log 240                          # raw text
```

---

## Colors

ANSI 16-colour palette via the `io.devportal.cli.output.Ansi` helper. Tracked elements:

| Element                            | Colour                                       |
|------------------------------------|----------------------------------------------|
| Banner / prompt                    | bold cyan                                    |
| Table header                       | bold                                         |
| Table separator                    | dim                                          |
| `succeeded` / `live` / `stable`    | green                                        |
| `failed` / `error` / `deprecated`  | red                                          |
| `running` / `experimental` / `warn`| yellow                                       |
| `cancelled` / `skipped` / `stopped`| gray                                         |
| Asset ★ rating                     | yellow                                       |
| Asset ♥ favorite                   | red                                          |
| Asset 📌 dashboard pin             | cyan                                         |
| Dashboard live indicator           | green ● / dim ○                              |
| URLs in macro output               | cyan                                         |
| Audit error / warning counts       | red / yellow; zero = dim                     |

`NO_COLOR=1` (https://no-color.org) disables colours globally — useful when shipping output to a logfile or piping somewhere that can't handle ANSI. picocli's help output is forced to `Ansi.ON` because `System.console()` is null over SSH (which would otherwise make picocli fall back to no-colour).

---

## Tab completion and history

Tab completion is powered by `picocli-shell-jline3`. It walks the picocli command tree:

- `<TAB>` at the prompt → list all top-level command groups.
- `asset <TAB>` → list asset subcommands.
- `asset list --<TAB>` → list `--type`, `--lifecycle`, `--favorites`, `--json`, `-q`.

Completion currently knows the static command tree but does **not** populate dynamic argument values (e.g. it won't list known asset ids when you tab after `asset get `). That's a follow-up — patches welcome.

History persists across sessions at `~/.devportal/cli-history`. ↑/↓ to scroll, Ctrl-R to reverse-search, Ctrl-A / Ctrl-E for line edits — full readline shortcuts.

---

## Composing with shell tools

The `--json` flag on most commands is the seam to standard Unix tooling. A few realistic recipes:

```sh
# Stop every dashboard-pinned asset that's currently live:
ssh devportal -p 2222 'dashboard running --json' \
  | jq -r '.[] | select(.live and .asset.dashboardPinned) | .asset.id' \
  | xargs -I{} ssh devportal -p 2222 'macro teardown {}'

# Audit-all sorted by error count, top 10:
ssh devportal -p 2222 'macro audit-all --json' \
  | jq -r 'sort_by(-.errors) | .[:10] | .[] | "\(.id)\t\(.errors)\t\(.findings)"'

# Tail every running build log — naive but effective:
ssh devportal -p 2222 'build recent --limit 20 --json' \
  | jq -r '.[] | select(.status=="running") | .id' \
  | while read id; do echo "=== build $id ==="; ssh devportal -p 2222 "build log $id" | tail -20; done
```

Note: each `ssh devportal -p 2222 'CMD'` opens a fresh shell session, runs CMD, and disconnects. Mina supports SSH "exec" channels — picocli's `cli.execute(argv)` runs the same way as inside an interactive shell, so command output behaves identically.

---

## Concurrency model

Every SSH connection gets its own session thread, its own JLine `LineReader`, its own `picocli.CommandLine` instance, and its own `SessionStream` binding. Two simultaneous sessions don't see each other's output even though `System.out` is process-global.

Command beans are **singletons** — Spring instantiates each `@Component @Command` class once. Picocli stores parsed argument values on those singleton instances during `execute()`. In single-user local mode (the design target) this is fine because you almost never have two CLI sessions running the same subcommand at the same instant. If you start running concurrent SSH sessions hitting the same command in parallel, switch the command beans to `@Scope("prototype")` — the picocli factory we register already calls `applicationContext.getBean(class)`, which respects scope, so the change is one annotation per command.

Builds, k8s apply, docker run, etc., are all asynchronous in the underlying services — kicking a build returns immediately with the build id, the heavy work runs on dedicated executors. The CLI inherits that model unchanged.

---

## Extending the CLI

The CLI is designed to extend without touching framework code. Three shapes of extension:

### 1. New subcommand on an existing group

Add a new `@Command`-annotated method to an existing class — e.g. a new `asset import-tags` command goes onto `AssetCommands`:

```java
@Command(name = "import-tags", description = "Bulk-import tags from a CSV.")
public Integer importTags(
    @Parameters(paramLabel = "CSV") String csvPath,
    @Option(names = "--dry-run") boolean dryRun
) throws Exception {
    // call AssetService and TagService here
    return 0;
}
```

That's all. Tab completion picks it up, `help asset` lists it, no other wiring.

### 2. New top-level group

Drop a new file under `backend/src/main/java/io/devportal/cli/commands/`:

```java
@Component
@Command(name = "things", description = "Manage things.")
public class ThingsCommands {
    private final ThingsService svc;
    public ThingsCommands(ThingsService svc) { this.svc = svc; }

    @Command(name = "list")
    public Integer list(@Option(names = "--json") boolean json) {
        var items = svc.list();
        System.out.println(json ? Out.json(items) : Out.tableOf(items, ...));
        return 0;
    }
}
```

Then append `ThingsCommands.class` to `RootCommand.subcommands`. Done.

### 3. New macro

Macros are composite operations or OS-shell wrappers. Either add a new method to `MacroCommands` or create a sibling class. Inject whatever services you need; for OS work, lean on the existing `MacroCommands.shell(command, cwd, timeoutSeconds)` helper.

---

## Worked example: a new "metrics-snapshot" macro

Suppose you want a `macro metrics-snapshot ID` that:

1. Pulls the asset's k8s pods,
2. Runs `kubectl top pod -n <namespace>` for the same pods,
3. Captures Spring Boot `/actuator/metrics` for any pod with a host-accessible URL,
4. Writes everything to a timestamped tarball under `~/.devportal/snapshots/<id>/<ts>/`.

Sketch:

```java
@Command(name = "metrics-snapshot",
         description = "Capture pods + kubectl top + actuator/metrics into a tarball.")
public Integer metricsSnapshot(@Parameters(paramLabel = "ID") String id) throws Exception {
    var asset = assets.findById(id).orElseThrow();
    var pods = cluster.listPods(id);                      // ClusterService — already injected
    var ep = endpoints.discover(id);
    Path out = Path.of(System.getProperty("user.home"), ".devportal", "snapshots", id,
        Instant.now().toString());
    Files.createDirectories(out);

    Files.writeString(out.resolve("pods.yaml"), Out.yaml(pods));

    String ns = asset.k8sNamespace();
    shell("kubectl top pod -n " + ns + " > " + out.resolve("top.txt"), out, 30);

    for (var e : ep.endpoints()) {
        if (e.hostAccessible() && e.url() != null) {
            shell("curl -s -m 5 " + e.url() + "/actuator/metrics > "
                  + out.resolve("metrics-" + e.label().replaceAll("\\W+", "_") + ".json"),
                  out, 10);
        }
    }
    shell("tar -czf " + out.getParent().resolve("snapshot-" + Instant.now().toEpochMilli() + ".tar.gz")
          + " -C " + out + " .", out, 30);

    System.out.println(Ansi.green("snapshot at: ") + out);
    return 0;
}
```

Inject `ClusterService` and `EndpointsService` (and reuse the existing `assets`, `endpoints`) via the constructor. Five extra `@Component` services, no framework changes.

---

## OS-side operations

`macro sh` is the canonical pattern for shelling out: it changes into the asset's workspace and runs your command, capturing stdout+stderr (merged) and the exit code.

```sh
macro sh my-svc -- ls -la src/main
macro sh my-svc -- mvn -DskipTests clean package
macro sh my-svc -- git log --oneline -20
```

The runner is `MacroCommands.shell(String command, Path cwd, int timeoutSeconds)` — uses `ProcessBuilder` with `redirectErrorStream(true)`, streams output line-by-line back to the SSH session, and kills the process if it overruns the timeout. Reusable from any new macro.

For OS operations that don't need a workspace cwd (e.g. checking host disk, running a portal-wide tool), call `shell(cmd, Path.of("."), 60)` directly. Or write a fresh `ProcessBuilder` if you need stdin or separated stderr.

For interactive shells with a real TTY — `kubectl exec`, opening a `psql` to an asset's database — use the existing WebSocket exec endpoint at `/ws/assets/{id}/pods/{pod}/exec` from the web UI. The CLI doesn't proxy WebSockets through SSH (would be a layer-busting mess); it instead exposes the same `kubectl logs / describe / events / pod list` flows non-interactively under the `pod` group.

---

## Security notes

Single-user local mode means *you* are the trust boundary:

- **Username is ignored.** Mina accepts any username and dispatches to the configured authenticators. There's no per-user authorization, no RBAC, no audit log of who did what. If you need that, this is the wrong tool — use the web UI behind your real auth.
- **The shell has the same privileges as the backend JVM.** Macros run as the user who started Spring Boot. `macro sh` can do anything that user can do.
- **Password file is plain text.** If your home directory is readable by other accounts on the box, the password is too. Either prefer public-key auth, or chmod 700 your home directory, or both.
- **Loopback default is intentional.** Most users want this. Switching to `0.0.0.0` opens the SSH port to the LAN; if you do that, disable password auth and use keys only.
- **Host key persists.** First start writes `~/.devportal/secrets/ssh-host-key`; SSH clients pin its fingerprint on first connection. Deleting the file (or moving it) regenerates a new host key, which will trigger MITM warnings on subsequent client connects — so don't delete it casually. To intentionally rotate, delete the file *and* clear the relevant entry from your `~/.ssh/known_hosts`.

---

## Troubleshooting

### Connection refused

The SSH server didn't bind. Check:

```sh
grep "ssh://" /tmp/devportal-bootrun.log    # or wherever Spring Boot logs go
lsof -i :2222                                # is something listening?
```

If you see `Failed to start SSH server on 127.0.0.1:2222`, another process is on that port — change `devportal.cli.ssh.port` or kill the conflicting process.

### Authentication failed

Mina logs auth attempts at INFO. Check the bootRun log for the username/method. Common causes:

- Password file is empty, missing trailing newline, or contains the wrong value.
- `authorized_keys` file uses a key format Mina doesn't support — currently RSA, ECDSA (P-256/384/521), Ed25519. (No legacy DSA, no ssh-rsa with SHA-1.)
- File mode too open — Mina is conservative; if `authorized_keys` is mode 0644 or worse, it may be ignored. Use 0600.

### Session connects but immediately disconnects

If you see ANSI escape garbage and the prompt never appears, JLine might be initializing a `PosixPtyTerminal` instead of `ExternalTerminal`. The portal pins this in `DevportalShellSession.java` by constructing `ExternalTerminal` directly — if you've forked / modified that file, double-check you didn't switch back to `TerminalBuilder.builder()...build()`.

### No colours in the terminal

Two possibilities:

- `NO_COLOR` is set in the environment of the backend JVM (we honour the standard).
- Your SSH client's terminal type doesn't support ANSI. The portal sends raw escapes; the client decodes them. xterm-256color, screen, tmux all work. If you're behind some weird shell, try `TERM=xterm ssh ...`.

### Commands run but produce no output

Check that you're talking to a backend that has the SessionStream installed at startup. If you've turned `devportal.cli.enabled` on/off without a JVM restart, the post-construct hook may not have fired. A clean restart fixes it.

### Tab completion is incomplete

Completion is built from picocli's static command tree at session start. If you've added new commands and devtools auto-restarted, your existing SSH session keeps the *old* completion until you reconnect. Disconnect (Ctrl-D) and reconnect to pick up new commands.

---

## See also

- [README — CLI / SSH access](../README.md#cli--ssh-access) — short summary + login.
- [README — Lifecycle hooks](../README.md#lifecycle-hooks-test-fixtures--setup-hooks) and [docs/lifecycle-hooks.md](lifecycle-hooks.md) — the test-fixture model that `fixture run` / `--run-hooks` invoke.
- [README — MCP server](../README.md#mcp-server) — the same operations exposed to Claude Code over stdio.
