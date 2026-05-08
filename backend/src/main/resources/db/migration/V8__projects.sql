-- Manually-curated grouping of assets into projects (and sub-projects). An asset can
-- belong to many projects; deleting a project doesn't delete its assets, just the
-- membership rows. parent_id forms the tree; the root level is parent_id IS NULL.

CREATE TABLE project (
    id           bigserial PRIMARY KEY,
    parent_id    bigint REFERENCES project(id) ON DELETE CASCADE,
    name         varchar(200) NOT NULL,
    description  text,
    metadata     jsonb,
    sort_order   integer NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_parent ON project(parent_id);

CREATE TABLE project_asset (
    project_id bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    asset_id   varchar(64) NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    added_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, asset_id)
);

CREATE INDEX idx_project_asset_asset ON project_asset(asset_id);
