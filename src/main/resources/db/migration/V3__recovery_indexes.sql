ALTER TABLE jobs
    ADD COLUMN available_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX playback_attempt_live ON playback_sessions (attempt, expires_at);
CREATE INDEX jobs_video ON jobs (video_id);
CREATE INDEX uploads_video ON uploads (video_id);
