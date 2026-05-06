#!/usr/bin/env bash
# devportal-backup.sh — drive `backup create` over the embedded SSH CLI.
#
# Designed for both interactive use ("just back up now, please") and cron use
# ("run nightly, push to origin, no chatter unless something breaks").
#
# Examples:
#   devportal-backup.sh                                         # state-only, default dir
#   devportal-backup.sh --secrets --commit --push               # full snapshot, push to remote
#   devportal-backup.sh --dir ~/code/devportal-state --commit   # write into a specific repo
#   DEVPORTAL_HOST=portal.lan devportal-backup.sh --quiet       # cron-friendly, ssh to remote host
#
# Auth: relies on ssh's normal resolution (public key first, falling back to
# whatever your client is configured for). For cron use, set up public-key auth
# in ~/.devportal/secrets/authorized_keys on the portal host.

set -euo pipefail

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Run a portal backup via the embedded SSH CLI.

Backup contents:
  --secrets             Include ~/.devportal/secrets/ (tokens, host key, password). Opt-in.
  --logs                Include ~/.devportal/logs/ (build logs, can be large). Opt-in.

Storage / git:
  --dir PATH            Override the configured backup root for this call.
  --commit              Force a git commit even if auto-commit is off.
  --push                Push the commit (requires a remote on the target repo).
  -m, --message MSG     Commit message (default: "backup <stamp>").

Connection:
  -h, --host HOST       Portal host (default: \$DEVPORTAL_HOST or 127.0.0.1)
  -p, --port PORT       SSH port (default: \$DEVPORTAL_PORT or 2222)
  -u, --user USER       SSH user (default: \$DEVPORTAL_USER or devportal — the username is ignored)
  -i, --identity FILE   SSH identity file (passed through as ssh -i)

Other:
  -q, --quiet           Suppress informational output (for cron). Errors still surface.
  --help                Show this help.

Environment defaults:
  DEVPORTAL_HOST, DEVPORTAL_PORT, DEVPORTAL_USER, DEVPORTAL_IDENTITY
EOF
}

host="${DEVPORTAL_HOST:-127.0.0.1}"
port="${DEVPORTAL_PORT:-2222}"
user="${DEVPORTAL_USER:-devportal}"
identity="${DEVPORTAL_IDENTITY:-}"

include_secrets=false
include_logs=false
backup_dir=""
do_commit=false
do_push=false
message=""
quiet=false

while [ $# -gt 0 ]; do
    case "$1" in
        --secrets)            include_secrets=true; shift ;;
        --logs)               include_logs=true; shift ;;
        --dir)                backup_dir="$2"; shift 2 ;;
        --commit)             do_commit=true; shift ;;
        --push)               do_push=true; shift ;;
        -m|--message)         message="$2"; shift 2 ;;
        -h|--host)            host="$2"; shift 2 ;;
        -p|--port)            port="$2"; shift 2 ;;
        -u|--user)            user="$2"; shift 2 ;;
        -i|--identity)        identity="$2"; shift 2 ;;
        -q|--quiet)           quiet=true; shift ;;
        --help)               usage; exit 0 ;;
        *)                    printf 'unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

# Build the inner CLI command.
cmd="backup create"
$include_secrets && cmd="$cmd --secrets"
$include_logs && cmd="$cmd --logs"
$do_commit && cmd="$cmd --commit"
$do_push && cmd="$cmd --push"
[ -n "$backup_dir" ] && cmd="$cmd --dir $(printf %q "$backup_dir")"
[ -n "$message" ] && cmd="$cmd --message $(printf %q "$message")"

ssh_args=(
    -o StrictHostKeyChecking=accept-new
    -o ConnectTimeout=10
    -p "$port"
)
# In quiet mode (cron, scripts), refuse password prompts so we fail fast instead of hanging.
$quiet && ssh_args+=( -o BatchMode=yes )
[ -n "$identity" ] && ssh_args+=( -i "$identity" )

$quiet || printf 'devportal-backup: %s@%s:%s -- %s\n' "$user" "$host" "$port" "$cmd"

if $quiet; then
    ssh "${ssh_args[@]}" "$user@$host" "$cmd" >/dev/null
else
    ssh "${ssh_args[@]}" "$user@$host" "$cmd"
fi
