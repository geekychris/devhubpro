-- Per-asset pin-to-dashboard flag. The dashboard shows every pinned asset (even when no pods
-- are running) so users can keep their portfolio of services in one place and toggle them on/off
-- without losing context.
ALTER TABLE asset ADD COLUMN dashboard_pinned BOOLEAN NOT NULL DEFAULT false;

-- Backfill: pin assets that already have any port allocation or kubernetes-enabled manifest.
-- For now we just look at port_reservation rows — anything with allocated ports has been
-- explicitly set up for runtime, so it's a reasonable default to surface on the dashboard.
UPDATE asset SET dashboard_pinned = true
WHERE id IN (SELECT DISTINCT asset_id FROM port_reservation);
