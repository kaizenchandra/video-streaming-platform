CREATE INDEX uploads_aborted_collection ON uploads (created_at) WHERE state='ABORTED' AND erased_at IS NULL;
