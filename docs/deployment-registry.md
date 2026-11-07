# Deployment registry — what is running where

DevHub Pro's catalog knows the **assets** we build (repos, services, libraries). The
deployment registry adds the other half: **deployments** — one observed instance of an
asset on a **target** (a Kubernetes cluster such as `wopr` or `mp2`, a host such as `m1`,
the FaaS server on `spark`, …), with its version, URLs and health.

Deployments are *reported* — by cloud-stack (SaaS pack parts, Lantern Market, FaaS
functions, cloud-stack itself, execution hosts, SDLC deliveries), by Claude Code through
the MCP tool `report_deployment`, or by hand with `curl`. DevHub Pro never discovers them
itself; it stores what it is told and health-checks what it can reach.

**Targets and URLs are stored exactly as reported.** Hostnames, MagicDNS names and IPs are
all just strings; the registry never resolves or rewrites them. Report stable names
(`wopr.tailnet.ts.net`, `wopr.local`) rather than DHCP addresses where you can.

## Data model

Table `deployment` (migration `V10__deployment_registry.sql`):

| Field | Meaning |
|---|---|
| `id` | numeric id |
| `key` | natural key `assetId\|target\|namespace\|name` (computed, read-only) |
| `assetId` | the asset (FK, cascade delete). Unknown ids are auto-created as minimal assets tagged `registry` |
| `target`, `targetKind` | where: free text (`wopr`, `mp2`, `m1`, `spark`) + `k8s-cluster` \| `host` \| `faas` \| `docker` \| `mac` \| `other` |
| `namespace`, `name` | location within the target, and the instance name (both default `""`) |
| `version`, `commit`, `ref` | what is deployed |
| `phase` | `running` (supposed to be up) or `delivered` (built / PR delivered, not known to run) |
| `urls[]` | `{label, url, primary}` links |
| `healthUrl` | what the poller probes (default: the primary URL of a running deployment, if it is http(s); other links are never probed) |
| `source` | who reported it: `cloud-stack saaspack`, `cloud-stack sdlc`, `claude-code`, `manual` … |
| `labels`, `notes` | free-form string map, text |
| `status` | `healthy` \| `degraded` \| `down` \| `unknown` \| `reported` \| `retired` |
| `reportedStatus` | what the reporter last claimed (if anything) |
| `lastCheckedAt`, `lastError`, `lastLatencyMs` | last health check |
| `firstSeenAt`, `lastReportedAt`, `retiredAt` | lifecycle |

Unique on `(asset_id, target, namespace, name)`; that is what makes reports idempotent.

### Status

- Something to probe (an http(s) `healthUrl`, or an http(s) *primary* URL while `phase=running` — non-primary links such as REST endpoints are never probed): the
  **poller owns the status**. A new (or revived, or re-pointed) deployment starts `unknown`
  and is checked within seconds; afterwards each sweep sets `healthy` (2xx/3xx, also
  401/403 — it answered, it just wants a login), `down` (no answer, timeout, 502/503/504) or
  `degraded` (any other status).
- Nothing to probe (an ssh-only host, a `delivered` build): the status is what the reporter
  said, or `reported`.
- `retired`: no longer reported (by reconcile or by hand). Kept for history, hidden from
  default listings, revived by the next report of the same key.

## REST API

Reads are open (like the rest of DevHub Pro). Writes — every POST/PUT/PATCH/DELETE under
`/api/deployments` — need header `X-DevHub-Token: <token>` **when** the backend has
`DEVHUB_REGISTRY_TOKEN` set; otherwise they are open too.

| Method | Path | |
|---|---|---|
| `POST` | `/api/deployments/report` | batch upsert (+ optional reconcile) — the reporter API |
| `POST` | `/api/deployments` | upsert one deployment (same item shape) |
| `GET` | `/api/deployments` | list; filters `target`, `assetId`, `status`, `source`, `phase`, `q`, `includeRetired` |
| `GET` | `/api/deployments/targets` | per-target counts by status |
| `GET` | `/api/deployments/{id}` | one deployment |
| `GET` | `/api/assets/{id}/deployments` | an asset's deployments (`includeRetired` optional) |
| `POST` | `/api/deployments/{id}/check` | probe now, return the updated row |
| `POST` | `/api/deployments/{id}/retire` | mark retired |
| `DELETE` | `/api/deployments/{id}` | delete the row |

### Report

```http
POST /api/deployments/report
X-DevHub-Token: …            (only when DEVHUB_REGISTRY_TOKEN is set)
Content-Type: application/json

{
  "source": "cloud-stack saaspack",
  "reconcile": true,
  "reconcileTargets": ["wopr"],
  "deployments": [
    {
      "assetId": "saaspack-kafka",
      "asset": { "name": "Kafka", "repoUrl": "https://github.com/apache/kafka", "tags": ["saaspack"] },
      "target": "wopr", "targetKind": "k8s-cluster",
      "namespace": "saaspack", "name": "kafka",
      "version": "3.7.0",
      "urls": [ { "label": "Kafka UI", "url": "http://wopr.local:31080", "primary": true } ],
      "healthUrl": "http://wopr.local:31080/",
      "labels": { "component": "kafka" }
    }
  ]
}
```

→ `200 {"created":1,"updated":0,"retired":0,"assetsCreated":["saaspack-kafka"],"deployments":[…]}`

- `assetId` may be omitted when `asset.name` (or `asset.repoUrl`) is given; the id is then
  slugified from it (`Lantern Market` → `lantern-market`). Ids must match
  `^[a-z][a-z0-9-]{1,62}[a-z0-9]$`. `asset` is used only to create a missing asset; an
  existing (curated) asset is never modified by a report.
- `target` is required. `targetKind` defaults to `other`, `phase` to `running`.
- `status` (optional) is the reporter's own view; it becomes the status only when there is
  nothing to probe.
- **Reconcile**: with `reconcile: true`, every live deployment with the same `source`
  (restricted to `reconcileTargets` when given) that is not in this batch is retired. A
  reporter that always sends its complete view therefore keeps the registry exact — a
  SaaS pack part that was uninstalled disappears on the next report. `reconcile` needs a
  `source`; an empty `deployments` list with `reconcile: true` retires everything of that
  source (on those targets). Reconcile never touches other sources.
- At most 1000 deployments per report; a key repeated inside one batch is a 400.

### curl

```sh
DEVHUB=http://spark.local:30573          # through the UI's /api proxy, or :8081 in-cluster
curl -fsS -X POST "$DEVHUB/api/deployments" \
  -H 'Content-Type: application/json' ${DEVHUB_REGISTRY_TOKEN:+-H "X-DevHub-Token: $DEVHUB_REGISTRY_TOKEN"} \
  -d '{"assetId":"swift-hello-m1","asset":{"repoUrl":"https://github.com/geekychris/swift-hello-m1"},
       "target":"m1","targetKind":"mac","phase":"delivered","ref":"feat/x","notes":"PR #12","source":"manual"}'

curl -fsS "$DEVHUB/api/deployments?target=wopr" | jq '.[] | {assetId, status, urls}'
```

## Health poller

`HealthPoller` runs on its own threads (virtual threads, bounded by `concurrency`), so API
requests never wait for a probe. Each sweep probes every live deployment that has a probe
URL with a short timeout, records `status`, `lastCheckedAt`, `lastError`, `lastLatencyMs`,
and skips a sweep if the previous one is still running. Newly reported deployments are
checked right away in the background.

| Property | Env | Default |
|---|---|---|
| `devportal.registry.token` | `DEVHUB_REGISTRY_TOKEN` | empty (writes open) |
| `devportal.registry.health.enabled` | `DEVHUB_REGISTRY_HEALTH_ENABLED` | `true` |
| `devportal.registry.health.interval-ms` | `DEVHUB_REGISTRY_HEALTH_INTERVAL_MS` | `60000` |
| `devportal.registry.health.timeout-ms` | `DEVHUB_REGISTRY_HEALTH_TIMEOUT_MS` | `3000` |
| `devportal.registry.health.initial-delay-ms` | — | `15000` |
| `devportal.registry.health.concurrency` | — | `8` |

## UI

- **Deployments** (`/deployments`, also the landing page `/`): per-target chips with
  status counts, then one section per target (`wopr`, `mp2`, `m1`, `spark` …) listing
  asset, namespace/instance, source, version, status light, clickable URLs, last seen /
  checked, and *check* / *retire* / *delete*. Filters: target, status, phase, text,
  include retired. Refreshes every 30 s.
- **Asset → Deployments** tab: the same table for one asset.
- When the backend requires a token, set it with *Registry token* on the Deployments page;
  it is kept in that browser's local storage only and sent on check / retire / delete.

## MCP

| Tool | |
|---|---|
| `list_deployments` | filters as the REST list; `targetsOnly: true` returns the per-target roll-up |
| `get_deployment` | one by id |
| `report_deployment` | upsert one deployment (source defaults to `claude-code`); sends `X-DevHub-Token` from the MCP server's `DEVHUB_REGISTRY_TOKEN` env |

## Who reports what (cloud-stack)

cloud-stack's api runs a reporter (see cloud-stack `docs/DEVHUB_REGISTRY.md`) that reports,
each under its own source so reconciles never cross: the SaaS pack parts on their data
hosts, Lantern Market, the FaaS server and its functions, cloud-stack itself, the
execution hosts, and SDLC deliveries (`phase: delivered`). It talks to the in-cluster
Service `http://devportal-backend.devportal.svc.cluster.local:8081`.
