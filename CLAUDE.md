# dev_portal — guidance for Claude

This repo is a monorepo for the developer portal. The assets it *manages* live in their own GitHub repos (initially under https://github.com/geekychris) — do not look for them inside this tree.

## Source of truth

Postgres is the database of record. The portal exports/imports YAML to a separate `devportal-state` git repo for backup and portability — that repo is not the source of truth.

## Stack

- Backend: Java 21, Spring Boot 3.x, Gradle (Kotlin DSL), Postgres 16, Flyway.
- Frontend: Node 20+, Vite, React 18, TypeScript, pnpm, Tailwind, TanStack Query, React Flow.
- MCP server: Node + TypeScript, stdio transport, calls backend REST.

## Commands

- Backend build: `cd backend && ./gradlew build`
- Backend run: `cd backend && ./gradlew bootRun`
- Frontend dev: `cd frontend && pnpm dev`
- Frontend build: `cd frontend && pnpm build`
- Bring up infra: `docker compose up -d`

## Conventions

- The schema for `devportal.yaml` (the manifest each managed asset carries) lives at `schema/devportal-asset.schema.json`. Update it carefully — it is consumed by the parser, the UI, and the onboarding skill.
- Port allocations are managed by the portal, not hard-coded. Never embed a port number in code; pull from the port registry.
- Single-user local mode is the current target. Don't add auth/RBAC scaffolding yet, but keep boundaries clean so it can be added.
