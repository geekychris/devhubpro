# Backup and restore

DevPortal can snapshot its full state into a timestamped folder, optionally committing the folder into a git repo for off-host durability and history. Restore reverses the operation.

The default backup root is `~/.devportal/backups/`. The root can sit anywhere — including **inside an existing git repository** (the dev_portal source repo, a dedicated `devportal-state` repo, or any other) — and the portal will auto-commit each new backup if it detects a git working tree wrapping the path.

---

## Table of contents

- [Quickstart with the helper scripts](#quickstart-with-the-helper-scripts)
- [What's in a backup](#whats-in-a-backup)
- [On-disk layout](#on-disk-layout)
- [Configuration](#configuration)
- [Creating backups](#creating-backups)
- [Listing backups](#listing-backups)
- [Cron-driven nightly backups](#cron-driven-nightly-backups)
- [Restoring](#restoring)
- [Disaster recovery walkthrough](#disaster-recovery-walkthrough)
- [Putting backups in a git repo](#putting-backups-in-a-git-repo)
- [Retention](#retention)
- [REST API](#rest-api)
- [Security: when (not) to include secrets](#security-when-not-to-include-secrets)
- [Backup vs `state git-sync`](#backup-vs-state-git-sync)
- [Recovery scenarios](#recovery-scenarios)

---

## Quickstart with the helper scripts

Three shell scripts in [`scripts/`](../scripts/) automate the common flows. Each is a thin wrapper around `ssh devportal -p 2222 'backup …'` that handles BatchMode, host key verification, confirmation gates, and the one-time setup steps.

### One-time setup

```sh
cd dev_portal/scripts

# Pattern 1 — clone an existing private state repo:
./devportal-backup-setup.sh --clone git@github.com:me/devportal-state.git

# Pattern 2 — initialise a new local repo at a path of your choice:
./devportal-backup-setup.sh --repo ~/code/devportal-state

# Pattern 3 — keep code + state in the dev_portal source repo on a `state` branch:
./devportal-backup-setup.sh --repo ~/code/dev_portal/state-backups
```

The setup script:

1. Inits or clones the chosen git repo.
2. Patches `application.yml` so `devportal.backup.dir` points at it.
3. Drops your local SSH public key into `~/.devportal/secrets/authorized_keys` (so the next steps don't need a password).
4. Runs a smoke-test backup.
5. Prints the exact cron line you'd add for nightly backups.

Re-run the script any time — every step is idempotent.

### Day-to-day backups

```sh
# minimal — state-only, default location:
./devportal-backup.sh

# full snapshot before a risky migration:
./devportal-backup.sh --secrets --logs --message "pre-migration"

# nightly into a state repo, pushed to origin:
./devportal-backup.sh --commit --push --message "nightly $(date -I)"
```

Flags ([full list with `./devportal-backup.sh --help`](../scripts/devportal-backup.sh)):

| Flag                  | Meaning                                                                                |
|-----------------------|----------------------------------------------------------------------------------------|
| `--secrets`           | Include `~/.devportal/secrets/`. Off by default.                                       |
| `--logs`              | Include `~/.devportal/logs/`. Off by default.                                          |
| `--dir PATH`          | Override the default backup root.                                                      |
| `--commit`            | Force a git commit (even when auto-commit is off).                                      |
| `--push`              | Push the commit. Requires the target repo to have a remote.                             |
| `-m, --message MSG`   | Commit message.                                                                        |
| `-q, --quiet`         | Suppress informational output. Enables SSH `BatchMode=yes` for cron.                    |
| `-h, --host HOST`     | Override `$DEVPORTAL_HOST`.                                                             |
| `-p, --port PORT`     | Override `$DEVPORTAL_PORT`.                                                             |
| `-i, --identity FILE` | SSH key file (passes through as `ssh -i`).                                              |

### Restoring

```sh
# interactive picker — lists backups, prompts for one, confirms before destructive op:
./devportal-restore.sh

# direct, by stamp:
./devportal-restore.sh 20260506-091820

# by absolute path (for backups outside the configured root):
./devportal-restore.sh /Users/me/code/devportal-state/20260506-091820

# include secrets (replaces stored tokens) and bypass confirmation (for unattended scripts):
./devportal-restore.sh 20260506-091820 --secrets --no-confirm
```

The script runs `backup list --json` over SSH, finds the matching folder, prints what it's about to do, and asks for `y/N` before invoking the destructive `backup restore` command. `--no-confirm` skips the prompt — only use this from a controlled script.

---

## What's in a backup

| Bucket    | Contents                                                                                       | Default     |
|-----------|------------------------------------------------------------------------------------------------|-------------|
| `state/`  | Postgres-of-record exported as YAML — every asset record + its dependency edges + index/README. | always      |
| `secrets/`| `~/.devportal/secrets/` — GitHub PAT, SSH password file, host key, `authorized_keys`.          | opt-in      |
| `logs/`   | `~/.devportal/logs/` — captured build stdout/stderr.                                            | opt-in      |
| `manifest.json` | Bundle metadata: schema version, timestamp, hostname, what's included, asset count.       | always      |

State is the *source of truth* — restoring it brings every asset, lifecycle setting, dependency edge, favorite, rating, and dashboard pin back exactly as they were. The repo URLs in those records still point at the original GitHub repos, so workspaces re-clone on demand.

Secrets and logs are **opt-in** because:

- **Secrets** include tokens — committing them into a shared/public git repo would leak credentials. Include them only when the backup repo is itself private and trusted.
- **Logs** can be tens of MB per build and aren't usually load-bearing for recovery. Include them when you specifically need to reproduce a build environment forensically.

---

## On-disk layout

A single backup is one timestamped folder under the configured root. Folder name is `yyyyMMdd-HHmmss` in UTC.

```
~/.devportal/backups/
├── 20260506-031734/
│   ├── manifest.json
│   └── state/
│       ├── index.yaml
│       ├── README.md
│       └── assets/
│           ├── enterprise-social-platform.yaml
│           ├── hitorro-spring-boot.yaml
│           └── … (one file per asset)
├── 20260506-091820/
│   ├── manifest.json
│   ├── state/...
│   ├── secrets/                    # only when --secrets was passed
│   │   ├── github-token            # mode 0600
│   │   ├── ssh-password            # mode 0600
│   │   └── ssh-host-key
│   └── logs/                       # only when --logs was passed
│       └── *.log
└── …
```

`manifest.json`:

```json
{
  "schemaVersion" : 1,
  "createdAt" : "2026-05-06T03:17:34.504009Z",
  "hostname" : "mactorro.local",
  "includesState" : true,
  "includesSecrets" : false,
  "includesLogs" : false,
  "assetCount" : 130,
  "logCount" : 0,
  "note" : "Generated by io.devportal.backup.BackupService."
}
```

Older backup folders sit alongside newer ones in the same root — restore picks the one you point it at.

---

## Configuration

```yaml
devportal:
  backup:
    dir: ${user.home}/.devportal/backups   # backup root (env: DEVPORTAL_BACKUP_DIR)
    auto-commit: true                      # if dir is inside a git working tree, commit each new backup automatically
    keep-last: 30                          # retention; older folders pruned from the working tree (git history is untouched)
```

Override per-call from the CLI with `--dir`, `--commit`, `--push`, `--message`. Environment override:

```sh
DEVPORTAL_BACKUP_DIR=/Users/me/code/devportal-state ./gradlew bootRun
```

---

## Creating backups

### CLI

```sh
# state-only, default location, auto-commit if dir is in a git repo:
backup create

# include secrets (be careful where the backup lands):
backup create --secrets

# include build logs too (can be large):
backup create --secrets --logs

# write into a specific repo and force a commit:
backup create --dir /Users/me/code/devportal-state --commit --message "before refactor"

# also push the commit to origin:
backup create --commit --push --message "nightly"
```

Output:

```
created /Users/me/.devportal/backups/20260506-031734
stamp    20260506-031734
assets   130
logs     0
secrets  no
commit   e9f421f083
pruned   0
```

### One-shot SSH

Each `ssh devportal` invocation runs the command and disconnects, so you can drive backups from cron:

```sh
# crontab entry — nightly backup at 2:30, into the devportal-state repo, push to origin:
30 2 * * * ssh devportal -p 2222 'backup create --dir /home/me/code/devportal-state --commit --push --message "nightly $(date -I)"'
```

### REST API

```sh
curl -X POST http://localhost:8081/api/backup -H 'Content-Type: application/json' -d '{
  "includeSecrets": false,
  "includeLogs": false,
  "commit": true,
  "push": false,
  "message": "after auto-wire"
}'
```

Returns the same `BackupSummary` shape as the CLI.

---

## Listing backups

```sh
backup list
# STAMP            ASSETS  LOGS  SECRETS  CREATED
# ---------------  ------  ----  -------  ---------------------------
# 20260506-091820  130     12    ✓        2026-05-06T09:18:20.123Z
# 20260506-031734  130     0     ·        2026-05-06T03:17:34.504Z

# JSON for piping:
backup list --json | jq '.[] | select(.includesSecrets) | .stamp'

# list inside a specific repo:
backup list --dir /Users/me/code/devportal-state
```

---

## Cron-driven nightly backups

Once the helper scripts are installed and your public key is authorised, a single crontab line drives the rest:

```cron
# crontab — nightly backup at 02:30 local time, push to origin, log silently unless something breaks:
30 2 * * * /home/me/code/dev_portal/scripts/devportal-backup.sh --commit --push --quiet --message "nightly $(date -I)"
```

`--quiet` enables SSH `BatchMode=yes`, so if something blocks the connection (key not authorised, SSH server down, host key mismatch) the script exits non-zero immediately and cron's mail-the-user-on-error machinery surfaces the failure. There's no path that hangs at a password prompt.

Equivalent systemd timer (Linux):

```ini
# ~/.config/systemd/user/devportal-backup.service
[Unit]
Description=DevPortal nightly backup

[Service]
Type=oneshot
ExecStart=/home/me/code/dev_portal/scripts/devportal-backup.sh --commit --push --quiet --message "nightly %%i"
```

```ini
# ~/.config/systemd/user/devportal-backup.timer
[Unit]
Description=DevPortal nightly backup at 02:30

[Timer]
OnCalendar=*-*-* 02:30:00
Persistent=true

[Install]
WantedBy=timers.target
```

```sh
systemctl --user enable --now devportal-backup.timer
journalctl --user -u devportal-backup -n 50    # see recent runs
```

Equivalent macOS launchd plist (`~/Library/LaunchAgents/io.devportal.backup.plist`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>            <string>io.devportal.backup</string>
  <key>ProgramArguments</key>
  <array>
    <string>/Users/me/code/dev_portal/scripts/devportal-backup.sh</string>
    <string>--commit</string>
    <string>--push</string>
    <string>--quiet</string>
    <string>--message</string><string>nightly</string>
  </array>
  <key>StartCalendarInterval</key>
  <dict>
    <key>Hour</key>           <integer>2</integer>
    <key>Minute</key>         <integer>30</integer>
  </dict>
  <key>StandardErrorPath</key><string>/tmp/devportal-backup.err</string>
  <key>StandardOutPath</key>  <string>/tmp/devportal-backup.out</string>
</dict>
</plist>
```

```sh
launchctl load ~/Library/LaunchAgents/io.devportal.backup.plist
launchctl start io.devportal.backup           # one-shot test run
```

---

## Restoring

Restore is **destructive** — it wipes existing portal state before re-loading from the backup. Always run `backup create` first if you might want to roll back.

```sh
# state-only restore (most common):
backup restore /Users/me/.devportal/backups/20260506-031734

# also restore secrets (replaces tokens / SSH password / host key):
backup restore /Users/me/.devportal/backups/20260506-091820 --secrets

# also restore build logs (rare):
backup restore /Users/me/.devportal/backups/20260506-091820 --secrets --logs
```

Output:

```
restored from 20260506-031734
assets   130
edges    47
secrets  no
logs     no
```

Under the hood:

1. Read + validate `manifest.json` (schema version check).
2. Wipe the StateService directory (preserving its `.git/` if any), copy the backup's `state/` over the top.
3. Call `StateService.importTree()` — drops existing assets + edges, re-inserts from YAML.
4. If `--secrets`: copy `secrets/` to `~/.devportal/secrets/` (mode 0600).
5. If `--logs`: copy `logs/` to `~/.devportal/logs/`.

**What restore does *not* do**: it doesn't touch port reservations, build records, fixture run history, or workspace clones on disk. Port reservations regenerate when you `port allocate`; build history is intentionally local; workspaces re-clone on first use.

---

## Disaster recovery walkthrough

Step-by-step, using the helper scripts. Assume your laptop's primary disk has died and you're rebuilding on a fresh machine. You have your `devportal-state` repo cloned somewhere (or you're cloning it now) and you have the dev_portal source.

```sh
# 1. Bring up Postgres + run Flyway migrations by starting the backend once.
cd ~/code/dev_portal/backend
./gradlew bootRun &
# wait for "Started DevPortalApplication" then ctrl-c, or leave it running and use a second terminal

# 2. Restore your SSH host key and ssh-password from the backup so the new portal
#    has a stable identity (optional — without this you'd just regenerate them).
#    Authorise your local public key first so the next steps don't need passwords:
echo "$(cat ~/.ssh/id_rsa.pub)" >> ~/.devportal/secrets/authorized_keys
chmod 600 ~/.devportal/secrets/authorized_keys

# 3. Restore from the most recent backup. Include --secrets so the GitHub PAT, SSH host
#    key, and authorized_keys all come back together (will overwrite the file you just wrote).
cd ~/code/dev_portal/scripts
./devportal-restore.sh                          # interactive picker
# (pick the latest stamp, hit y at the confirm prompt)

# After the restore, the GitHub PAT, host key, etc. are all back. Workspaces will
# re-clone on demand the first time you build / run anything against an asset.
```

If the entire dev_portal source repo is gone too, clone that first (`git clone …`), `cd backend && ./gradlew build`, then proceed.

---

## Putting backups in a git repo

Three sensible patterns:

### Pattern 1 — dedicated `devportal-state` repo (recommended)

A small private repo just for backups. Clone it once, point the backup root at it.

```sh
git clone git@github.com:me/devportal-state.git ~/code/devportal-state
```

```yaml
devportal:
  backup:
    dir: ~/code/devportal-state
    auto-commit: true
```

Each `backup create` writes a new timestamped folder, commits it, optionally pushes. Each backup is its own commit; `git log` shows you the chronology.

### Pattern 2 — same repo as the dev_portal source

If you'd rather keep code + state in one place, point the backup root at a sub-folder of the dev_portal repo:

```yaml
devportal:
  backup:
    dir: ${user.home}/code/dev_portal/state-backups
```

Add `state-backups/` to `.gitignore` *only if you don't want them tracked*; otherwise leave it. Each `backup create` becomes a commit on whatever branch you currently have checked out, which is fine if you cut a `state` branch and check it out before backing up:

```sh
cd ~/code/dev_portal && git checkout -b state
ssh devportal -p 2222 'backup create --commit --message "after rename"'
git push -u origin state
```

The dev_portal repo grows with backup commits, but they're isolated to the branch.

### Pattern 3 — ad-hoc, anywhere

Drop a backup somewhere that's not yet a git repo, then `--commit` to init it on the spot:

```sh
backup create --dir /tmp/throwaway --commit --message smoke
```

The portal calls `git init` on first commit, so this Just Works.

---

## Retention

`devportal.backup.keep-last` (default 30) prunes older folders from the working tree once the count exceeds the threshold. **Git history is not touched** — older backups live on as commits even after their files disappear from the current checkout. To physically reduce repo size, run `git gc --aggressive` or rewrite history with `git filter-repo`; the portal won't do that for you.

Set `keep-last: 0` to disable pruning entirely (useful when you want every backup to remain visible on disk).

---

## REST API

| Verb   | Path                | Body                                 | Returns                                                                    |
|--------|---------------------|--------------------------------------|----------------------------------------------------------------------------|
| `POST` | `/api/backup`         | `CreateBackupRequest` (all optional) | `BackupSummary` (201 Created)                                              |
| `GET`  | `/api/backup`         | —                                    | `List<BackupSummary>` (newest first)                                       |
| `POST` | `/api/backup/restore` | `RestoreBackupRequest` (source required) | `RestoreResult` ({assetsRestored, edgesRestored, secretsRestored, …})  |

DTO shapes:

```jsonc
// CreateBackupRequest
{
  "includeSecrets": false,    // default false
  "includeLogs":    false,    // default false
  "dir":            null,     // override the configured dir for this call
  "commit":         true,     // also do a git commit
  "push":           false,    // and push the commit (requires a remote)
  "message":        "..."     // optional, defaults to "backup <stamp>"
}

// RestoreBackupRequest
{
  "source":         "/path/to/20260506-091820",  // required
  "includeSecrets": false,
  "includeLogs":    false
}

// BackupSummary
{
  "stamp":            "20260506-091820",
  "dir":              "/Users/me/.devportal/backups/20260506-091820",
  "assetCount":       130,
  "logCount":         12,
  "includesSecrets":  true,
  "includesLogs":     true,
  "commitSha":        "e9f421f083e12cf795664f4dd24e11889f174ed2",
  "prunedOldBackups": 0,
  "createdAt":        "2026-05-06T09:18:20.123Z"
}
```

---

## Security: when (not) to include secrets

`secrets/` contains the GitHub PAT, the SSH host key, the SSH password file, and `authorized_keys`. Anything that gets committed to a git repo *stays* in that repo's history forever, even if you later remove it. So:

- **Don't commit secrets to a public repo.** Pattern 1 (private `devportal-state` repo) is safe; Pattern 2 (dev_portal source repo) is dangerous unless your dev_portal repo is private.
- **If you accidentally commit secrets**, the only safe response is to *rotate the credentials* (regenerate the GitHub PAT, regenerate the SSH host key, change the password). Removing the file from git history doesn't help — assume it's been read.
- **For local-only storage** (`~/.devportal/backups/` with no remote), secrets are fine to include and make full disaster recovery easier. The folder mirrors the 0600 mode of the source.
- **Air-gapped move**: if you need to copy state to another machine, secrets-included backups are convenient. Use `scp` or a USB stick rather than git, and delete the backup after restoring.

---

## Backup vs `state git-sync`

The existing `state git-sync` command writes the asset YAML tree into `~/.devportal/state/` and commits it as a flat directory. It's a single rolling snapshot — the same files keep getting rewritten.

`backup` is a layer on top:

|                            | `state git-sync`                  | `backup create`                                  |
|----------------------------|-----------------------------------|--------------------------------------------------|
| Output                     | one rolling dir (`~/.devportal/state/`) | one new timestamped folder per call              |
| History                    | git log of the same files         | git log of separate folders, plus filesystem timestamps |
| Includes secrets / logs    | no                                | opt-in                                           |
| Restore counterpart        | `state import`                    | `backup restore PATH`                            |
| Manifest                   | `index.yaml` (asset id list)      | `manifest.json` (timestamp, contents, hostname)  |
| Use case                   | incremental sync to a state repo  | point-in-time recovery, audit trail              |

You can use both — `state git-sync` for continuous sync of just the DB, `backup` for periodic full snapshots that include secrets.

---

## Recovery scenarios

### "I broke the database — restore from last night"

```sh
backup list
# 20260506-091820 …
backup restore /Users/me/.devportal/backups/20260506-091820
```

Asset records, lifecycle, favorites, ratings, dashboard pins, and dependency edges all come back. Workspaces re-clone on first use; port reservations regenerate when you `port allocate`.

### "My laptop died — bring up devportal on a new machine"

1. On the new machine, install Postgres + run Flyway migrations (start the backend once to bootstrap the schema).
2. Either `git clone` your `devportal-state` repo (Pattern 1) or copy the backup folder via `scp`.
3. `backup restore <folder> --secrets` to pull in tokens + SSH host key.
4. Done.

You'll lose: workspace clones (re-cloned on demand), running k8s state, in-flight builds, port reservations (regenerated). Everything else is recovered.

### "I want to see what changed since last week"

If your backup root is a git repo, `git diff` between two backup commits shows exactly which assets changed:

```sh
cd /Users/me/code/devportal-state
git log --since="1 week ago" --oneline
git diff <old-sha>..<new-sha> 20260429-031734/state/assets/ 20260506-031734/state/assets/
```

### "I want to migrate to a new schema"

Backup before, restore manifest-validated against the old schema version. The manifest's `schemaVersion: 1` lets the service refuse mismatched restores so you don't silently write old YAML into a newer DB layout.

---

## See also

- [README — CLI / SSH access](../README.md#cli--ssh-access)
- [docs/cli.md](cli.md) — full CLI reference, including the `backup` command group.
- [README — State sync](../README.md#state-sync) — the simpler, rolling sync mechanism.
