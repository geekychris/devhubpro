-- Records the build-tool coordinates each asset publishes (Maven groupId/artifactId, npm package, etc.).
-- Used to match an asset's *declared* dependencies (e.g. <dependency> in someone else's pom.xml)
-- back to a portal asset that publishes it, enabling automatic dependency edge suggestion.

CREATE TABLE asset_artifact (
    id           BIGSERIAL   PRIMARY KEY,
    asset_id     TEXT        NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    flavor       TEXT        NOT NULL DEFAULT 'maven'
                  CHECK (flavor IN ('maven', 'gradle', 'npm', 'pypi', 'go-module', 'cargo', 'other')),
    group_id     TEXT,                 -- null for npm (uses package name as artifact_id)
    artifact_id  TEXT        NOT NULL,
    version      TEXT,
    relative_path TEXT       NOT NULL DEFAULT '.', -- where in the repo this artifact lives (multi-module)
    detected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (asset_id, flavor, group_id, artifact_id, relative_path)
);

CREATE INDEX asset_artifact_lookup_idx
    ON asset_artifact (flavor, group_id, artifact_id);

CREATE INDEX asset_artifact_asset_idx
    ON asset_artifact (asset_id);
