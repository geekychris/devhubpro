# cloud-stack link

[cloud-stack](https://github.com/geekychris/cloud-stack) is the agent / SDLC platform: agents take a
ticket through triage, plan, implement, verify, review and deliver, and open pull requests.
DevHub Pro shows what cloud-stack is doing on each asset's GitHub repo and links to it.

On an asset page:

- **cloud-stack** tab — a **New task** button (opens cloud-stack's New task page for this repo in a new
  tab), open pull requests with what each needs next and a checks light (passing / failing / pending /
  none), the latest Jenkins result, and active and recent agent runs, all linking to cloud-stack, GitHub
  or Jenkins. Refreshes every 30 s.
- **New task in cloud-stack** button in the header, next to *Ask Claude*.

When the activity cannot be shown, the tab says why: cloud-stack is not configured, the asset's repo is
not on GitHub, cloud-stack is unreachable (connection error, timeout, 404 — e.g. a cloud-stack too old to
have the endpoint — or 5xx), or it refused the token. The New task button still works whenever the
repo is on GitHub and `CLOUDSTACK_UI_URL` is set.

## How it works

The backend calls cloud-stack server-side; the browser never sees the token.

```
browser ──GET /api/assets/{id}/cloudstack──▶ DevHub backend ──GET /v1/repos/activity?repo=owner/name──▶ cloud-stack API
                                                          (Authorization: Bearer $CLOUDSTACK_TOKEN)
```

- The repo is `owner/name` from the asset's repo URL (`https://github.com/o/r(.git)`, `git@github.com:o/r.git`).
- Timeout 3 s (connect and request). Each repo's answer is cached for 30 s; a failure for at most 10 s,
  so a down cloud-stack does not slow every page.
- Unknown fields in cloud-stack's answer are ignored, missing ones default to empty; only `http(s)`
  links are passed on.
- `newTaskUrl` is cloud-stack's when it sends one, else `<CLOUDSTACK_UI_URL>/#/new?repo=<url-encoded https://github.com/owner/name>`.

### Endpoints

| | |
|---|---|
| `GET /api/assets/{id}/cloudstack` | `{assetId, available, reasonCode, reason, repo, repoUrl, openPRs[], jenkins, runs[], newTaskUrl, uiUrl, fetchedAt}` — 404 for an unknown asset |
| `GET /api/cloudstack/status` | `{configured, tokenConfigured, apiUrl, uiUrl, reachable, error, latencyMs, checkedAt}` — probes cloud-stack's `/healthz` |

`reasonCode` is one of `not-configured`, `no-github-repo`, `unreachable`, `unauthorized`, `bad-response`.

```json
{"assetId":"devhubpro","available":true,"reasonCode":null,"reason":null,
 "repo":"geekychris/devhubpro","repoUrl":"https://github.com/geekychris/devhubpro",
 "openPRs":[{"url":"https://github.com/geekychris/devhubpro/pull/3","number":3,"title":"…",
             "needs":"review","why":"…","checks":"passing","runId":"…"}],
 "jenkins":{"status":"succeeded","jobName":"…","buildNumber":17,"sha":"…","link":"…","at":"…"},
 "runs":[{"id":"…","state":"running","workflow":"…","title":"…","url":"…","createdAt":"…"}],
 "newTaskUrl":"http://spark.tail8791ce.ts.net:30880/#/new?repo=https%3A%2F%2Fgithub.com%2Fgeekychris%2Fdevhubpro",
 "uiUrl":"http://spark.tail8791ce.ts.net:30880","fetchedAt":"…"}
```

MCP: `asset_cloudstack_activity {assetId}` returns the same.

## Configuration

| Key | Env var | Default |
|---|---|---|
| `cloudstack.api-url` | `CLOUDSTACK_API_URL` | empty = feature off |
| `cloudstack.token` | `CLOUDSTACK_TOKEN` | empty (never logged or returned) |
| `cloudstack.ui-url` | `CLOUDSTACK_UI_URL` | empty (no New task link unless cloud-stack sends one) |
| `cloudstack.timeout-ms` | `CLOUDSTACK_TIMEOUT_MS` | `3000` |
| `cloudstack.cache-ttl-ms` | `CLOUDSTACK_CACHE_TTL_MS` | `30000` |

### Kubernetes (spark)

`scripts/install-k8s.sh` sets on the backend:

- `CLOUDSTACK_API_URL` — `DEVPORTAL_CLOUDSTACK_API_URL`, else the existing install's value, else
  `http://cloudstack-api.cloudstack.svc.cluster.local:8080`. Pass `DEVPORTAL_CLOUDSTACK_API_URL=off` to turn it off.
- `CLOUDSTACK_UI_URL` — `DEVPORTAL_CLOUDSTACK_UI_URL`, else the existing install's value.
- `CLOUDSTACK_TOKEN` — from Secret `cloudstack-api-token`, key `token`, in the `devportal` namespace.
  The reference is `optional`, so the pod starts without it (the tab then says cloud-stack refused the request).

The installer never handles the token. Create the Secret straight from cloud-stack's own Secret
(`cloudstack-env`, key `API_BEARER_TOKEN`, namespace `cloudstack`) — the value goes through a pipe and is
never printed or put on a command line:

```sh
ssh spark.local "microk8s kubectl -n cloudstack get secret cloudstack-env -o jsonpath='{.data.API_BEARER_TOKEN}' | base64 -d \
  | microk8s kubectl -n devportal create secret generic cloudstack-api-token --from-file=token=/dev/stdin \
      --dry-run=client -o yaml | microk8s kubectl apply -f -"
ssh spark.local 'microk8s kubectl -n devportal rollout restart deploy/devportal-backend'
```

(`--dry-run=client -o yaml | kubectl apply -f -` makes it re-runnable when the token rotates; the plain
`create secret … --from-file=token=/dev/stdin` also works the first time.) Pods read the Secret at start,
hence the restart.

Install or upgrade with the UI URL set (it is kept on later upgrades):

```sh
ssh -t spark.local 'curl -fsSL https://raw.githubusercontent.com/geekychris/devhubpro/main/scripts/install-k8s.sh \
  | DEVPORTAL_REF=main DEVPORTAL_URLS_HOST=spark.local DEVPORTAL_CLOUDSTACK_UI_URL=http://spark.tail8791ce.ts.net:30880 bash'
```

Or on a running install, without rebuilding:

```sh
ssh spark.local 'microk8s kubectl -n devportal set env deploy/devportal-backend \
  CLOUDSTACK_API_URL=http://cloudstack-api.cloudstack.svc.cluster.local:8080 \
  CLOUDSTACK_UI_URL=http://spark.tail8791ce.ts.net:30880'
```

Check: `curl -s http://spark.local:30573/api/cloudstack/status` — expect `configured`, `tokenConfigured`
and `reachable` all `true`.

### Local / direct mode

```sh
export CLOUDSTACK_API_URL=http://<cloud-stack-api-host>:8080   # wherever the cloud-stack API answers
export CLOUDSTACK_UI_URL=http://spark.tail8791ce.ts.net:30880
export CLOUDSTACK_TOKEN=…                                 # from your environment or a secrets manager
cd backend && ./gradlew bootRun
```
