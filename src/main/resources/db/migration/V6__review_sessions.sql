ALTER TABLE playback_sessions
    ADD COLUMN purpose text NOT NULL DEFAULT 'WATCH' CHECK (purpose IN ('WATCH', 'REVIEW'));
