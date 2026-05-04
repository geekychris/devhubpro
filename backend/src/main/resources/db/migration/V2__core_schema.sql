-- Core dev_portal schema.
-- Asset is the central entity. AssetVersion captures a pinned git ref.
-- Dependency edges are between assets (logical) and may pin to a specific version.
-- ManifestSnapshot stores the parsed devportal.yaml at a point in time.
-- PortReservation is the port-registry table.
-- MetaAsset is the catalog of shared infra (postgres, redis, ...).

CREATE TABLE asset (
    id              TEXT        PRIMARY KEY,
    name            TEXT        NOT NULL,
    description     TEXT,
    owner           TEXT,
    type            TEXT        NOT NULL CHECK (type IN ('library', 'service', 'meta-asset')),
    language        TEXT,
    repo_url        TEXT        NOT NULL,
    repo_default_branch TEXT    NOT NULL DEFAULT 'main',
    tags            TEXT[]      NOT NULL DEFAULT '{}',
    lifecycle       TEXT        NOT NULL DEFAULT 'experimental'
                    CHECK (lifecycle IN ('experimental', 'stable', 'deprecated')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX asset_type_idx     ON asset (type);
CREATE INDEX asset_owner_idx    ON asset (owner);
CREATE INDEX asset_lifecycle_idx ON asset (lifecycle);
CREATE INDEX asset_tags_idx     ON asset USING GIN (tags);

CREATE TABLE asset_version (
    id              BIGSERIAL   PRIMARY KEY,
    asset_id        TEXT        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    git_ref         TEXT        NOT NULL,
    git_sha         TEXT,
    observed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (asset_id, git_ref)
);

CREATE INDEX asset_version_asset_idx ON asset_version (asset_id);

CREATE TABLE dependency (
    id              BIGSERIAL   PRIMARY KEY,
    consumer_id     TEXT        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    producer_id     TEXT        NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    version_ref     TEXT        NOT NULL DEFAULT 'main',
    kind            TEXT        NOT NULL DEFAULT 'build'
                    CHECK (kind IN ('build', 'runtime')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (consumer_id, producer_id, kind),
    CHECK (consumer_id <> producer_id)
);

CREATE INDEX dependency_consumer_idx ON dependency (consumer_id);
CREATE INDEX dependency_producer_idx ON dependency (producer_id);

CREATE TABLE manifest_snapshot (
    id              BIGSERIAL   PRIMARY KEY,
    asset_id        TEXT        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    git_sha         TEXT        NOT NULL,
    raw_yaml        TEXT        NOT NULL,
    parsed_json     JSONB       NOT NULL,
    valid           BOOLEAN     NOT NULL,
    validation_errors JSONB,
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (asset_id, git_sha)
);

CREATE INDEX manifest_snapshot_asset_idx ON manifest_snapshot (asset_id);

CREATE TABLE meta_asset (
    id              TEXT        PRIMARY KEY,
    name            TEXT        NOT NULL,
    kind            TEXT        NOT NULL,
    -- e.g. 'postgres', 'redis', 'memcache', 'opensearch', 'minio'
    config          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- shared connection info: host, port, credentials_secret_ref, etc.
    provisioned_by_portal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE consumes (
    id              BIGSERIAL   PRIMARY KEY,
    asset_id        TEXT        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    meta_asset_id   TEXT        NOT NULL REFERENCES meta_asset(id) ON DELETE RESTRICT,
    role            TEXT,
    UNIQUE (asset_id, meta_asset_id, role)
);

-- Port registry. Each row is a single allocated port for a specific (asset, slot, scope).
-- Scope distinguishes local-dev runs from k8s NodePort assignments.
CREATE TABLE port_reservation (
    id              BIGSERIAL   PRIMARY KEY,
    asset_id        TEXT        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    slot_name       TEXT        NOT NULL,
    scope           TEXT        NOT NULL CHECK (scope IN ('local', 'k8s-nodeport')),
    port            INTEGER     NOT NULL CHECK (port > 0 AND port < 65536),
    protocol        TEXT        NOT NULL DEFAULT 'tcp' CHECK (protocol IN ('tcp', 'udp')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (asset_id, slot_name, scope),
    UNIQUE (scope, port, protocol)
);

CREATE INDEX port_reservation_asset_idx ON port_reservation (asset_id);

-- Updated-at trigger function shared by tables that have updated_at.
CREATE OR REPLACE FUNCTION devportal_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER asset_set_updated_at
    BEFORE UPDATE ON asset
    FOR EACH ROW EXECUTE FUNCTION devportal_set_updated_at();

CREATE TRIGGER meta_asset_set_updated_at
    BEFORE UPDATE ON meta_asset
    FOR EACH ROW EXECUTE FUNCTION devportal_set_updated_at();
