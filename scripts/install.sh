#!/usr/bin/env bash
#
# DevHub Pro — one-shot installer for direct (docker compose) mode.
#
# Clones the repo, brings up Postgres via docker compose, builds backend and
# frontend, and (optionally) starts them in the background. Designed to be
# invoked as:
#
#   curl -fsSL https://raw.githubusercontent.com/geekychris/devhubpro/main/scripts/install.sh | bash
#
# Re-runs are safe: existing checkout is `git pull`ed, Postgres is reused,
# builds are incremental.
#
# Environment knobs:
#   DEVPORTAL_SRC     install directory (default: $HOME/.devportal/src)
#   DEVPORTAL_REF     git ref to check out (default: main)
#   DEVPORTAL_REPO    git remote (default: https://github.com/geekychris/devhubpro.git)
#   DEVPORTAL_START         "1" to launch backend+frontend in background (default: 1)
#   DEVPORTAL_OPEN          "1" to open http://localhost:5173 when ready (default: 1)
#   DEVPORTAL_AUTO_INSTALL  "1" to brew/apt/dnf install missing deps (default: 0)

set -euo pipefail

DEVPORTAL_SRC="${DEVPORTAL_SRC:-$HOME/.devportal/src}"
DEVPORTAL_REF="${DEVPORTAL_REF:-main}"
DEVPORTAL_REPO="${DEVPORTAL_REPO:-https://github.com/geekychris/devhubpro.git}"
DEVPORTAL_START="${DEVPORTAL_START:-1}"
DEVPORTAL_OPEN="${DEVPORTAL_OPEN:-1}"
DEVPORTAL_AUTO_INSTALL="${DEVPORTAL_AUTO_INSTALL:-0}"

RUN_DIR="$HOME/.devportal/run"
LOG_DIR="$HOME/.devportal/logs"
mkdir -p "$RUN_DIR" "$LOG_DIR"

c_reset=$'\033[0m'; c_bold=$'\033[1m'; c_dim=$'\033[2m'
c_green=$'\033[32m'; c_yellow=$'\033[33m'; c_red=$'\033[31m'; c_blue=$'\033[34m'

step() { printf "%s==>%s %s%s%s\n" "$c_blue" "$c_reset" "$c_bold" "$*" "$c_reset"; }
ok()   { printf "%s ✓%s %s\n" "$c_green" "$c_reset" "$*"; }
warn() { printf "%s ! %s%s\n" "$c_yellow" "$c_reset" "$*"; }
die()  { printf "%s ✗ %s%s\n" "$c_red" "$c_reset" "$*" >&2; exit 1; }

# Non-interactive shells (curl | bash) inherit a minimal PATH and miss
# directories that login shells get from /etc/profile.d (snap, user-local).
for _d in /snap/bin /var/lib/snapd/snap/bin /usr/local/bin "$HOME/.local/bin"; do
  if [ -d "$_d" ]; then
    case ":$PATH:" in *:"$_d":*) ;; *) PATH="$_d:$PATH" ;; esac
  fi
done
export PATH

# ---- platform detection + install hints ---------------------------------
case "$(uname -s)" in
  Darwin) OS=mac ;;
  Linux)
    if [ -r /etc/os-release ]; then
      . /etc/os-release
      case "$ID" in
        ubuntu|debian|linuxmint|pop) OS=debian ;;
        fedora|rhel|centos|rocky|almalinux) OS=fedora ;;
        arch|manjaro) OS=arch ;;
        *) OS=linux ;;
      esac
    else OS=linux; fi ;;
  *) OS=unknown ;;
esac

install_hint() {
  case "$OS:$1" in
    mac:git)        echo "brew install git" ;;
    mac:java)       echo "brew install openjdk@21 && echo 'export PATH=\"\$(brew --prefix openjdk@21)/bin:\$PATH\"' >> ~/.zshrc" ;;
    mac:node)       echo "brew install node" ;;
    mac:pnpm)       echo "corepack enable  (or: brew install pnpm)" ;;
    mac:docker)     echo "brew install --cask docker  (or Rancher Desktop)" ;;
    debian:git)     echo "sudo apt-get install -y git" ;;
    debian:java)    echo "sudo apt-get install -y openjdk-21-jdk" ;;
    debian:node)    echo "curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - && sudo apt-get install -y nodejs" ;;
    debian:pnpm)    echo "corepack enable  (or: sudo npm install -g pnpm)" ;;
    debian:docker)  echo "see https://docs.docker.com/engine/install/ubuntu/" ;;
    fedora:git)     echo "sudo dnf install -y git" ;;
    fedora:java)    echo "sudo dnf install -y java-21-openjdk-devel" ;;
    fedora:node)    echo "sudo dnf install -y nodejs npm" ;;
    fedora:pnpm)    echo "corepack enable" ;;
    fedora:docker)  echo "see https://docs.docker.com/engine/install/fedora/" ;;
    arch:git)       echo "sudo pacman -S --noconfirm git" ;;
    arch:java)      echo "sudo pacman -S --noconfirm jdk21-openjdk" ;;
    arch:node)      echo "sudo pacman -S --noconfirm nodejs npm" ;;
    arch:pnpm)      echo "corepack enable" ;;
    arch:docker)    echo "sudo pacman -S --noconfirm docker && sudo systemctl enable --now docker" ;;
    *)              echo "install $1 (no hint for $OS — see your package manager)" ;;
  esac
}

auto_install() {
  local pkg="$1"
  step "Auto-installing $pkg (DEVPORTAL_AUTO_INSTALL=1)"
  case "$OS:$pkg" in
    mac:git)        brew install git ;;
    mac:java)       brew install openjdk@21 && export PATH="$(brew --prefix openjdk@21)/bin:$PATH" ;;
    mac:node)       brew install node ;;
    mac:pnpm)       corepack enable ;;
    mac:docker)     die "Docker requires GUI install on macOS — install Docker Desktop or Rancher Desktop, then re-run" ;;
    debian:git)     sudo -p "[sudo] " apt-get update -qq </dev/tty && sudo apt-get install -y git </dev/tty ;;
    debian:java)    sudo -p "[sudo] " apt-get update -qq </dev/tty && sudo apt-get install -y openjdk-21-jdk </dev/tty ;;
    debian:node)    curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - </dev/tty && sudo apt-get install -y nodejs </dev/tty ;;
    debian:pnpm)    corepack enable ;;
    debian:docker)  die "auto-install of docker on debian/ubuntu isn't safe to scriptize — see $(install_hint docker)" ;;
    fedora:git)     sudo dnf install -y git </dev/tty ;;
    fedora:java)    sudo dnf install -y java-21-openjdk-devel </dev/tty ;;
    fedora:node)    sudo dnf install -y nodejs npm </dev/tty ;;
    fedora:pnpm)    corepack enable ;;
    arch:git)       sudo pacman -S --noconfirm git </dev/tty ;;
    arch:java)      sudo pacman -S --noconfirm jdk21-openjdk </dev/tty ;;
    arch:node)      sudo pacman -S --noconfirm nodejs npm </dev/tty ;;
    arch:pnpm)      corepack enable ;;
    *)              die "no auto-install recipe for $pkg on $OS — $(install_hint "$pkg")" ;;
  esac
}

# Make Homebrew openjdk@21 visible if the user installed it but didn't link it.
if [ "$OS" = "mac" ] && command -v brew >/dev/null 2>&1 && ! command -v java >/dev/null 2>&1; then
  if [ -d "$(brew --prefix openjdk@21 2>/dev/null)/bin" ]; then
    export PATH="$(brew --prefix openjdk@21)/bin:$PATH"
  fi
fi

require() {
  local bin="$1" pkg="$2"
  if command -v "$bin" >/dev/null 2>&1; then return; fi
  if [ "$DEVPORTAL_AUTO_INSTALL" = "1" ]; then
    auto_install "$pkg"
    command -v "$bin" >/dev/null 2>&1 || die "after auto-install, '$bin' is still missing — $(install_hint "$pkg")"
    return
  fi
  die "missing '$bin' on PATH — $(install_hint "$pkg")
     (or re-run with DEVPORTAL_AUTO_INSTALL=1 to attempt automatic install)"
}

step "Checking prerequisites ($OS)"
require git git
require docker docker
docker info >/dev/null 2>&1 || die "docker daemon is not reachable — start Docker / Rancher Desktop"
require java java
java_major=$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F. '{print $1}')
[ "${java_major:-0}" -ge 21 ] || die "Java 21+ required (have: $java_major) — $(install_hint java)"
require node node
node_major=$(node -v | sed 's/^v//' | awk -F. '{print $1}')
[ "${node_major:-0}" -ge 20 ] || die "Node 20+ required (have: $node_major) — $(install_hint node)"
if ! command -v pnpm >/dev/null 2>&1; then
  step "Enabling pnpm via corepack"
  corepack enable >/dev/null 2>&1 || die "corepack failed — $(install_hint pnpm)"
fi
ok "prereqs ok (java $java_major, node $node_major, docker, pnpm)"

step "Fetching source -> $DEVPORTAL_SRC ($DEVPORTAL_REF)"
if [ -d "$DEVPORTAL_SRC/.git" ]; then
  git -C "$DEVPORTAL_SRC" fetch --quiet origin "$DEVPORTAL_REF"
  git -C "$DEVPORTAL_SRC" checkout --quiet "$DEVPORTAL_REF"
  git -C "$DEVPORTAL_SRC" pull --quiet --ff-only origin "$DEVPORTAL_REF" || warn "could not fast-forward; continuing with current HEAD"
else
  mkdir -p "$(dirname "$DEVPORTAL_SRC")"
  git clone --quiet --branch "$DEVPORTAL_REF" "$DEVPORTAL_REPO" "$DEVPORTAL_SRC"
fi
ok "source ready at $DEVPORTAL_SRC"

step "Starting Postgres (docker compose)"
(cd "$DEVPORTAL_SRC" && docker compose up -d) >/dev/null
ok "postgres up on :5432"

step "Building backend (./gradlew build)"
(cd "$DEVPORTAL_SRC/backend" && ./gradlew --quiet -x test build)
ok "backend built"

step "Building frontend (pnpm install && pnpm build)"
(cd "$DEVPORTAL_SRC/frontend" && pnpm --silent install && pnpm --silent build)
ok "frontend built"

if [ "$DEVPORTAL_START" != "1" ]; then
  cat <<EOF

Install complete. To start the portal:

  cd $DEVPORTAL_SRC/backend  && ./gradlew bootRun
  cd $DEVPORTAL_SRC/frontend && pnpm dev

Then open http://localhost:5173
EOF
  exit 0
fi

start_bg() {
  local name="$1" cwd="$2"; shift 2
  local pidfile="$RUN_DIR/$name.pid"
  local logfile="$LOG_DIR/$name.log"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    warn "$name already running (pid $(cat "$pidfile"))"
    return
  fi
  ( cd "$cwd" && nohup "$@" >"$logfile" 2>&1 & echo $! >"$pidfile" )
  ok "$name pid $(cat "$pidfile") — log: $logfile"
}

step "Launching backend (background)"
start_bg backend "$DEVPORTAL_SRC/backend" ./gradlew bootRun

step "Launching frontend (background)"
start_bg frontend "$DEVPORTAL_SRC/frontend" pnpm dev --host

step "Waiting for backend on :8081"
for i in $(seq 1 60); do
  if curl -fsS http://localhost:8081/api/health >/dev/null 2>&1; then ok "backend healthy"; break; fi
  sleep 2
  [ "$i" = "60" ] && warn "backend not healthy after 120s — check $LOG_DIR/backend.log"
done

step "Waiting for frontend on :5173"
for i in $(seq 1 30); do
  if curl -fsS http://localhost:5173 >/dev/null 2>&1; then ok "frontend up"; break; fi
  sleep 2
  [ "$i" = "30" ] && warn "frontend not up after 60s — check $LOG_DIR/frontend.log"
done

cat <<EOF

${c_bold}DevHub Pro is up.${c_reset}

  UI       http://localhost:5173
  API      http://localhost:8081
  Swagger  http://localhost:8081/swagger-ui/index.html

  source   $DEVPORTAL_SRC
  logs     $LOG_DIR/{backend,frontend}.log
  pids     $RUN_DIR/{backend,frontend}.pid

Stop with: kill \$(cat $RUN_DIR/backend.pid) \$(cat $RUN_DIR/frontend.pid)
EOF

if [ "$DEVPORTAL_OPEN" = "1" ]; then
  if command -v open >/dev/null 2>&1; then open http://localhost:5173 >/dev/null 2>&1 || true
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open http://localhost:5173 >/dev/null 2>&1 || true
  fi
fi
