CREATE SCHEMA IF NOT EXISTS event_domain;
CREATE SCHEMA IF NOT EXISTS verification;
CREATE SCHEMA IF NOT EXISTS ballot_core;
CREATE SCHEMA IF NOT EXISTS operations;
CREATE DOMAIN IF NOT EXISTS jsonb AS JSON;

CREATE TABLE event_domain.events (
    id UUID PRIMARY KEY, public_id VARCHAR(96) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL, starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL, rules_version INTEGER NOT NULL,
    candidate_version INTEGER NOT NULL, first_ballot_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0, title VARCHAR(200) NOT NULL DEFAULT 'Untitled event',
    short_description VARCHAR(500) NOT NULL DEFAULT '', long_description CLOB NOT NULL DEFAULT '',
    organizer_name VARCHAR(200) NOT NULL DEFAULT 'Community organizer',
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC', verification_level VARCHAR(32) NOT NULL DEFAULT 'ACCOUNT',
    result_visibility VARCHAR(24) NOT NULL DEFAULT 'AFTER_CLOSE', CHECK (starts_at < ends_at)
);

CREATE TABLE verification.eligibility_credentials (
    id UUID PRIMARY KEY, event_id UUID NOT NULL REFERENCES event_domain.events(id),
    tier VARCHAR(32) NOT NULL, subject_commitment VARBINARY NOT NULL, policy_version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (event_id, subject_commitment, policy_version)
);

CREATE TABLE ballot_core.ballots (
    id UUID PRIMARY KEY, event_id UUID NOT NULL REFERENCES event_domain.events(id),
    credential_id UUID NOT NULL REFERENCES verification.eligibility_credentials(id),
    idempotency_key UUID NOT NULL, request_digest VARBINARY NOT NULL, rules_version INTEGER NOT NULL,
    candidate_version INTEGER NOT NULL, ballot_type VARCHAR(24) NOT NULL,
    encrypted_payload VARBINARY NOT NULL, payload_digest VARBINARY NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (event_id, credential_id), UNIQUE (event_id, idempotency_key)
);

CREATE TABLE ballot_core.receipts (
    id UUID PRIMARY KEY, ballot_id UUID NOT NULL UNIQUE REFERENCES ballot_core.ballots(id),
    public_token_hash VARBINARY NOT NULL UNIQUE, accepted_time_bucket TIMESTAMP WITH TIME ZONE NOT NULL,
    signature VARBINARY NOT NULL
);

CREATE TABLE operations.outbox (
    id UUID PRIMARY KEY, aggregate_type VARCHAR(64) NOT NULL, aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL, payload jsonb NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE event_domain.rules (
    event_id UUID NOT NULL REFERENCES event_domain.events(id), version INTEGER NOT NULL,
    ballot_type VARCHAR(24) NOT NULL, max_choices INTEGER NOT NULL, canonical_json jsonb NOT NULL,
    PRIMARY KEY (event_id, version)
);

CREATE TABLE event_domain.candidates (
    event_id UUID NOT NULL REFERENCES event_domain.events(id), id UUID NOT NULL, version INTEGER NOT NULL,
    display_order INTEGER NOT NULL, status VARCHAR(16) NOT NULL, name VARCHAR(200) NOT NULL,
    PRIMARY KEY (event_id, id, version), UNIQUE (event_id, version, display_order)
);

CREATE TRIGGER ballots_immutable BEFORE UPDATE, DELETE ON ballot_core.ballots
FOR EACH ROW CALL 'com.pulsevote.ballot.ImmutableBallotTrigger';
