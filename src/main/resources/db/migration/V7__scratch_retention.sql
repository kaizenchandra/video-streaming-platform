ALTER TABLE processing_attempts
    ADD COLUMN scratch_volume uuid, ADD COLUMN scratch_purged_at timestamptz, ADD COLUMN scratch_error text;
CREATE INDEX scratch_pending ON processing_attempts (scratch_volume, started_at) WHERE scratch_purged_at IS NULL;
