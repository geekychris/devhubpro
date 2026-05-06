---
name: containerize-service
description: Add Dockerfile + Kubernetes manifests to a project, then verify they actually build, run, and respond. Detects the runtime (Java, Node/TS, Rust, Go, Python static frontend), produces a multi-stage Dockerfile, writes Deployment + Service + optional Ingress, then runs docker build + docker run + an HTTP probe and (if a cluster is reachable) a `kubectl apply --dry-run=server`. Use when the user says "containerize X", "add docker to X", "make this deployable", or when invoked by the init-repo skill after the first commit.
---

# containerize-service

You are adding container + Kubernetes artifacts to an existing project and proving they work before declaring victory. This skill is invoked either directly by the user or by `init-repo` immediately after the first commit.

## Inputs

- **Required**: a working directory (cwd by default).
- **Required**: an asset/repo name — used as image tag and for `metadata.name`.
- **Optional**: whether the project has a UI tier (frontend) in addition to / instead of a backend service. If unspecified, detect and confirm.
- **Optional**: target port(s). If unspecified, infer from code/config and confirm.

## Workflow

### 1. Detect the shape

Walk the tree and pick exactly one of the patterns below. If the project has both a backend AND a frontend in sub-directories (`backend/`, `frontend/`), treat each tier separately and produce two image specs.

| Pattern                           | Detection                                                                                          | Build flavor                                       |
|-----------------------------------|----------------------------------------------------------------------------------------------------|----------------------------------------------------|
| **Java + Spring Boot (Maven)**    | `pom.xml` + spring-boot-starter-web in dependencies                                                | multi-stage: `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre` |
| **Java + Spring Boot (Gradle)**   | `build.gradle*` + Spring Boot plugin                                                               | multi-stage: `gradle:8-jdk21` → `eclipse-temurin:21-jre` |
| **Node + TypeScript backend**     | `package.json` + `"start"` or `"dev"` script that runs node/tsx, no Vite/Next config              | multi-stage: `node:20-alpine` → `node:20-alpine` (slim runtime) |
| **Node + Vite/Next/Astro UI**     | `vite.config.*`, `next.config.*`, `astro.config.*`                                                | multi-stage: `node:20-alpine` build → `nginx:alpine` for static, OR keep node runtime for SSR |
| **Rust binary**                   | `Cargo.toml` with `[[bin]]` (or default `main.rs`)                                                 | multi-stage: `rust:1` build → `debian:bookworm-slim` runtime |
| **Go binary**                     | `go.mod`                                                                                           | multi-stage: `golang:1.23` → `gcr.io/distroless/static-debian12` |
| **Python web service**            | `pyproject.toml`/`requirements.txt` + uvicorn / gunicorn / fastapi / flask / django imports       | multi-stage: `python:3.12-slim` build → `python:3.12-slim` runtime |
| **Static site only**              | HTML/JS/CSS no build step or pure Vite/Next static-export                                          | single-stage: `nginx:alpine` copying the built `dist/` |

Also detect:
- **Existing `Dockerfile`** — read it before writing. Don't clobber. If the user wants a refresh, *replace* only after confirmation.
- **Existing `k8s/` / `deploy/` / `manifests/`** — same rule: don't overwrite, surface what's there.
- **Port** — extract from Spring `server.port`, Vite/Next defaults (5173, 3000), `app.listen(N)`, `actix HttpServer.bind(...)`, etc. If multiple, ask which is the "primary".
- **Health endpoint** — `/actuator/health` for Spring, `/health` for many web frameworks, `/api/health` if there's an explicit `HealthController`, `/` if nothing else.

### 2. Confirm with the user before writing

Show the user a summary of what you intend to write:

```
Project shape:    Spring Boot (Maven), single tier
Image tag:        {{repoName}}:latest
Port:             8080
Health:           /actuator/health
Files to add:
  Dockerfile
  .dockerignore
  k8s/deployment.yaml
  k8s/service.yaml
  k8s/ingress.yaml      (optional — say no to skip)
```

Wait for `yes` / `no` / "tweak X" before writing files. If the user says "tweak the port to 9000", apply the change to your plan and re-confirm.

### 3. Write Dockerfile

Use multi-stage where appropriate to keep the runtime image small. Copy patterns from these templates — they're battle-tested.

#### Spring Boot (Maven)

```dockerfile
# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY .mvn .mvn 2>/dev/null
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package -DfinalName=app

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

#### Spring Boot (Gradle Kotlin DSL)

```dockerfile
# syntax=docker/dockerfile:1.7
FROM gradle:8-jdk21 AS build
WORKDIR /src
COPY --chown=gradle:gradle . .
RUN --mount=type=cache,target=/home/gradle/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

#### Node + Vite (UI)

```dockerfile
# syntax=docker/dockerfile:1.7
FROM node:20-alpine AS build
WORKDIR /src
COPY package.json pnpm-lock.yaml* package-lock.json* yarn.lock* ./
RUN corepack enable && (test -f pnpm-lock.yaml && pnpm install --frozen-lockfile || npm ci)
COPY . .
RUN (test -f pnpm-lock.yaml && pnpm build || npm run build)

FROM nginx:alpine AS runtime
COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

Also write a sensible `nginx.conf` for SPA routing:

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

#### Rust binary

```dockerfile
# syntax=docker/dockerfile:1.7
FROM rust:1 AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo "fn main(){}" > src/main.rs && cargo build --release && rm -rf src
COPY src src
RUN cargo build --release

FROM debian:bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/release/{{binname}} /app/{{binname}}
EXPOSE 8080
ENTRYPOINT ["/app/{{binname}}"]
```

#### Go

```dockerfile
# syntax=docker/dockerfile:1.7
FROM golang:1.23 AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o /out/app ./...

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/app /app
EXPOSE 8080
ENTRYPOINT ["/app"]
```

#### Python (FastAPI / uvicorn)

```dockerfile
# syntax=docker/dockerfile:1.7
FROM python:3.12-slim AS build
WORKDIR /src
RUN pip install --no-cache-dir uv
COPY pyproject.toml uv.lock* requirements.txt* ./
RUN uv sync --frozen 2>/dev/null || pip install --no-cache-dir -r requirements.txt

FROM python:3.12-slim AS runtime
WORKDIR /app
COPY --from=build /src /app
COPY . /app
EXPOSE 8000
ENTRYPOINT ["uvicorn","app.main:app","--host","0.0.0.0","--port","8000"]
```

(Adjust `app.main:app` to the actual ASGI entry point.)

Always include a sibling `.dockerignore` that excludes the same artifacts your `.gitignore` does, plus `.git/` itself:

```
.git
.gitignore
.idea
.vscode
node_modules
target
build
.gradle
__pycache__
*.log
.env
.env.*
```

### 4. Write k8s manifests

Default to `k8s/deployment.yaml` + `k8s/service.yaml`. Add `k8s/ingress.yaml` only if the user wants HTTP routing exposed.

#### `k8s/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{repoName}}
  labels:
    app: {{repoName}}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: {{repoName}}
  template:
    metadata:
      labels:
        app: {{repoName}}
    spec:
      containers:
        - name: {{repoName}}
          image: {{repoName}}:latest
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: {{port}}
              name: http
          env: []
          readinessProbe:
            httpGet:
              path: {{healthPath}}
              port: http
            initialDelaySeconds: 5
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: {{healthPath}}
              port: http
            initialDelaySeconds: 30
            periodSeconds: 15
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 1000m
              memory: 512Mi
```

#### `k8s/service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{repoName}}
  labels:
    app: {{repoName}}
spec:
  type: ClusterIP                 # change to NodePort if the user wants host-accessible
  selector:
    app: {{repoName}}
  ports:
    - name: http
      port: 80
      targetPort: http
```

If the user opted for `NodePort`, the dev_portal port-registry usually allocates the concrete port — leave `nodePort` blank and let the portal patch it later. If the user is not on dev_portal, ask for a port in the 30000-32767 range or default to omitted (cluster auto-assigns).

#### `k8s/ingress.yaml` (optional)

Only write if the user explicitly wants Ingress. Default to nginx-class:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{repoName}}
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  ingressClassName: nginx
  rules:
    - host: {{repoName}}.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: {{repoName}}
                port:
                  number: 80
```

### 5. Verify the artifacts actually work

This is the part that distinguishes containerize-service from "drop boilerplate and hope". Don't skip these steps.

1. **`docker build`** — `docker build -t {{repoName}}:latest .` from the project root. If it fails, surface the error, don't write the manifests yet (or remove them and report).
2. **`docker run`** — `docker run --rm -d --name {{repoName}}-test -p {{port}}:{{port}} {{repoName}}:latest`. Capture the container id.
3. **HTTP probe** — wait up to 30s for the health endpoint:
   ```sh
   for i in $(seq 1 30); do
     if curl -fsS http://localhost:{{port}}{{healthPath}} >/dev/null 2>&1; then echo OK; break; fi
     sleep 1
   done
   ```
   Report `pass` or `fail` and what HTTP code came back.
4. **`docker logs`** — capture the last 30 lines from the test container; useful when the probe fails.
5. **Cleanup** — `docker stop {{repoName}}-test`. Always run this even on probe failure (use `trap` or equivalent so failure on the probe doesn't leave a stranded container).
6. **`kubectl apply --dry-run=server` (optional)** — if `kubectl` is on PATH and `kubectl version --client` works, run `kubectl apply --dry-run=server -f k8s/` to validate the manifests against the cluster's API. If no cluster is reachable, fall back to `--dry-run=client` to at least catch YAML / schema errors.

### 6. Report back

Tell the user, in this exact shape:

```
{{repoName}}
  files:
    + Dockerfile
    + .dockerignore
    + k8s/deployment.yaml
    + k8s/service.yaml
    + k8s/ingress.yaml          (or "skipped")
  build:    pass | fail ({{short reason}})
  run:      pass | fail
  probe:    pass | fail (HTTP {{code}} from {{healthPath}})
  apply:    server-dry-run pass | client-dry-run pass | skipped (no kubectl) | fail ({{reason}})
```

If everything is green, hand control back to whoever invoked the skill (the user, or `init-repo`). They commit. Don't commit yourself — the parent skill or the user owns commits, you own artifacts.

If anything fails, **leave the files in the working tree but uncommitted**, surface the failure with logs, and propose the next step (fix the Dockerfile, update the entry point, change the port, etc.).

## What to avoid

- **Don't overwrite an existing Dockerfile** without explicit confirmation. Read it, propose a diff, get yes.
- **Don't commit.** Leave that to `init-repo` or the user. Containers + manifests are isolated working-tree changes; the parent decides commit cadence.
- **Don't fabricate health endpoints.** If you can't find one in the code, ask. `/health` is fine as a default but flag it explicitly: "I'm assuming `/health` — does this app expose that?"
- **Don't skip the build/run/probe.** A Dockerfile that compiles is half the story; a container that boots is the rest. If verify fails, you've done a useful thing — surfaced a real bug — but don't pretend the artifacts are usable.
- **Don't push images.** Building locally is enough. Pushing to a registry is the user's call (and ties into their auth setup, registry choice, etc.).
- **Don't apply to a real cluster.** `--dry-run=server` is the limit unless the user explicitly says "apply it". This skill produces artifacts and proves they're valid; running production-shaped workloads is a separate decision.

## Failure recovery

- **Build fails because the entry point is wrong** → ask the user where `main` lives. For Spring this is autodetected; for plain Java or Node this often needs explicit pointing.
- **Build fails because dependencies don't resolve** → check the marker files. Spring projects with multi-module poms need a parent stage; Node projects without a lock file need `npm install` not `npm ci`. Adjust the Dockerfile and rebuild.
- **Run starts but probe fails on connection refused** → the app is listening on a different port than declared. Check `application.yml/server.port`, `app.listen()` arg, etc. Update the Dockerfile `EXPOSE` and the manifest `containerPort`.
- **Run starts but probe fails on 404** → the health path is wrong. Try `/`, `/healthz`, `/actuator/health`, `/api/health` and update the manifest.
- **Probe passes locally but `kubectl apply --dry-run=server` fails** → schema mismatch (deprecated apiVersion, missing required field). Surface the validation error and patch the manifest.
- **The project has a backend AND a frontend in sub-dirs** → produce two separate Dockerfiles (`backend/Dockerfile`, `frontend/Dockerfile`) and two Deployments. Add a single Ingress that routes `/api` to the backend service and `/` to the frontend service if the user wants the SPA + API pattern.

## Why this skill exists

Manually writing a Dockerfile + k8s set per project is rote and error-prone. Engineers copy from old projects, miss a flag, the image is 2GB, the probe path is wrong, and the manifest only gets noticed at deploy time. This skill bakes the patterns + a verification gate into one step so the artifacts are correct *and* proven to work before they reach the repo.
