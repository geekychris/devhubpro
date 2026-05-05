-- Per-asset favorite flag and 1-5 star rating.
-- Single-user portal: no per-user join table needed; columns live on the asset row.
ALTER TABLE asset ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE asset ADD COLUMN rating SMALLINT;

-- Rating range guard: NULL = unrated; otherwise 1..5.
ALTER TABLE asset ADD CONSTRAINT asset_rating_range CHECK (rating IS NULL OR (rating BETWEEN 1 AND 5));

-- Useful for the "show my favorites" filter and ordering by rating.
CREATE INDEX asset_favorite_rating_idx ON asset (favorite DESC, rating DESC NULLS LAST, id);
