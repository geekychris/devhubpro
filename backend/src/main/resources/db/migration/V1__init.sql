-- Placeholder. Real schema lands in V2 (task #3).
CREATE TABLE IF NOT EXISTS devportal_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO devportal_meta(key, value)
VALUES ('schema_version', '1')
ON CONFLICT (key) DO NOTHING;
