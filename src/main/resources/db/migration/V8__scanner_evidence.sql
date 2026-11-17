ALTER TABLE processing_attempts
    ADD COLUMN scan_engine text, ADD COLUMN scan_database bigint, ADD COLUMN scan_checked_at timestamptz, ADD COLUMN scan_database_built_at timestamptz;
