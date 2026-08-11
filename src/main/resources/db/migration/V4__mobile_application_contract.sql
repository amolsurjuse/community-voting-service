ALTER TABLE event_domain.events
    ADD COLUMN organizer_id varchar(128),
    ADD COLUMN category varchar(32) NOT NULL DEFAULT 'COMMUNITY',
    ADD COLUMN cover_seed integer NOT NULL DEFAULT 0,
    ADD COLUMN cover_emoji varchar(32) NOT NULL DEFAULT '',
    ADD COLUMN location varchar(200) NOT NULL DEFAULT '',
    ADD COLUMN language varchar(64) NOT NULL DEFAULT 'English',
    ADD COLUMN organizer_verified boolean NOT NULL DEFAULT false,
    ADD COLUMN min_result_threshold integer NOT NULL DEFAULT 0,
    ADD COLUMN time_zone varchar(64) NOT NULL DEFAULT 'UTC',
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT transaction_timestamp();

ALTER TABLE event_domain.candidates
    ADD COLUMN subtitle varchar(200) NOT NULL DEFAULT '',
    ADD COLUMN description text NOT NULL DEFAULT '',
    ADD COLUMN organization varchar(200) NOT NULL DEFAULT '',
    ADD COLUMN organizer_notes text NOT NULL DEFAULT '',
    ADD COLUMN color_seed integer NOT NULL DEFAULT 0;

CREATE TABLE event_domain.invitations (
    token_hash bytea PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES event_domain.events(id),
    expires_at timestamptz NOT NULL,
    redeemed_at timestamptz
);

CREATE TABLE operations.activity (
    id uuid PRIMARY KEY,
    organizer_id varchar(128) NOT NULL,
    event_id uuid REFERENCES event_domain.events(id),
    kind varchar(40) NOT NULL,
    title varchar(200) NOT NULL,
    body varchar(500) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    read_at timestamptz
);

CREATE INDEX ix_events_organizer_updated ON event_domain.events (organizer_id, updated_at DESC);
CREATE INDEX ix_events_discovery ON event_domain.events (status, visibility, created_at DESC);
CREATE INDEX ix_activity_organizer ON operations.activity (organizer_id, occurred_at DESC);
