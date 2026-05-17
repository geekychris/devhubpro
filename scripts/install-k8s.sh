#!/usr/bin/env bash
#
# DevHub Pro — one-shot installer for Kubernetes mode.
#
# Builds backend + frontend images locally, generates manifests on the fly,
# and applies them to the current kubectl context. No Dockerfile or k8s/
# directory is required in the repo — everything is produced inline.
#
# Invoke as:
#
#   curl -fsSL https://raw.githubusercontent.com/geekychris/devhubpro/main/scripts/install-k8s.sh | bash
#
# Targets a local cluster (Rancher Desktop, kind, minikube, Docker Desktop k8s).
# Postgres runs in-cluster on a PVC. Backend talks to it via the in-cluster
# service DNS. Frontend is a Vite static build served by nginx, which proxies
# /api/ + /ws/ to the backend Service.
#
# Environment knobs:
#   DEVPORTAL_SRC        local checkout dir (default: $HOME/.devportal/src)
#   DEVPORTAL_REF        git ref to build from (default: main)
#   DEVPORTAL_REPO       git remote (default: https://github.com/geekychris/devhubpro.git)
#   DEVPORTAL_NS         k8s namespace (default: devportal)
#   DEVPORTAL_TAG        image tag (default: dev)
#   DEVPORTAL_NODEPORT   nodePort for the UI (default: 30573)
#   DEVPORTAL_URLS_HOST   host part of URLs the portal shows back to users
#                         (default: localhost; set to spark.local etc. when the
#                         portal is on a server and the browser is on your laptop)
#   DEVPORTAL_LOAD_KIND   if "1", run `kind load docker-image` (kind clusters)
#   DEVPORTAL_AUTO_INSTALL "1" to brew/apt/dnf install missing deps (default: 0)

set -euo pipefail

DEVPORTAL_SRC="${DEVPORTAL_SRC:-$HOME/.devportal/src}"
DEVPORTAL_REF="${DEVPORTAL_REF:-main}"
DEVPORTAL_REPO="${DEVPORTAL_REPO:-https://github.com/geekychris/devhubpro.git}"
DEVPORTAL_NS="${DEVPORTAL_NS:-devportal}"
DEVPORTAL_TAG="${DEVPORTAL_TAG:-dev}"
DEVPORTAL_NODEPORT="${DEVPORTAL_NODEPORT:-30573}"
DEVPORTAL_URLS_HOST="${DEVPORTAL_URLS_HOST:-localhost}"
DEVPORTAL_LOAD_KIND="${DEVPORTAL_LOAD_KIND:-0}"
DEVPORTAL_AUTO_INSTALL="${DEVPORTAL_AUTO_INSTALL:-0}"

BACKEND_IMAGE="devportal-backend:${DEVPORTAL_TAG}"
FRONTEND_IMAGE="devportal-frontend:${DEVPORTAL_TAG}"

c_reset=$'\033[0m'; c_bold=$'\033[1m'
c_green=$'\033[32m'; c_yellow=$'\033[33m'; c_red=$'\033[31m'; c_blue=$'\033[34m'

step() { printf "%s==>%s %s%s%s\n" "$c_blue" "$c_reset" "$c_bold" "$*" "$c_reset"; }
ok()   { printf "%s ✓%s %s\n" "$c_green" "$c_reset" "$*"; }
warn() { printf "%s ! %s%s\n" "$c_yellow" "$c_reset" "$*"; }
die()  { printf "%s ✗ %s%s\n" "$c_red" "$c_reset" "$*" >&2; exit 1; }

# Non-interactive shells (curl | bash) inherit a minimal PATH and miss
# directories that login shells get from /etc/profile.d (snap, user-local) or
# from app-installer shims (Rancher Desktop, Docker Desktop on macOS, Homebrew
# on Apple Silicon). Surface them so wrappers like microk8s.kubectl in /snap/bin
# or ~/.rd/bin/{docker,kubectl} become visible.
for _d in \
    /snap/bin /var/lib/snapd/snap/bin \
    /usr/local/bin /opt/homebrew/bin \
    "$HOME/.rd/bin" \
    /Applications/Rancher\ Desktop.app/Contents/Resources/resources/darwin/bin \
    /Applications/Docker.app/Contents/Resources/bin \
    "$HOME/.local/bin"; do
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
    mac:kubectl)    echo "brew install kubectl" ;;
    debian:git)     echo "sudo apt-get install -y git" ;;
    debian:java)    echo "sudo apt-get install -y openjdk-21-jdk" ;;
    debian:node)    echo "curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - && sudo apt-get install -y nodejs" ;;
    debian:pnpm)    echo "corepack enable" ;;
    debian:docker)  echo "see https://docs.docker.com/engine/install/ubuntu/" ;;
    debian:kubectl) echo "re-run with DEVPORTAL_AUTO_INSTALL=1, or: curl -LO https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo install -m 755 kubectl /usr/local/bin/" ;;
    linux:kubectl)  echo "re-run with DEVPORTAL_AUTO_INSTALL=1, or see https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/" ;;
    fedora:git)     echo "sudo dnf install -y git" ;;
    fedora:java)    echo "sudo dnf install -y java-21-openjdk-devel" ;;
    fedora:node)    echo "sudo dnf install -y nodejs npm" ;;
    fedora:pnpm)    echo "corepack enable" ;;
    fedora:docker)  echo "see https://docs.docker.com/engine/install/fedora/" ;;
    fedora:kubectl) echo "sudo dnf install -y kubectl" ;;
    arch:git)       echo "sudo pacman -S --noconfirm git" ;;
    arch:java)      echo "sudo pacman -S --noconfirm jdk21-openjdk" ;;
    arch:node)      echo "sudo pacman -S --noconfirm nodejs npm" ;;
    arch:pnpm)      echo "corepack enable" ;;
    arch:docker)    echo "sudo pacman -S --noconfirm docker && sudo systemctl enable --now docker" ;;
    arch:kubectl)   echo "sudo pacman -S --noconfirm kubectl" ;;
    *)              echo "install $1 (no hint for $OS — see your package manager)" ;;
  esac
}

# Install kubectl from the official upstream binary. Works on every Linux
# distro and is arch-aware. Falls back to /usr/local/bin so it lands on PATH.
install_kubectl_binary() {
  local arch
  case "$(uname -m)" in
    x86_64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) die "unsupported arch $(uname -m) for kubectl binary install" ;;
  esac
  local ver tmp
  ver=$(curl -fsSL https://dl.k8s.io/release/stable.txt) || die "could not fetch kubectl stable version"
  tmp=$(mktemp)
  curl -fsSL -o "$tmp" "https://dl.k8s.io/release/${ver}/bin/linux/${arch}/kubectl" || die "kubectl download failed"
  sudo install -m 755 "$tmp" /usr/local/bin/kubectl </dev/tty
  rm -f "$tmp"
}

auto_install() {
  local pkg="$1"
  step "Auto-installing $pkg (DEVPORTAL_AUTO_INSTALL=1)"
  case "$OS:$pkg" in
    mac:git)        brew install git ;;
    mac:java)       brew install openjdk@21 && export PATH="$(brew --prefix openjdk@21)/bin:$PATH" ;;
    mac:node)       brew install node ;;
    mac:pnpm)       corepack enable ;;
    mac:kubectl)    brew install kubectl ;;
    mac:docker)     die "Docker requires GUI install on macOS — install Docker Desktop or Rancher Desktop, then re-run" ;;
    debian:git)     sudo -p "[sudo] " apt-get update -qq </dev/tty && sudo apt-get install -y git </dev/tty ;;
    debian:java)    sudo -p "[sudo] " apt-get update -qq </dev/tty && sudo apt-get install -y openjdk-21-jdk </dev/tty ;;
    debian:node)    curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - </dev/tty && sudo apt-get install -y nodejs </dev/tty ;;
    debian:pnpm)    corepack enable ;;
    debian:docker)  die "auto-install of docker isn't safe to scriptize — $(install_hint docker)" ;;
    debian:kubectl) install_kubectl_binary ;;
    linux:kubectl)  install_kubectl_binary ;;
    fedora:git)     sudo dnf install -y git </dev/tty ;;
    fedora:java)    sudo dnf install -y java-21-openjdk-devel </dev/tty ;;
    fedora:node)    sudo dnf install -y nodejs npm </dev/tty ;;
    fedora:pnpm)    corepack enable ;;
    fedora:kubectl) sudo dnf install -y kubectl </dev/tty ;;
    arch:git)       sudo pacman -S --noconfirm git </dev/tty ;;
    arch:java)      sudo pacman -S --noconfirm jdk21-openjdk </dev/tty ;;
    arch:node)      sudo pacman -S --noconfirm nodejs npm </dev/tty ;;
    arch:pnpm)      corepack enable ;;
    arch:kubectl)   sudo pacman -S --noconfirm kubectl </dev/tty ;;
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
docker info >/dev/null 2>&1 || die "docker daemon is not reachable"
# Java + mvn + node + pnpm are NOT host requirements for the k8s installer —
# the backend builds inside eclipse-temurin:21-jdk-jammy and the frontend
# inside node:22-alpine. Host only needs git + docker + kubectl + a cluster.
# kubectl may be a shell alias in the user's interactive shell (microk8s.kubectl,
# minikube kubectl, k3s kubectl) — aliases don't carry into `curl | bash`.
# Detect the underlying wrapper, define a function so the rest of the script
# can call `kubectl` normally, and remember the flavor so we can do flavor-
# specific image loading later (microk8s/k3s have their own containerd, not
# the docker daemon).
KUBE_FLAVOR=stock
if ! command -v kubectl >/dev/null 2>&1; then
  if command -v microk8s.kubectl >/dev/null 2>&1; then
    kubectl() { command microk8s.kubectl "$@"; }; export -f kubectl
    KUBE_FLAVOR=microk8s
    ok "using microk8s.kubectl as kubectl"
  elif command -v k3s >/dev/null 2>&1 && k3s kubectl version --client >/dev/null 2>&1; then
    kubectl() { command k3s kubectl "$@"; }; export -f kubectl
    KUBE_FLAVOR=k3s
    ok "using 'k3s kubectl' as kubectl"
  elif command -v minikube >/dev/null 2>&1 && minikube kubectl -- version --client >/dev/null 2>&1; then
    kubectl() { command minikube kubectl -- "$@"; }; export -f kubectl
    KUBE_FLAVOR=minikube
    ok "using 'minikube kubectl' as kubectl"
  fi
fi
require kubectl kubectl
kubectl cluster-info >/dev/null 2>&1 || die "kubectl cannot reach a cluster — start one (Rancher Desktop / kind / minikube / microk8s)"
ok "prereqs ok — context: $(kubectl config current-context 2>/dev/null || echo "($KUBE_FLAVOR)")"

step "Fetching source -> $DEVPORTAL_SRC ($DEVPORTAL_REF)"
if [ -d "$DEVPORTAL_SRC/.git" ]; then
  git -C "$DEVPORTAL_SRC" fetch --quiet origin "$DEVPORTAL_REF"
  git -C "$DEVPORTAL_SRC" checkout --quiet "$DEVPORTAL_REF"
  git -C "$DEVPORTAL_SRC" pull --quiet --ff-only origin "$DEVPORTAL_REF" || warn "could not fast-forward; continuing"
else
  mkdir -p "$(dirname "$DEVPORTAL_SRC")"
  git clone --quiet --branch "$DEVPORTAL_REF" "$DEVPORTAL_REPO" "$DEVPORTAL_SRC"
fi
ok "source ready"

step "Building backend image ($BACKEND_IMAGE)"
# Direct mode (install.sh) runs the JVM on the host and inherits the user's
# shell — mvn/git/kubectl/docker just work. K8s mode must bake those tools
# into the container or the JVM has nothing to exec when builds, scaffolds,
# or apply-to-k8s code shells out. Build via custom Dockerfile (instead of
# Paketo bootBuildImage which gives a JRE-only artifact) so the runtime has
# the same toolbox as a Mac dev box.
BE_BUILD=$(mktemp -d)
trap 'rm -rf "$BE_BUILD"' EXIT
cat >"$BE_BUILD/Dockerfile" <<'DOCKERFILE'
# --- 1. build the boot jar from source ---
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
# backend/build.gradle.kts reads `../schema/devportal-asset.schema.json` and
# bundles it as a generated resource — copy both trees so the relative path
# resolves identically to a direct ./gradlew bootJar from the monorepo root.
COPY backend/ ./backend/
COPY schema/ ./schema/
WORKDIR /src/backend
RUN ./gradlew --no-daemon --quiet -x test bootJar && ls -la build/libs/

# --- 2. runtime: JDK (not JRE) + the toolbox the portal shells out to ---
# JDK rather than JRE so the in-pod mvn can find javac when building managed
# assets from source. ~170MB larger but required for compile-from-source.
FROM eclipse-temurin:21-jdk-jammy
ENV DEBIAN_FRONTEND=noninteractive
# python3 included so asset hook scripts that shell out to python (very common
# pattern for "parse JSON inline" helpers) work out of the box. Without it,
# `python3 ... 2>/dev/null || echo ""` patterns silently produce empty strings
# and downstream verification logic falls into surprising fallback paths.
RUN apt-get update && apt-get install -y --no-install-recommends \
      bash git curl ca-certificates maven jq python3 \
    && rm -rf /var/lib/apt/lists/*

# kubectl + docker CLI, arch-aware (amd64 / arm64).
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
      amd64) k_arch=amd64; d_arch=x86_64 ;; \
      arm64) k_arch=arm64; d_arch=aarch64 ;; \
      *) echo "unsupported arch $arch" >&2; exit 1 ;; \
    esac; \
    kver="$(curl -fsSL https://dl.k8s.io/release/stable.txt)"; \
    curl -fsSL "https://dl.k8s.io/release/${kver}/bin/linux/${k_arch}/kubectl" -o /usr/local/bin/kubectl; \
    chmod +x /usr/local/bin/kubectl; \
    curl -fsSL "https://download.docker.com/linux/static/stable/${d_arch}/docker-26.1.4.tgz" -o /tmp/docker.tgz; \
    tar -xzf /tmp/docker.tgz -C /tmp; \
    mv /tmp/docker/docker /usr/local/bin/docker; \
    rm -rf /tmp/docker /tmp/docker.tgz

# Put HOME on the PVC so SecretService's $HOME/.devportal/secrets survives
# pod restarts. Without this, the GitHub PAT (and SSH keys, telegram tokens)
# would live in the pod's writable layer and disappear on every rollout.
#
# Java's System.getProperty("user.home") reads from /etc/passwd (=/root for
# the root user), NOT from $HOME — so the JVM arg below is required for
# SecretService (which uses user.home) to honour the PVC mount.
ENV HOME=/var/devportal/home

COPY --from=build /src/backend/build/libs/*.jar /app/devportal.jar
WORKDIR /app
EXPOSE 8081
ENTRYPOINT ["java","-Duser.home=/var/devportal/home","-jar","/app/devportal.jar"]
DOCKERFILE

# Patterns ANCHORED to the gradle output dirs — `**/build` and `--exclude=build`
# would also match the io.devportal.build source package, breaking compile.
# Same trap as gitignore had before /build/ was anchored.
cat >"$BE_BUILD/.dockerignore" <<'IGNORE'
backend/.git
backend/.gradle
backend/build
backend/.idea
**/node_modules
IGNORE

# Copy the backend tree + the /schema tree it references via `../schema` in
# build.gradle.kts. Skip gradle's output dirs (anchored paths to avoid hitting
# source dirs named "build") to keep the context small.
(cd "$DEVPORTAL_SRC" && tar \
    --exclude='backend/.gradle' \
    --exclude='backend/build' \
    --exclude='backend/.git' \
    --exclude='backend/.idea' \
    -cf - backend schema) | tar -xf - -C "$BE_BUILD"
docker build --progress=plain -t "$BACKEND_IMAGE" "$BE_BUILD"
ok "backend image built (eclipse-temurin + mvn + git + kubectl + docker CLI)"

step "Building frontend image ($FRONTEND_IMAGE)"
FE_BUILD=$(mktemp -d)
trap 'rm -rf "$BE_BUILD" "$FE_BUILD"' EXIT
cp -R "$DEVPORTAL_SRC/frontend/." "$FE_BUILD/"
cat >"$FE_BUILD/Dockerfile" <<'DOCKERFILE'
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable
# Pin pnpm@9 — pnpm 11 (corepack default) defaults strict-dep-builds=true,
# which refuses to run esbuild's postinstall (downloads the native binary),
# breaking vite build. pnpm 9 doesn't have that friction.
RUN corepack prepare pnpm@9.15.9 --activate
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --no-frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
DOCKERFILE
cat >"$FE_BUILD/nginx.conf" <<'NGINX'
server {
  listen 80;
  root /usr/share/nginx/html;
  index index.html;

  location /api/ {
    proxy_pass http://devportal-backend:8081;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
  }
  location /ws/ {
    proxy_pass http://devportal-backend:8081;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
  }
  location / {
    try_files $uri $uri/ /index.html;
  }
}
NGINX
# --progress=plain so failures show the actual build log (pnpm errors etc.)
# instead of BuildKit's terse exit-code summary.
docker build --progress=plain -t "$FRONTEND_IMAGE" "$FE_BUILD"
ok "frontend image built"

if [ "$DEVPORTAL_LOAD_KIND" = "1" ]; then
  step "Loading images into kind"
  kind load docker-image "$BACKEND_IMAGE" "$FRONTEND_IMAGE"
fi

# microk8s and k3s ship their own containerd — images built against the
# docker daemon aren't visible to them. Pipe through ctr image import so the
# Deployment doesn't ErrImagePull.
#
# `docker save | sudo ctr import -` claims sudo's stdin, leaving no fd for
# the password prompt. Pre-authenticate with `sudo -v </dev/tty` so the
# cached creds (default 5 min) carry through subsequent piped sudo calls.
load_into_containerd() {
  local importer="$1"
  if [ "$KUBE_FLAVOR" = "microk8s" ] || [ "$KUBE_FLAVOR" = "k3s" ]; then
    if ! sudo -n true 2>/dev/null; then
      step "$KUBE_FLAVOR containerd import needs sudo — authenticating"
      sudo -v -p "[sudo] password for $USER (containerd import): " </dev/tty \
        || die "sudo authentication failed — re-run after: sudo -v"
    fi
  fi
  for img in "$BACKEND_IMAGE" "$FRONTEND_IMAGE"; do
    step "Importing $img into $KUBE_FLAVOR containerd"
    docker save "$img" | $importer
  done
}
case "$KUBE_FLAVOR" in
  microk8s) load_into_containerd "sudo microk8s ctr image import -" ;;
  k3s)      load_into_containerd "sudo k3s ctr images import -" ;;
esac

# Ensure a default StorageClass exists — without one PVCs stay Pending.
# microk8s ships hostpath-storage disabled; k3s ships local-path enabled.
if ! kubectl get storageclass 2>/dev/null | grep -q '(default)'; then
  if [ "$KUBE_FLAVOR" = "microk8s" ]; then
    step "Enabling microk8s hostpath-storage addon (no default StorageClass)"
    sudo microk8s enable hostpath-storage >/dev/null 2>&1 \
      || warn "could not enable hostpath-storage automatically — run: sudo microk8s enable hostpath-storage"
    # Wait briefly for the StorageClass to register
    for i in $(seq 1 20); do
      kubectl get storageclass 2>/dev/null | grep -q '(default)' && break
      sleep 1
    done
  else
    warn "no default StorageClass found — PVCs will stay Pending until one exists"
  fi
fi

step "Applying manifests to namespace '$DEVPORTAL_NS'"
kubectl create namespace "$DEVPORTAL_NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

kubectl -n "$DEVPORTAL_NS" apply -f - <<YAML >/dev/null
apiVersion: v1
kind: Secret
metadata:
  name: devportal-pg
type: Opaque
stringData:
  POSTGRES_DB: devportal
  POSTGRES_USER: devportal
  POSTGRES_PASSWORD: devportal
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: devportal-pg-data
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 5Gi
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: devportal-pg
spec:
  serviceName: devportal-pg
  replicas: 1
  selector:
    matchLabels: { app: devportal-pg }
  template:
    metadata:
      labels: { app: devportal-pg }
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          envFrom:
            - secretRef: { name: devportal-pg }
          ports:
            - containerPort: 5432
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "devportal"]
            initialDelaySeconds: 5
            periodSeconds: 5
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: devportal-pg-data
---
apiVersion: v1
kind: Service
metadata:
  name: devportal-pg
spec:
  selector: { app: devportal-pg }
  ports:
    - port: 5432
      targetPort: 5432
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: devportal-state
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 5Gi
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: devportal-backend
---
# Single-user local mode: the backend pod gets cluster-admin so its in-pod
# kubectl can do anything the user could from outside (apply, exec into pods,
# manage namespaces). Do NOT ship this RBAC as-is on a multi-tenant cluster;
# scope it down to the namespaces the portal actually manages.
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: devportal-backend
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
  - kind: ServiceAccount
    name: devportal-backend
    namespace: ${DEVPORTAL_NS}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: devportal-backend
spec:
  replicas: 1
  selector:
    matchLabels: { app: devportal-backend }
  template:
    metadata:
      labels: { app: devportal-backend }
    spec:
      serviceAccountName: devportal-backend
      containers:
        - name: backend
          image: ${BACKEND_IMAGE}
          imagePullPolicy: IfNotPresent
          env:
            - name: SPRING_DATASOURCE_URL
              value: jdbc:postgresql://devportal-pg:5432/devportal
            - name: SPRING_DATASOURCE_USERNAME
              value: devportal
            - name: SPRING_DATASOURCE_PASSWORD
              value: devportal
            - name: DEVPORTAL_WORKSPACE_DIR
              value: /var/devportal/workspace
            - name: DEVPORTAL_URLS_HOST
              value: ${DEVPORTAL_URLS_HOST}
            # HOME is set on the image to /var/devportal/home so secrets
            # (github-token, ssh keys) live on the PVC and survive rollouts.
          ports:
            - containerPort: 8081
          readinessProbe:
            httpGet: { path: /api/health, port: 8081 }
            initialDelaySeconds: 30
            periodSeconds: 5
          volumeMounts:
            - name: state
              mountPath: /var/devportal
            - name: docker-sock
              mountPath: /var/run/docker.sock
      volumes:
        - name: state
          persistentVolumeClaim:
            claimName: devportal-state
        # Host docker daemon access for `docker build` / `docker run` of
        # managed assets. Single-user local: the pod can do anything the
        # docker daemon can, which is effectively root on the host.
        - name: docker-sock
          hostPath:
            path: /var/run/docker.sock
            type: Socket
---
apiVersion: v1
kind: Service
metadata:
  name: devportal-backend
spec:
  selector: { app: devportal-backend }
  ports:
    - port: 8081
      targetPort: 8081
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: devportal-frontend
spec:
  replicas: 1
  selector:
    matchLabels: { app: devportal-frontend }
  template:
    metadata:
      labels: { app: devportal-frontend }
    spec:
      containers:
        - name: frontend
          image: ${FRONTEND_IMAGE}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 80
          readinessProbe:
            httpGet: { path: /, port: 80 }
            initialDelaySeconds: 3
            periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: devportal-frontend
spec:
  type: NodePort
  selector: { app: devportal-frontend }
  ports:
    - port: 80
      targetPort: 80
      nodePort: ${DEVPORTAL_NODEPORT}
YAML
ok "manifests applied"

step "Waiting for rollouts"
kubectl -n "$DEVPORTAL_NS" rollout status statefulset/devportal-pg --timeout=180s
kubectl -n "$DEVPORTAL_NS" rollout status deployment/devportal-backend --timeout=300s
kubectl -n "$DEVPORTAL_NS" rollout status deployment/devportal-frontend --timeout=120s
ok "all rollouts complete"

cat <<EOF

${c_bold}DevHub Pro is running in Kubernetes.${c_reset}

  namespace        $DEVPORTAL_NS
  UI (NodePort)    http://localhost:${DEVPORTAL_NODEPORT}
  backend image    $BACKEND_IMAGE
  frontend image   $FRONTEND_IMAGE

Logs:
  kubectl -n $DEVPORTAL_NS logs deploy/devportal-backend -f
  kubectl -n $DEVPORTAL_NS logs deploy/devportal-frontend -f

Tear down:
  kubectl delete namespace $DEVPORTAL_NS
EOF
