CREATE TABLE build (
    id              BIGSERIAL   PRIMARY KEY,
    asset_id        TEXT        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    parent_build_id BIGINT      REFERENCES build(id) ON DELETE SET NULL,
    mode            TEXT        NOT NULL CHECK (mode IN ('shallow', 'deep')),
    command_name    TEXT        NOT NULL,
    command_line    TEXT        NOT NULL,
    git_ref         TEXT        NOT NULL,
    git_sha         TEXT,
    workspace_path  TEXT        NOT NULL,
    log_path        TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    exit_code       INTEGER,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX build_asset_idx          ON build (asset_id);
CREATE INDEX build_status_idx         ON build (status);
CREATE INDEX build_created_at_idx     ON build (created_at DESC);
CREATE INDEX build_parent_idx         ON build (parent_build_id);
