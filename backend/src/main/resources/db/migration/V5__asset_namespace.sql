-- Per-asset Kubernetes namespace override. Null = derive from asset id (or manifest).
ALTER TABLE asset ADD COLUMN k8s_namespace TEXT;

-- Default it to the asset id for existing rows so new behavior is "isolated namespace per repo"
-- without requiring users to set it manually. Users can later null it out or change.
UPDATE asset SET k8s_namespace = id WHERE k8s_namespace IS NULL;
