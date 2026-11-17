ALTER TABLE processing_attempts
    ADD COLUMN scan_evidence jsonb NOT NULL DEFAULT '{}'::jsonb;
