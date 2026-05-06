#!/usr/bin/env bash
# devportal-backup-setup.sh — bootstrap a state repo + key-based SSH for backups.
#
# Walks through:
#   1. Either init or clone a git repo to hold backups.
#   2. Patch application.yml so devportal.backup.dir points at it.
#   3. Add the local SSH public key to ~/.devportal/secrets/authorized_keys
#      so cron can run `ssh devportal -p 2222 'backup create ...'` without
#      a password prompt.
#   4. Run a smoke-test backup.
#
# Re-runnable: every step is idempotent.

set -euo pipefail

usage() {
    cat <<EOF
Usage: $(basename "$0") [--repo PATH | --clone GIT_URL [--into PATH]] [--key FILE]

  --repo PATH        Local path to use as the backup root (will be init'd if not a git repo).
  --clone GIT_URL    Clone a remote repo to use as the backup root.
  --into PATH        Where to clone (default: ~/code/devportal-state).
  --key FILE         Public key file to authorize (default: ~/.ssh/id_ed25519.pub or id_rsa.pub).
  --skip-key         Don't touch authorized_keys (you handle your own auth).
  --skip-config      Don't patch application.yml (you'll set it manually).
  --help             Show this help.
EOF
}

repo_path=""
clone_url=""
into_path="$HOME/code/devportal-state"
key_file=""
skip_key=false
skip_config=false

while [ $# -gt 0 ]; do
    case "$1" in
        --repo)         repo_path="$2"; shift 2 ;;
        --clone)        clone_url="$2"; shift 2 ;;
        --into)         into_path="$2"; shift 2 ;;
        --key)          key_file="$2"; shift 2 ;;
        --skip-key)     skip_key=true; shift ;;
        --skip-config)  skip_config=true; shift ;;
        --help)         usage; exit 0 ;;
        *)              printf 'unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

if [ -z "$repo_path" ] && [ -z "$clone_url" ]; then
    printf 'pick one of --repo or --clone\n\n' >&2
    usage >&2
    exit 2
fi

step() { printf '\n==> %s\n' "$*"; }

# Step 1 — repo
if [ -n "$clone_url" ]; then
    step "Cloning $clone_url into $into_path"
    if [ -d "$into_path/.git" ]; then
        printf '   already a git repo, skipping clone\n'
    else
        mkdir -p "$(dirname "$into_path")"
        git clone "$clone_url" "$into_path"
    fi
    repo_path="$into_path"
else
    step "Preparing $repo_path"
    mkdir -p "$repo_path"
    if [ ! -d "$repo_path/.git" ]; then
        git -C "$repo_path" init -b main
        printf '   git initialised\n'
    else
        printf '   already a git repo\n'
    fi
fi

# Step 2 — application.yml
if ! $skip_config; then
    step "Patching backend/src/main/resources/application.yml"
    config="$(dirname "$0")/../backend/src/main/resources/application.yml"
    if [ ! -f "$config" ]; then
        printf '   could not find application.yml at %s — skipping\n' "$config"
    else
        if grep -q 'devportal.backup.dir' "$config" 2>/dev/null \
           || grep -q '^  backup:' "$config"; then
            # Just point the existing key at the new repo.
            python3 - "$config" "$repo_path" <<'PY'
import re, sys, pathlib
path = pathlib.Path(sys.argv[1])
target = sys.argv[2]
text = path.read_text()
new = re.sub(
    r'(  backup:\n(?:.*\n)*?    dir: )[^\n]+',
    lambda m: m.group(1) + target,
    text,
    count=1,
)
if new != text:
    path.write_text(new)
    print(f'   updated devportal.backup.dir → {target}')
else:
    print(f'   could not patch automatically; please set devportal.backup.dir to {target}')
PY
        else
            printf '   no devportal.backup section found — please add manually:\n'
            printf '       devportal.backup.dir: %s\n' "$repo_path"
        fi
    fi
fi

# Step 3 — authorized_keys
if ! $skip_key; then
    step "Authorising public key for SSH"
    if [ -z "$key_file" ]; then
        for candidate in ~/.ssh/id_ed25519.pub ~/.ssh/id_rsa.pub ~/.ssh/id_ecdsa.pub; do
            if [ -f "$candidate" ]; then key_file="$candidate"; break; fi
        done
    fi
    if [ -z "$key_file" ] || [ ! -f "$key_file" ]; then
        printf '   no public key found; pass --key FILE or --skip-key\n'
    else
        ak="$HOME/.devportal/secrets/authorized_keys"
        mkdir -p "$(dirname "$ak")"
        touch "$ak"; chmod 600 "$ak"
        if grep -qFf "$key_file" "$ak"; then
            printf '   key already present in %s\n' "$ak"
        else
            cat "$key_file" >> "$ak"
            printf '   appended %s to %s\n' "$key_file" "$ak"
        fi
    fi
fi

# Step 4 — smoke-test backup
step "Running smoke-test backup"
script_dir="$(cd "$(dirname "$0")" && pwd)"
"$script_dir/devportal-backup.sh" --commit --message "setup smoke test" \
    || printf '   backup smoke-test failed; check that the backend is running and reachable\n'

step "Done"
printf 'Repo:        %s\n' "$repo_path"
printf 'Add a cron entry like:\n'
printf '   30 2 * * * %s/devportal-backup.sh --commit --push --quiet --message "nightly $(date -I)"\n' "$script_dir"
