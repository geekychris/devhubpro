# scripts/

Helper shell scripts that wrap the embedded SSH CLI for common operations. Each script reads ssh defaults from environment variables and accepts overrides on the command line.

| Script                      | Purpose                                                                                          |
|-----------------------------|--------------------------------------------------------------------------------------------------|
| `devportal-backup.sh`       | Run a backup. Cron-friendly with `--quiet` (BatchMode SSH, fail fast on any prompt).             |
| `devportal-restore.sh`      | Pick a backup (interactive list or by stamp/path), confirm, restore. Always destructive.        |
| `devportal-backup-setup.sh` | Bootstrap a state repo, patch `application.yml`, install your public key, run a smoke backup.   |
| `devportal-telegram-setup.sh` | Walk through @BotFather, save the token, poll for your chat id, append it to the allowlist. |

## Environment

```sh
export DEVPORTAL_HOST=127.0.0.1     # or hostname when running cron from a different box
export DEVPORTAL_PORT=2222
export DEVPORTAL_USER=devportal     # username is ignored — single-user local
export DEVPORTAL_IDENTITY=~/.ssh/id_rsa   # passed to ssh -i
```

## Quickstart

```sh
# one-time setup — clone a state repo, authorize your key, take the first backup:
./devportal-backup-setup.sh --clone git@github.com:me/devportal-state.git

# afterwards — periodic backup:
./devportal-backup.sh --commit --push --message "ad-hoc"

# disaster recovery — pick from a list:
./devportal-restore.sh
```

## Cron-friendly invocations

`--quiet` enables `BatchMode=yes` so SSH won't hang at a password prompt; failures exit non-zero and surface via cron's mail/mta machinery.

```cron
# nightly state-only backup, push to origin, log silently unless something breaks:
30 2 * * * /home/me/code/dev_portal/scripts/devportal-backup.sh --commit --push --quiet --message "nightly $(date -I)"
```

## Authentication

Mina (the embedded SSH server) supports RSA, ECDSA, and Ed25519. Public-key auth requires the public key in `~/.devportal/secrets/authorized_keys` on the portal host. The setup script handles that:

```sh
./devportal-backup-setup.sh --skip-config --key ~/.ssh/id_rsa.pub
```

For Ed25519 keys, ensure the backend was built with the eddsa library on the classpath (it's pulled in via `build.gradle.kts` since this repo's CLI added). RSA always works.

## Why scripts at all

You could just type `ssh devportal -p 2222 'backup create --commit --push'` directly — the scripts exist so:

- Cron / launchd / systemd units have a single fixed command path that handles BatchMode, host key verification, retry logic.
- `devportal-restore.sh` adds a confirmation gate before the destructive operation.
- `devportal-backup-setup.sh` codifies the half-dozen one-time setup steps.

If a script doesn't fit your workflow, copy and edit — they're short bash, not magic.
