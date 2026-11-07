-- Deployment registry: "what is running where". An asset is the thing we build
-- (a repo / service); a deployment is one observed instance of it on a target
-- (a k8s cluster, a host, the FaaS server, a Mac ...), reported by a source
-- (cloud-stack's SaaS pack reporter, an SDLC delivery, a human, Claude Code ...).
--
-- Targets and URLs are stored exactly as reported (hostnames, MagicDNS names,
-- or IPs) — the registry never resolves or rewrites them.

CREATE TABLE deployment (
    id               BIGSERIAL   PRIMARY KEY,
    asset_id         TEXT        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    -- Where it runs: free text ("wopr", "mp2", "m1", "spark") ...
    target           TEXT        NOT NULL,
    -- ... and what kind of place that is: k8s-cluster | host | faas | docker | mac | other.
    target_kind      TEXT        NOT NULL DEFAULT 'other',
    -- Namespace / location within the target ('' when not applicable).
    namespace        TEXT        NOT NULL DEFAULT '',
    -- Instance name within (asset, target, namespace) ('' for the only instance).
    name             TEXT        NOT NULL DEFAULT '',
    version          TEXT,
    git_commit       TEXT,
    git_ref          TEXT,
    -- running: it is (supposed to be) up; delivered: built / PR delivered, not known to run.
    phase            TEXT        NOT NULL DEFAULT 'running'
                     CHECK (phase IN ('running', 'delivered')),
    -- [{"label": "UI", "url": "http://wopr:31080", "primary": true}, ...]
    urls             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    health_url       TEXT,
    -- Who reported it, e.g. 'cloud-stack saaspack', 'cloud-stack sdlc', 'manual'.
    source           TEXT        NOT NULL DEFAULT 'manual',
    labels           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    notes            TEXT,
    -- Effective status: what the health poller saw, or what the reporter said when
    -- there is nothing to poll. 'retired' rows are kept for history.
    status           TEXT        NOT NULL DEFAULT 'unknown'
                     CHECK (status IN ('reported', 'healthy', 'degraded', 'down', 'unknown', 'retired')),
    reported_status  TEXT,
    last_checked_at  TIMESTAMPTZ,
    last_error       TEXT,
    last_latency_ms  INTEGER,
    first_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at       TIMESTAMPTZ,
    UNIQUE (asset_id, target, namespace, name)
);

CREATE INDEX deployment_target_idx ON deployment (target);
CREATE INDEX deployment_status_idx ON deployment (status);
CREATE INDEX deployment_source_idx ON deployment (source);
