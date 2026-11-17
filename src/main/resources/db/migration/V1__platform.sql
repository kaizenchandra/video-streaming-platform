CREATE TABLE accounts
(
    id           text PRIMARY KEY,
    display_name text        NOT NULL,
    active       boolean     NOT NULL DEFAULT true,
    reserved     bigint      NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    used         bigint      NOT NULL DEFAULT 0 CHECK (used >= 0),
    quota        bigint      NOT NULL DEFAULT 107374182400 CHECK (quota >= 0),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE videos
(
    id              uuid PRIMARY KEY,
    owner_id        text          NOT NULL REFERENCES accounts (id),
    title           varchar(200)  NOT NULL,
    description     varchar(5000) NOT NULL DEFAULT '',
    category        varchar(80)   NOT NULL DEFAULT 'general',
    tags            text          NOT NULL DEFAULT '',
    visibility      text          NOT NULL CHECK (visibility IN ('PUBLIC', 'UNLISTED', 'PRIVATE')),
    state           text          NOT NULL DEFAULT 'DRAFT',
    published       boolean       NOT NULL DEFAULT false,
    current_attempt uuid,
    duration        double precision,
    version         bigint        NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    published_at    timestamptz,
    deleted         boolean       NOT NULL DEFAULT false,
    search          tsvector GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || description || ' ' || tags)) STORED,
    CHECK (NOT published OR (state = 'READY' AND current_attempt IS NOT NULL))
);
CREATE INDEX videos_catalog ON videos (created_at DESC, id DESC) WHERE published AND NOT deleted AND visibility='PUBLIC';
CREATE INDEX videos_search ON videos USING gin(search);
CREATE TABLE uploads
(
    id              uuid PRIMARY KEY,
    video_id        uuid         NOT NULL REFERENCES videos (id),
    owner_id        text         NOT NULL REFERENCES accounts (id),
    idempotency_key varchar(100) NOT NULL,
    object_key      text         NOT NULL UNIQUE,
    multipart_id    text,
    expected_bytes  bigint       NOT NULL CHECK (expected_bytes BETWEEN 1 AND 21474836480),
    sha256          varchar(64)  NOT NULL,
    state           text         NOT NULL DEFAULT 'INITIATING',
    created_at      timestamptz  NOT NULL DEFAULT now(),
    expires_at      timestamptz  NOT NULL DEFAULT now() + interval '24 hours',
    UNIQUE (owner_id, idempotency_key)
);
CREATE UNIQUE INDEX one_open_upload ON uploads (video_id) WHERE state NOT IN ('ABORTED','COMPLETED');
CREATE TABLE jobs
(
    id          uuid PRIMARY KEY,
    video_id    uuid        NOT NULL REFERENCES videos (id),
    source_key  text        NOT NULL,
    sha256      text        NOT NULL,
    state       text        NOT NULL DEFAULT 'QUEUED',
    attempt     uuid,
    attempts    integer     NOT NULL DEFAULT 0,
    lease_until timestamptz,
    error       text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_live_job ON jobs (video_id) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX jobs_claim ON jobs (state, lease_until, created_at);
CREATE TABLE processing_attempts
(
    id          uuid PRIMARY KEY,
    job_id      uuid        NOT NULL REFERENCES jobs (id),
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    state       text        NOT NULL DEFAULT 'RUNNING',
    error       text
);
CREATE TABLE renditions
(
    attempt  uuid    NOT NULL REFERENCES processing_attempts (id),
    height   integer NOT NULL,
    bitrate  integer NOT NULL,
    manifest text    NOT NULL,
    PRIMARY KEY (attempt, height)
);
CREATE TABLE playback_sessions
(
    id            uuid PRIMARY KEY,
    video_id      uuid        NOT NULL REFERENCES videos (id),
    user_id       text        NOT NULL REFERENCES accounts (id),
    attempt       uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL DEFAULT now() + interval '4 hours',
    last_sequence bigint      NOT NULL DEFAULT -1,
    qualified     boolean     NOT NULL DEFAULT false
);
CREATE TABLE watch_progress
(
    user_id    text             NOT NULL REFERENCES accounts (id),
    video_id   uuid             NOT NULL REFERENCES videos (id),
    session_id uuid             NOT NULL,
    position   double precision NOT NULL CHECK (position >= 0),
    completed  boolean          NOT NULL,
    updated_at timestamptz      NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, video_id)
);
CREATE TABLE video_likes
(
    user_id  text REFERENCES accounts (id),
    video_id uuid REFERENCES videos (id),
    PRIMARY KEY (user_id, video_id)
);
CREATE TABLE watchlist
(
    user_id  text REFERENCES accounts (id),
    video_id uuid REFERENCES videos (id),
    PRIMARY KEY (user_id, video_id)
);
CREATE TABLE comments
(
    id         uuid PRIMARY KEY,
    video_id   uuid REFERENCES videos (id),
    user_id    text REFERENCES accounts (id),
    body       varchar(2000) NOT NULL,
    created_at timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX comments_page ON comments (video_id, created_at, id);
CREATE TABLE events
(
    id             uuid PRIMARY KEY,
    aggregate_id   uuid        NOT NULL,
    kind           text        NOT NULL,
    schema_version integer     NOT NULL DEFAULT 1,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    processed_at   timestamptz
);
CREATE INDEX events_pending ON events (created_at) WHERE processed_at IS NULL;
CREATE TABLE video_views
(
    video_id uuid PRIMARY KEY REFERENCES videos (id),
    views    bigint NOT NULL DEFAULT 0
);
