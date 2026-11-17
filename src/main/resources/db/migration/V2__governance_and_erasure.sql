ALTER TABLE accounts
    ADD COLUMN preferences jsonb NOT NULL DEFAULT '{}', ADD COLUMN profile_version bigint NOT NULL DEFAULT 0;
CREATE TABLE channels
(
    owner_id    text PRIMARY KEY REFERENCES accounts (id),
    handle      varchar(40)   NOT NULL UNIQUE,
    name        varchar(100)  NOT NULL,
    description varchar(2000) NOT NULL DEFAULT '',
    version     bigint        NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now()
);
ALTER TABLE videos
    ADD COLUMN moderation_status text NOT NULL DEFAULT 'APPROVED' CHECK (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED')),
 ADD COLUMN deleted_at timestamptz, ADD COLUMN legal_hold boolean NOT NULL DEFAULT false;
ALTER TABLE videos
    ALTER COLUMN moderation_status SET DEFAULT 'PENDING';
ALTER TABLE videos
    ADD CONSTRAINT publication_moderated CHECK (NOT published OR moderation_status = 'APPROVED');
UPDATE videos
SET deleted_at=now()
WHERE deleted;
ALTER TABLE uploads
    ADD COLUMN erased_at timestamptz;
ALTER TABLE processing_attempts
    ADD COLUMN scan_result text NOT NULL DEFAULT 'NOT_SCANNED', ADD COLUMN collected_at timestamptz;
CREATE TABLE moderation_decisions
(
    id            uuid PRIMARY KEY,
    video_id      uuid          NOT NULL REFERENCES videos (id),
    attempt       uuid          NOT NULL REFERENCES processing_attempts (id),
    reviewer      text          NOT NULL REFERENCES accounts (id),
    decision      text          NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reason        varchar(2000) NOT NULL,
    video_version bigint        NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now()
);
CREATE TABLE erasure_jobs
(
    video_id     uuid PRIMARY KEY REFERENCES videos (id),
    state        text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DONE')),
    not_before   timestamptz NOT NULL,
    attempts     integer     NOT NULL DEFAULT 0,
    last_error   text,
    completed_at timestamptz
);
CREATE INDEX erasure_pending ON erasure_jobs (not_before) WHERE state='PENDING';
CREATE INDEX abandoned_attempts ON processing_attempts (started_at) WHERE collected_at IS NULL;
CREATE INDEX moderation_queue ON videos (created_at, id) WHERE moderation_status='PENDING' AND NOT deleted;
