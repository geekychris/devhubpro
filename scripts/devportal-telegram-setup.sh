#!/usr/bin/env bash
# devportal-telegram-setup.sh — walk through Telegram bot setup.
#
#   1. Stash a bot token (created via @BotFather) at ~/.devportal/secrets/telegram-bot-token.
#   2. Open the bot in Telegram, send any message to it.
#   3. Run this script — it polls getUpdates, finds your chat id, and writes it to
#      ~/.devportal/secrets/telegram-allowlist.
#   4. Set devportal.telegram.enabled: true in application.yml and restart the backend.
#
# Re-runnable: every step is idempotent.

set -euo pipefail

usage() {
    cat <<EOF
Usage: $(basename "$0") [--token TOKEN]

  --token TOKEN     Skip the interactive prompt and use this bot token.
  --skip-config     Don't suggest enabling devportal.telegram.enabled in application.yml.
  --help            Show this help.

Workflow:
  1. Create a bot via @BotFather on Telegram (/newbot, pick a name, get a token).
  2. Re-run this script and paste the token (or pass via --token).
  3. Open your bot in Telegram and send any message.
  4. The script discovers your chat id and authorises it.
  5. Set devportal.telegram.enabled: true and restart the backend.
EOF
}

token_in=""
skip_config=false

while [ $# -gt 0 ]; do
    case "$1" in
        --token)        token_in="$2"; shift 2 ;;
        --skip-config)  skip_config=true; shift ;;
        --help)         usage; exit 0 ;;
        *)              printf 'unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

step() { printf '\n==> %s\n' "$*"; }

token_file="$HOME/.devportal/secrets/telegram-bot-token"
allow_file="$HOME/.devportal/secrets/telegram-allowlist"

mkdir -p "$(dirname "$token_file")"

# Step 1 — token
step "Bot token"
if [ -n "$token_in" ]; then
    printf '%s' "$token_in" > "$token_file"
    chmod 600 "$token_file"
    printf '   wrote %s (mode 0600)\n' "$token_file"
elif [ -s "$token_file" ]; then
    printf '   already present at %s\n' "$token_file"
else
    printf '   Create a bot via @BotFather on Telegram:\n'
    printf '     1. Open a chat with @BotFather.\n'
    printf '     2. Send /newbot, follow the prompts.\n'
    printf '     3. Copy the token (looks like 1234567890:AAH...).\n\n'
    printf '   Paste token (or empty to abort): '
    read -r tok </dev/tty
    if [ -z "$tok" ]; then printf 'aborted\n' >&2; exit 1; fi
    printf '%s' "$tok" > "$token_file"
    chmod 600 "$token_file"
    printf '   wrote %s\n' "$token_file"
fi

token=$(<"$token_file")

# Step 2 — verify token works (getMe)
step "Verifying token (getMe)"
me=$(curl -fsS "https://api.telegram.org/bot${token}/getMe" 2>/dev/null) \
    || { printf '   getMe failed — token rejected by Telegram\n' >&2; exit 1; }
username=$(printf '%s' "$me" | grep -oE '"username"[[:space:]]*:[[:space:]]*"[^"]+"' \
    | sed -E 's/.*:[[:space:]]*"([^"]+)"/\1/' | head -1)
printf '   bot is @%s\n' "$username"
printf '   open https://t.me/%s in Telegram and send the bot any message\n' "$username"

# Step 3 — wait for a message and discover the chat id
step "Discovering your chat id (polling getUpdates)"
printf '   waiting for a message — send "hello" to @%s now...\n' "$username"
chat_id=""
for i in $(seq 1 120); do
    updates=$(curl -fsS "https://api.telegram.org/bot${token}/getUpdates?timeout=1&offset=-1" 2>/dev/null || true)
    chat_id=$(printf '%s' "$updates" | grep -oE '"chat":\{[^}]*"id"[[:space:]]*:[[:space:]]*-?[0-9]+' \
        | grep -oE '-?[0-9]+$' | tail -1)
    if [ -n "$chat_id" ]; then break; fi
    sleep 1
    [ $((i % 10)) = 0 ] && printf '   still waiting (%ds)...\n' "$i"
done
if [ -z "$chat_id" ]; then
    printf '   no message detected after 120s; aborting. Send the bot a message and re-run.\n' >&2
    exit 1
fi
printf '   detected chat id: %s\n' "$chat_id"

# Step 4 — append to allowlist
step "Authorising chat id"
touch "$allow_file"
chmod 600 "$allow_file"
if grep -qE "^${chat_id}\$" "$allow_file" 2>/dev/null; then
    printf '   already authorised\n'
else
    printf '%s\n' "$chat_id" >> "$allow_file"
    printf '   appended to %s\n' "$allow_file"
fi

# Step 5 — application.yml hint
if ! $skip_config; then
    step "Enable the bot"
    config="$(dirname "$0")/../backend/src/main/resources/application.yml"
    if [ -f "$config" ] && grep -q '^  telegram:' "$config"; then
        if grep -qE '^\s+enabled:\s*true' <(awk '/^  telegram:/,/^[a-z]/' "$config"); then
            printf '   devportal.telegram.enabled is already true\n'
        else
            printf '   set in %s:\n' "$config"
            printf '       devportal.telegram.enabled: true\n'
            printf '   then restart the backend (./gradlew bootRun) — devtools is fine for runtime config but a fresh start is cleaner.\n'
        fi
    else
        printf '   set devportal.telegram.enabled: true in application.yml manually.\n'
    fi
fi

step "Done"
printf 'Bot:        @%s\n' "$username"
printf 'Chat id:    %s\n' "$chat_id"
printf 'Token file: %s\n' "$token_file"
printf 'Allowlist:  %s\n' "$allow_file"
printf '\nNext: send any CLI command to the bot in Telegram.\n'
printf 'Examples: "asset list", "backup create --message hello", "help".\n'
