CREATE TABLE event_domain.rules (
    event_id uuid NOT NULL REFERENCES event_domain.events(id),
    version integer NOT NULL CHECK (version > 0),
    ballot_type varchar(24) NOT NULL CHECK (ballot_type IN ('SINGLE','MULTIPLE','RANKED')),
    max_choices integer NOT NULL CHECK (max_choices BETWEEN 1 AND 50),
    canonical_json jsonb NOT NULL,
    PRIMARY KEY (event_id, version)
);

CREATE TABLE event_domain.candidates (
    event_id uuid NOT NULL REFERENCES event_domain.events(id),
    id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    display_order integer NOT NULL CHECK (display_order >= 0),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','WITHDRAWN')),
    name varchar(200) NOT NULL,
    PRIMARY KEY (event_id, id, version),
    UNIQUE (event_id, version, display_order)
);

CREATE INDEX ix_candidates_active ON event_domain.candidates (event_id, version, id) WHERE status = 'ACTIVE';

