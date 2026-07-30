CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS event_domain;
CREATE SCHEMA IF NOT EXISTS verification;
CREATE SCHEMA IF NOT EXISTS ballot_core;
CREATE SCHEMA IF NOT EXISTS operations;

CREATE TABLE event_domain.events (
    id uuid PRIMARY KEY,
    public_id varchar(96) NOT NULL UNIQUE,
    status varchar(32) NOT NULL CHECK (status IN ('DRAFT','SCHEDULED','OPEN','PAUSED','CLOSED','RESULTS_PROCESSING','RESULTS_FINALIZED','CANCELLED','ARCHIVED')),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    rules_version integer NOT NULL CHECK (rules_version > 0),
    candidate_version integer NOT NULL CHECK (candidate_version > 0),
    first_ballot_at timestamptz,
    row_version bigint NOT NULL DEFAULT 0,
    CHECK (starts_at < ends_at)
);

CREATE TABLE verification.eligibility_credentials (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES event_domain.events(id),
    tier varchar(32) NOT NULL,
    subject_commitment bytea NOT NULL,
    policy_version integer NOT NULL CHECK (policy_version > 0),
    status varchar(16) NOT NULL CHECK (status IN ('ELIGIBLE','CONSUMED','REVOKED','EXPIRED')),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    UNIQUE (event_id, subject_commitment, policy_version)
);

CREATE TABLE ballot_core.ballots (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES event_domain.events(id),
    credential_id uuid NOT NULL REFERENCES verification.eligibility_credentials(id),
    idempotency_key uuid NOT NULL,
    request_digest bytea NOT NULL,
    rules_version integer NOT NULL,
    candidate_version integer NOT NULL,
    ballot_type varchar(24) NOT NULL,
    encrypted_payload bytea NOT NULL,
    payload_digest bytea NOT NULL,
    accepted_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (event_id, credential_id),
    UNIQUE (event_id, idempotency_key)
);

CREATE TABLE ballot_core.receipts (
    id uuid PRIMARY KEY,
    ballot_id uuid NOT NULL UNIQUE REFERENCES ballot_core.ballots(id),
    public_token_hash bytea NOT NULL UNIQUE,
    accepted_time_bucket timestamptz NOT NULL,
    signature bytea NOT NULL
);

CREATE TABLE operations.outbox (
    id uuid PRIMARY KEY,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    published_at timestamptz
);

CREATE INDEX ix_outbox_unpublished ON operations.outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX ix_ballots_event_accepted ON ballot_core.ballots (event_id, accepted_at, id);
CREATE INDEX ix_eligibility_available ON verification.eligibility_credentials (event_id, expires_at) WHERE status = 'ELIGIBLE';

CREATE OR REPLACE FUNCTION ballot_core.reject_ballot_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'accepted ballots are immutable';
END;
$$;

CREATE TRIGGER ballots_immutable
BEFORE UPDATE OR DELETE ON ballot_core.ballots
FOR EACH ROW EXECUTE FUNCTION ballot_core.reject_ballot_mutation();

