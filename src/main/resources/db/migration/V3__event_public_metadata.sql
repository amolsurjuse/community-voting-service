ALTER TABLE event_domain.events
    ADD COLUMN title varchar(200) NOT NULL DEFAULT 'Untitled event',
    ADD COLUMN short_description varchar(500) NOT NULL DEFAULT '',
    ADD COLUMN long_description text NOT NULL DEFAULT '',
    ADD COLUMN organizer_name varchar(200) NOT NULL DEFAULT 'Community organizer',
    ADD COLUMN visibility varchar(16) NOT NULL DEFAULT 'PUBLIC'
        CHECK (visibility IN ('PUBLIC','UNLISTED','PRIVATE')),
    ADD COLUMN verification_level varchar(32) NOT NULL DEFAULT 'ACCOUNT'
        CHECK (verification_level IN ('ACCOUNT','INSTALLATION','ATTESTED_INSTALLATION','PHONE','INVITATION','ORGANIZER_APPROVAL')),
    ADD COLUMN result_visibility varchar(24) NOT NULL DEFAULT 'AFTER_CLOSE'
        CHECK (result_visibility IN ('LIVE','AFTER_VOTE','AFTER_CLOSE','ORGANIZER_ONLY'));

CREATE INDEX ix_events_public_lookup ON event_domain.events (public_id)
    WHERE visibility IN ('PUBLIC','UNLISTED') AND status <> 'DRAFT';
