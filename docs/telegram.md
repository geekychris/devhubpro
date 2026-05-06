# Telegram bot

DevPortal can run a Telegram bot that accepts commands by chat — same command surface as the SSH CLI, same picocli command tree, same backing services. Sending `asset list` in Telegram is identical to typing `asset list` in `ssh devportal -p 2222`.

This is a thin bridge: each Telegram message → one `cli.execute(argv)` call → output captured, ANSI stripped, wrapped in a `<pre>` code block, split if it exceeds Telegram's 4096-char message limit.

---

## Table of contents

- [What this is good for](#what-this-is-good-for)
- [Architecture](#architecture)
- [Setup](#setup)
  - [1. Create a bot via @BotFather](#1-create-a-bot-via-botfather)
  - [2. Run the setup script](#2-run-the-setup-script)
  - [3. Enable and restart](#3-enable-and-restart)
- [Configuration reference](#configuration-reference)
- [Using the bot](#using-the-bot)
- [Output formatting](#output-formatting)
- [Allowlist management](#allowlist-management)
- [Group chats](#group-chats)
- [Bot command menu (@BotFather setcommands)](#bot-command-menu-botfather-setcommands)
- [Recipes — common workflows](#recipes--common-workflows)
- [Multi-device behaviour](#multi-device-behaviour)
- [Comparison with other access methods](#comparison-with-other-access-methods)
- [Audit and logging](#audit-and-logging)
- [Telegram rate limits](#telegram-rate-limits)
- [Phone shortcuts and home-screen automation](#phone-shortcuts-and-home-screen-automation)
- [Security model](#security-model)
- [Troubleshooting](#troubleshooting)

---

## What this is good for

- Phone-driven admin while away from the dev box: `dashboard running`, `pod logs my-svc abc123`, `backup create`.
- Push-button operations from a chat client that's already pinned on your home screen.
- Quick verification of a deploy from anywhere — `k8s status my-svc` returns a YAML snapshot in seconds.
- Anything you'd otherwise SSH in to do, but with a worse autocomplete experience.

What it's *not* great for:
- Long log streams (Telegram messages cap at 4096 chars; the bot splits but it gets unwieldy past a few hundred KB).
- Interactive flows (`workspace shell`, `pod exec`) — no PTY over Telegram.
- Tab completion — there isn't any. Use `help <command>` for syntax.

---

## Architecture

```
Telegram message  ─┐
                   ▼
  TelegramBot (long-poll, pengrad lib)
                   │
  filter chat_id ──┴── allowlist file
                   │
  parse argv (Bourne-ish split, same as SSH)
                   │
  CommandLine.execute(argv) ───────► same RootCommand tree as SSH
                   │
  capture System.out via SessionStream (ThreadLocal)
                   │
  strip ANSI, wrap in <pre>, split if >4096
                   │
  sendMessage ─────► Telegram message
```

Single shared bot instance, ThreadLocal-bound output stream per message, so two simultaneous chats don't see each other's results. The bot polls Telegram's `/getUpdates` endpoint over HTTPS — no inbound port required, works behind NAT, no public URL needed.

---

## Setup

You have two paths — the **Settings UI** (point-and-click, lives at <http://localhost:5173/settings> under the "Telegram bot" panel) or the **shell script** (`scripts/devportal-telegram-setup.sh`, guided walkthrough). Both end up with the same files on disk; pick whichever matches the moment.

### Option A — Settings UI

1. **Master switch**: set `devportal.telegram.enabled: true` in `application.yml` and restart the backend. The UI can manage the token and allowlist *while the bot is disabled*, so you can prep everything before flipping the switch.
2. Open <http://localhost:5173/settings> and find the **Telegram bot** panel.
3. **Token** — paste the @BotFather token in the input, click Save. The portal writes it mode-0600 to `~/.devportal/secrets/telegram-bot-token`. Click **Test connection** — calls Telegram's `getMe` and reports the bot's username (`ok — bot is @yourbotname`) or the rejection reason.
4. **Allowlist** — type your numeric chat id and click Add. Don't know your chat id? Once the token is saved and the bot is running (master switch on), open the bot in Telegram and send any message. The bot replies with `Not authorized. Your chat id: 12345678`. Copy that number into the UI.
5. The UI also has **Restart bot** (no-op if disabled) and **Clear token** (also stops the bot). The bot picks up token changes immediately — no JVM restart needed.

### Option B — Shell script

### 1. Create a bot via @BotFather

In Telegram:

1. Open a chat with [@BotFather](https://t.me/BotFather).
2. Send `/newbot`. BotFather asks for a display name (free-form, e.g. "Chris's DevPortal") and a username (must end in `bot`, e.g. `chris_devportal_bot`).
3. BotFather replies with a token like `1234567890:AAH-abcdef0123456789ABCDEF0123456789a`. Copy it.

### 2. Run the setup script

```sh
cd dev_portal/scripts
./devportal-telegram-setup.sh
```

The script:

1. Asks for the bot token (or reads it from `--token`); writes it to `~/.devportal/secrets/telegram-bot-token` (mode 0600).
2. Calls `getMe` to confirm Telegram accepts the token, prints the bot username.
3. Prompts you to send any message to the bot.
4. Polls `getUpdates` for up to 120s, finds your chat id, appends it to `~/.devportal/secrets/telegram-allowlist`.
5. Reminds you to flip `devportal.telegram.enabled: true` and restart.

Re-run the script any time — every step is idempotent. To pre-supply the token from a script:

```sh
./devportal-telegram-setup.sh --token "1234:AAH..."
```

### 3. Enable and restart (option B continued)

```yaml
devportal:
  telegram:
    enabled: true
```

```sh
cd backend && ./gradlew bootRun
```

Watch the log for:

```
INFO  io.devportal.telegram.TelegramService : Telegram bot listening (allowlist=1, allow-groups=false)
```

Send a test message in Telegram: `health check`. The bot replies with a YAML snapshot in a code block.

---

## Configuration reference

```yaml
devportal:
  telegram:
    enabled: false                                  # opt-in; default off
    bot-token-file: ${user.home}/.devportal/secrets/telegram-bot-token
    allowlist-file: ${user.home}/.devportal/secrets/telegram-allowlist
    allow-groups: false                             # respond in group chats too
    long-message-mode: split                        # split | truncate | file
```

| Key                 | Default                                                      | Purpose                                                                                                                |
|---------------------|--------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `enabled`           | `false`                                                      | Master switch. Off by default since the bot talks to a third-party API.                                                |
| `bot-token-file`    | `~/.devportal/secrets/telegram-bot-token`                    | Single-line file containing the @BotFather token. Mode 0600 enforced by the setup script.                             |
| `allowlist-file`    | `~/.devportal/secrets/telegram-allowlist`                    | One numeric chat id per line. Empty/missing = no chat is authorized (every message is rejected).                       |
| `allow-groups`      | `false`                                                      | When false, the bot only responds in private (DM) chats. Group chat ids can still be added to the allowlist if needed. |
| `long-message-mode` | `split`                                                      | What to do when a reply exceeds 4096 chars: `split` into multiple messages, `truncate` with a marker, or send as a `file` attachment. |

The token file and allowlist file are re-read on every message — change either and the next message picks up the new state, no restart needed.

---

## Using the bot

Anything you'd type at `devportal>` works as a Telegram message. Telegram-style commands with a leading `/` also work and are unambiguously routed to the same picocli tree.

```
asset list -q redis
backup create --message "from the train"
dashboard running
pod logs my-svc my-svc-abc123 --tail 50
build kick my-svc --mode deep
search run "ratelimit"
```

Or with a leading slash (Telegram's bot-command convention; the suffix `@yourbotname` that Telegram appends in groups is also stripped):

```
/health check
/asset get my-svc
/help asset
```

Built-in shortcuts:

- `/start` — welcome banner.
- `help` or `/help` — picocli's built-in help, lists every command group.
- `help <command>` — detail for one command (e.g. `help backup`, `help asset list`).

---

## Output formatting

The bot:

- Strips ANSI escape sequences (Telegram clients don't render them — without stripping you'd see literal `[31m` garbage).
- Wraps every reply in `<pre>...</pre>` so monospace tables stay aligned.
- HTML-escapes `&`, `<`, `>` in the body so output containing tags doesn't confuse Telegram's renderer.
- Splits replies > 4096 chars on line boundaries. Multi-part replies are tagged `(N/M)` at the bottom.

If `long-message-mode: file` is set, replies bigger than 4096 chars are sent as a `output.txt` attachment instead of split. Useful when you want to scroll through a long log on your phone.

`long-message-mode: truncate` keeps the first chunk and appends `…(truncated)`. Useful when you genuinely don't want spam.

---

## Allowlist management

Three places to manage the allowlist:

### From the Settings UI

<http://localhost:5173/settings> → **Telegram bot** panel → "Authorized chat ids". Paste a numeric id, click Add. Each entry has a Remove button. Same backing file as the CLI commands — both surfaces edit `~/.devportal/secrets/telegram-allowlist` directly, and the bot re-reads on every message.

### From inside the SSH CLI (recommended)

```
telegram status                   # bot running? how many allowed?
telegram allowlist                # list ids
telegram allow 12345678           # authorize a chat id
telegram deny 12345678            # remove
```

These commands write to the allowlist file directly; the bot picks up the change on the next message.

### From Telegram itself (if already authorized)

A user already on the allowlist can run `telegram allow <chat-id>` to add another. Useful for inviting a new family member or admin without dropping back to a shell.

### By editing the file

```sh
echo 12345678 >> ~/.devportal/secrets/telegram-allowlist
```

One id per line. Comments (lines starting with `#`) are allowed.

### REST API

The Settings UI talks to these endpoints; you can hit them directly too.

| Verb     | Path                                        | Body                       | Purpose                                            |
|----------|---------------------------------------------|----------------------------|----------------------------------------------------|
| `GET`    | `/api/settings/telegram`                    | —                          | Token status + bot status + allowlist size + paths |
| `PUT`    | `/api/settings/telegram/token`              | `{"token":"..."}`          | Save the token, restart the bot if enabled        |
| `DELETE` | `/api/settings/telegram/token`              | —                          | Clear the token, stop the bot                     |
| `POST`   | `/api/settings/telegram/test`               | —                          | Call `getMe`; returns `{ok, username, message}`   |
| `GET`    | `/api/settings/telegram/allowlist`          | —                          | `{ "chatIds": [ ... ] }`                          |
| `POST`   | `/api/settings/telegram/allowlist`          | `{"chatId":12345678}`      | Add a chat id (also when negative for groups)     |
| `DELETE` | `/api/settings/telegram/allowlist/{chatId}` | —                          | Remove a chat id                                  |
| `POST`   | `/api/settings/telegram/restart`            | —                          | Bounce the bot — useful after editing files manually |

The full bot token is never returned; the status response shows just a redacted preview (`1234567890:AAH…XYZ`).

---

## Group chats

By default `allow-groups: false` — the bot only responds in private chats. To enable group support:

```yaml
devportal:
  telegram:
    allow-groups: true
```

In a group, the chat id is **negative** (e.g. `-1001234567890` for supergroups). Add the negative chat id to the allowlist.

Group caveats:

- Anyone in the group can issue commands. The allowlist is per-chat, not per-user. If you have multiple admins in the group, all get the same level of access.
- BotFather sets bots as group privacy mode by default — they only see messages that mention them or that start with `/`. Disable privacy via BotFather (`/setprivacy` → Disable) if you want plain-text commands without `/` to also reach the bot.

---

## Bot command menu (@BotFather setcommands)

Telegram clients show a `/` menu with autocompletion for any commands you register with @BotFather. By default, your bot has none — typing `/` in chat shows just `/help` and `/start` placeholders. To get the full devportal command tree as suggestions:

1. Open @BotFather on Telegram.
2. Send `/setcommands`.
3. Pick your bot.
4. Paste this list (or a subset — only the ones you actually use):

```
asset - List / get / register / update / delete assets and dependency edges
build - Kick builds, watch progress, tail logs
port - Allocate / release / inspect port reservations
k8s - Apply, delete, status, render, diagnostics
pod - Pods, logs, describe, events
docker - Build, run, list, stop containers
forward - kubectl port-forward sessions
endpoint - Discovered URLs for an asset
fixture - Run lifecycle hooks / test fixtures
dashboard - Live dashboard view
search - Global search across assets and docs
backup - Snapshot + restore portal state
state - Export / import state YAML
analyze - Validate manifest, run pom analysis, auto-wire
audit - Drift report against conventions
verify - Boot-check an asset
import - Bulk-import a GitHub org
meta - Meta-assets and consumes edges
graph - Reachable producer/consumer graph
docs - Per-asset markdown
git_info - GitHub metadata for an asset
panel - Server-driven panels
prompt - Build an Ask-Claude prompt
scaffold - Generate Dockerfile / k8s manifests
settings - GitHub PAT management
tag - Tag catalog
telegram - Manage the bot's allowlist
workspace - Local workspace status / commit / push
macro - Composite operations (spinup, teardown, audit-all, sh)
health - Backend liveness check
help - Show command help
```

Telegram requires lowercase letters, digits, and underscores in command names — so `git_info` works but `git-info` doesn't. The bot accepts either form (it normalises before dispatch).

After `setcommands`, typing `/` in the chat shows the menu. Tap a command, hit space, and start typing the rest. This is the closest you'll get to tab completion in Telegram.

To clear the menu later: `@BotFather → /setcommands → pick bot → send "empty"`.

---

## Recipes — common workflows

### Morning state-of-the-world

```
dashboard running
backup list
build recent --limit 5
```

Three messages, three quick replies. Tells you what's live, when the last backup ran, and what you built last.

### Deploy verification from the train

```
asset get my-svc
build kick my-svc --mode deep
build progress 240
```

Wait until `build progress` shows everything green, then:

```
k8s apply my-svc --include runtime --run-hooks
endpoint list my-svc
```

The endpoint list is what to share in chat with whoever's testing.

### Triage a flaky pod

```
pod list my-svc
pod logs my-svc my-svc-abc123 --tail 100
pod events my-svc
k8s diagnostics my-svc
```

For very long logs, set `long-message-mode: file` in `application.yml` so the 100-line tail comes back as an attachment instead of split messages.

### Restore from yesterday's snapshot (last-resort)

```
backup list
backup restore /Users/me/.devportal/backups/20260506-031734
```

The bot has the same destructive-op exposure as the SSH CLI — there's **no confirmation prompt** in Telegram. If you want a safety gate, use `scripts/devportal-restore.sh` over SSH instead; the bot is the express lane.

### Run a one-off shell command in an asset workspace

```
macro sh my-svc -- git log --oneline -10
macro sh my-svc -- ls -la src/main/resources/
```

Output streams back as a code block. The `--` after the asset id is picocli's "everything after this is positional"; without it, picocli would try to interpret `-10` or `-la` as flags.

### Get a credential dump for the running app

```
fixture list my-svc
fixture run my-svc seed-tenants
```

The result is a YAML block that includes the parsed credentials table — copy-paste the username/password into your test client.

### Check what changed in the codebase

```
search run "ratelimit" --no-docs
asset deps my-svc
graph show my-svc --producer-depth 2
```

Useful when you remember "we changed something about that recently" but not what or where.

---

## Multi-device behaviour

Telegram sessions are device-side; your bot doesn't know or care which device you typed from. So:

- **Same allowlist works on phone + desktop + tablet + web.** All your devices use the same chat id (the user id is what Telegram pins to the chat, not the device).
- **Output renders identically** because the bot wraps in `<pre>` and Telegram's HTML renderer is consistent across clients. Tables that look right on the desktop look right on the phone (just narrower).
- **Long messages are split the same way** regardless of viewing device — the chunking happens server-side.
- **Notifications** are a Telegram client setting, not a bot setting. Mute the chat in one client and not another, or set a per-device notification schedule, all without changing anything on the portal side.

If you want a separate bot for separate use cases (e.g. a "loud" deployment-notification bot vs a "quiet" admin bot), create two bots via @BotFather and run two backend instances pointing at different token files.

---

## Comparison with other access methods

| Need                                    | UI (5173)        | REST (8081)         | MCP (Claude Code) | SSH (`ssh -p 2222`) | Telegram bot     |
|-----------------------------------------|------------------|---------------------|-------------------|---------------------|------------------|
| Browse the catalog with thumbnails      | ✓                | —                   | —                 | —                   | —                |
| Driving by AI / Claude                  | —                | (with helpers)      | ✓                 | (theoretical)       | (theoretical)    |
| Scriptable from cron / CI               | —                | ✓                   | —                 | ✓                   | —                |
| Phone-driven from anywhere              | (responsive UI)  | (with helpers)      | —                 | (mosh / Termius)    | ✓                |
| Tab completion + history                | —                | —                   | —                 | ✓                   | (via /setcommands) |
| Output sized for code blocks            | —                | (JSON only)         | —                 | ✓                   | ✓                |
| Live progress streaming                 | ✓                | (poll)              | (poll)            | ✓                   | (poll, via build progress) |
| Public-internet exposure                | (with reverse proxy) | (with reverse proxy) | —             | (ngrok / WireGuard) | ✓ (built-in)     |
| Audit log of who ran what               | (via web logs)   | (via web logs)      | —                 | (via SSH log)       | (via Telegram metadata) |
| Interactive shells / TTY                | ✓ (xterm.js)     | —                   | —                 | ✓                   | —                |

The Telegram bot's strongest niche is **phone-driven admin without setting up tunnels**. The bot polls Telegram's API, so the portal never needs to be reachable from the internet — handy when the dev machine is behind NAT, on a coffee-shop wifi, or behind a corporate VPN where you can't ingress.

---

## Audit and logging

The backend logs every Telegram interaction at INFO level via `io.devportal.telegram.TelegramService`:

```
INFO  io.devportal.telegram.TelegramService : Telegram bot listening (allowlist=1, allow-groups=false)
INFO  io.devportal.telegram.TelegramService : rejected message from un-allowlisted chat 1234567 (alice)
```

What's logged:

- Bot startup with allowlist size + group-chat policy.
- **Rejected** messages: chat id + Telegram username (`@alice`). The message body is *not* logged.
- **Accepted** messages: not logged separately. The picocli command itself runs and produces whatever logs the underlying service emits — same as if you'd run the command from SSH.

What this means for audit:

- Every state-changing operation has its existing log line (e.g. `BackupService` logs every backup). You can correlate "this backup was created at 14:30" with "this Telegram chat sent a message at 14:30".
- The Telegram username is the closest thing to a user identity. If you add multiple chat ids to the allowlist, you can tell who's running what by username (assuming they keep the same Telegram account).
- For stronger audit you'd need to wrap the command dispatch in a log line that includes chat id + command line. The current implementation deliberately doesn't log command bodies (some commands include tokens, e.g. `settings github-set <pat>`) — if you need full audit, fork `TelegramService.handleUpdate` and log selectively.

---

## Telegram rate limits

Telegram's limits (last checked against their public docs):

- **30 messages per second** to different users globally.
- **20 messages per minute** to a single group.
- **1 message per second** to a single user (private chat).

The bot's reply pattern is one message per command (or N for split outputs > 4096 chars). If you batch-fire 50 commands at the bot, expect throttling on the way back — Telegram returns HTTP 429 and the bot logs:

```
WARN  io.devportal.telegram.TelegramService : Telegram getUpdates returned 429: Too Many Requests
```

Mitigations baked in:

- The bot doesn't batch outbound messages — each `cli.execute` produces one inbound, one (or split) outbound.
- The pengrad library handles 429 responses by sleeping for the `retry_after` value Telegram returns; you don't need to do anything.
- Large `pod logs` or `build log` outputs in `split` mode produce many messages; flip to `long-message-mode: file` to avoid hitting the per-user-per-second limit on a single big command.

For 99% of single-user usage you'll never see a rate limit. If you do, that's a signal you're using the bot for a workload that wants the REST API instead.

---

## Phone shortcuts and home-screen automation

Once a chat with your bot exists, you can promote it to a one-tap shortcut on your phone:

- **iOS**: Telegram chat → swipe right on the chat → "Pin" puts it at the top. Or use the **Shortcuts** app: "Open URL" with `tg://resolve?domain=yourbotname` opens the bot directly.
- **Android**: Long-press the chat in Telegram → "Add to home screen" creates a launcher icon.
- **Telegram inline mode** (`@yourbotname query`): the bot doesn't currently support inline mode — that's a different bot API surface and would route through `inline_query` updates, not `message` updates. Could be added; not currently wired.

For automated triggers (e.g. "fire `backup create` every night without involving cron on the dev machine"), don't try to use the Telegram bot side — bots can't receive messages they sent themselves, and impersonating a user requires the much-more-complex MTProto user API. Instead, use the **REST API** or **SSH** from your cron host:

```sh
# REST — no auth in single-user local mode, just hit the backend directly:
curl -X POST http://your-portal:8081/api/backup -d '{"commit":true,"push":true,"message":"nightly"}'

# SSH — same allowlist as Telegram, key-based auth:
ssh devportal -p 2222 'backup create --commit --push --message "nightly"'
```

The Telegram bot's purpose is interactive admin from a phone — for headless automation, REST and SSH are the right primitives.

---

## Security model

The bot has the same privileges as the backend JVM — it can do everything the SSH CLI can do, including:

- Read your GitHub PAT (`settings github-show`).
- Run shell commands inside any asset workspace (`macro sh`).
- Trigger destructive operations (`backup restore`, `asset delete`, `k8s delete`).

Mitigations baked in:

- **Allowlist-only.** Empty allowlist = nobody. The first unauthorized message gets a one-time reply revealing the sender's chat id; subsequent messages from that chat are silently dropped.
- **Token file is mode 0600.** Anyone with the token can impersonate the bot; the setup script enforces the mode.
- **Token sniff guard in CLI output.** `settings github-show` deliberately returns a preview only, never the full token.
- **No PTY/exec.** The bot can run `pod logs` but not `pod exec` (no interactive WebSocket through Telegram). `macro sh` does run shell commands — be conservative about who you authorize.
- **HTTPS only.** Telegram's bot API is HTTPS, so your traffic is encrypted in transit. Telegram still sees your message content though — if you don't want them to, don't run the bot.

If you suspect compromise:

1. Revoke the bot via @BotFather (`/revoke`, pick the bot, get a new token).
2. Update `~/.devportal/secrets/telegram-bot-token` with the new token.
3. Restart the backend.
4. Rotate your GitHub PAT and any other credentials the bot might have surfaced.

---

## Troubleshooting

### Bot doesn't reply

Check, in order:

1. `devportal.telegram.enabled: true`?
2. Token file exists and is non-empty?
3. Backend log shows `Telegram bot listening`?
4. Your chat id is in the allowlist file?
5. You're DMing the bot (or `allow-groups: true` if it's a group)?

### "Not authorized" reply with my chat id

Working as designed — the bot revealed your chat id once. Add it to the allowlist with `telegram allow <id>` from the SSH CLI, or paste it directly into the allowlist file.

### Bot says "(no output, exit 0)"

The command executed successfully but produced no output. Some commands work this way (e.g. `asset delete foo` prints `deleted foo` but `port release foo` returns just an exit code). Try `--json` if available.

### Long output is being mangled

Telegram caps individual messages at 4096 chars. By default the bot splits on line boundaries; set `long-message-mode: file` if you'd rather get attachments for big outputs (e.g. when running `pod logs` with high `--tail`).

### "Telegram getUpdates returned 401"

Token is wrong or revoked. Re-create via @BotFather, run the setup script with `--token <new>`.

### Spam protection

If a non-allowlisted user keeps sending messages, they get the "your chat id is N" reveal exactly once per chat — repeated messages from the same chat id are silently dropped. To rate-limit further, set up a Telegram bot privacy filter via @BotFather's `/setprivacy` (group-only, but still helpful in groups).

### Picking up new commands without restarting

Spring DevTools restart picks up new `@Component @Command` classes; the bot keeps polling and the next message routes through the new command tree. No need to restart the bot itself.

---

## See also

- [README — Telegram bot](../README.md#telegram-bot) — short summary.
- [docs/cli.md](cli.md) — full command catalog (the same commands the bot exposes).
- [scripts/devportal-telegram-setup.sh](../scripts/devportal-telegram-setup.sh) — guided setup walkthrough.
