#!/usr/bin/env bash
# devportal-restore.sh — pick a backup and restore it. Destructive: confirms before running.
#
# Examples:
#   devportal-restore.sh                                      # interactive picker
#   devportal-restore.sh 20260506-091820                      # restore by stamp (state-only)
#   devportal-restore.sh /full/path/to/20260506-091820        # restore by absolute path
#   devportal-restore.sh 20260506-091820 --secrets --logs     # full restore including secrets
#   devportal-restore.sh --no-confirm 20260506-091820         # skip the confirmation prompt
#
# Restore is *destructive* — it wipes the live state directory and re-imports from
# the backup. The script always confirms unless --no-confirm is passed.

set -euo pipefail

usage() {
    cat <<EOF
Usage: $(basename "$0") [options] [STAMP|PATH]

Restore from a portal backup via the embedded SSH CLI. Always destructive on the
state buckets it touches.

Selection:
  STAMP                  Backup folder name (e.g. 20260506-091820). Resolved against the
                         configured backup root on the server.
  PATH                   Absolute path to a backup folder.
  (none)                 List available backups and prompt for one.

Optional buckets:
  --secrets              Also restore ~/.devportal/secrets/ from the bundle.
  --logs                 Also restore ~/.devportal/logs/ from the bundle.

Safety:
  --no-confirm           Skip the "are you sure" prompt. Required for non-interactive use.

Connection:
  -h, --host HOST        Portal host (default: \$DEVPORTAL_HOST or 127.0.0.1)
  -p, --port PORT        SSH port (default: \$DEVPORTAL_PORT or 2222)
  -u, --user USER        SSH user (default: \$DEVPORTAL_USER or devportal)
  -i, --identity FILE    SSH identity file
  --help                 Show this help.
EOF
}

host="${DEVPORTAL_HOST:-127.0.0.1}"
port="${DEVPORTAL_PORT:-2222}"
user="${DEVPORTAL_USER:-devportal}"
identity="${DEVPORTAL_IDENTITY:-}"

include_secrets=false
include_logs=false
no_confirm=false
target=""

while [ $# -gt 0 ]; do
    case "$1" in
        --secrets)            include_secrets=true; shift ;;
        --logs)               include_logs=true; shift ;;
        --no-confirm)         no_confirm=true; shift ;;
        -h|--host)            host="$2"; shift 2 ;;
        -p|--port)            port="$2"; shift 2 ;;
        -u|--user)            user="$2"; shift 2 ;;
        -i|--identity)        identity="$2"; shift 2 ;;
        --help)               usage; exit 0 ;;
        -*)                   printf 'unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
        *)                    target="$1"; shift ;;
    esac
done

ssh_args=(
    -o StrictHostKeyChecking=accept-new
    -o ConnectTimeout=10
    -p "$port"
)
[ -n "$identity" ] && ssh_args+=( -i "$identity" )

run_remote() {
    ssh "${ssh_args[@]}" "$user@$host" "$@"
}

# If the user gave a stamp (no leading slash) we need to resolve it to a full path
# by asking the server for the configured backup dir + stamp. The CLI list output
# already has the path; we filter by stamp.
resolve_path() {
    local input="$1"
    if [[ "$input" = /* ]]; then
        printf '%s\n' "$input"
        return
    fi
    # Pull JSON listing and find the entry with matching stamp.
    local json
    json=$(run_remote "backup list --json")
    local path
    if command -v python3 >/dev/null 2>&1; then
        path=$(printf '%s' "$json" | STAMP="$input" python3 -c "
import json, os, sys
stamp = os.environ['STAMP']
for b in json.loads(sys.stdin.read()):
    if b.get('stamp') == stamp:
        print(b.get('dir', ''))
        break
") || true
    else
        # Best-effort grep fallback — works when each entry is on its own line.
        path=$(printf '%s' "$json" \
            | tr -d '\n' \
            | grep -oE '\{[^}]*"stamp"[[:space:]]*:[[:space:]]*"'"$input"'"[^}]*\}' \
            | grep -oE '"dir"[[:space:]]*:[[:space:]]*"[^"]*"' \
            | sed -E 's/.*"dir"[[:space:]]*:[[:space:]]*"([^"]+)"/\1/' \
            | head -1) || true
    fi
    if [ -z "$path" ]; then
        printf 'no backup found with stamp %s\n' "$input" >&2
        exit 1
    fi
    printf '%s\n' "$path"
}

interactive_pick() {
    printf 'Available backups:\n\n' >&2
    run_remote "backup list" >&2
    printf '\nEnter STAMP (or PATH) to restore: ' >&2
    read -r picked </dev/tty || { printf '\n' >&2; exit 1; }
    [ -n "$picked" ] || { printf 'cancelled\n' >&2; exit 1; }
    printf '%s\n' "$picked"
}

if [ -z "$target" ]; then
    if [ ! -t 0 ]; then
        printf 'no STAMP/PATH given and stdin is not a tty — refusing to guess\n' >&2
        exit 2
    fi
    target=$(interactive_pick)
fi

full_path=$(resolve_path "$target")

# Confirmation gate. Show what's about to happen.
if ! $no_confirm; then
    printf '\nAbout to restore from:\n  %s\n' "$full_path" >&2
    printf 'Buckets: state' >&2
    $include_secrets && printf ', secrets' >&2
    $include_logs && printf ', logs' >&2
    printf '\n\nThis WIPES existing portal state before importing. Continue? [y/N] ' >&2
    read -r ans </dev/tty || ans=""
    case "$ans" in
        y|Y|yes|YES) ;;
        *) printf 'cancelled\n' >&2; exit 1 ;;
    esac
fi

cmd="backup restore $(printf %q "$full_path")"
$include_secrets && cmd="$cmd --secrets"
$include_logs && cmd="$cmd --logs"

run_remote "$cmd"
