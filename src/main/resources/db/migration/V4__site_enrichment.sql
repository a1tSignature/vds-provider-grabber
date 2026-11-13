-- Provenance for data that automation fills in next to humans ("manual always wins, automation only
-- adds, removal by a human is remembered").
--
-- Legal fields carry a per-field origin. NULL origin means "nobody claimed this field": automation
-- may fill it. MANUAL is never overwritten by automation, even when the value itself is NULL.
ALTER TABLE provider_legal_info
    ADD COLUMN legal_entity_name_origin VARCHAR(16) NULL,
    ADD COLUMN registration_country_origin VARCHAR(16) NULL,
    ADD CONSTRAINT ck_provider_legal_info_legal_entity_name_origin
        CHECK (legal_entity_name_origin IN ('AUTO_CONFIRMED', 'AUTO_PRESUMED', 'MANUAL')),
    ADD CONSTRAINT ck_provider_legal_info_registration_country_origin
        CHECK (registration_country_origin IN ('AUTO_CONFIRMED', 'AUTO_PRESUMED', 'MANUAL'));

-- Only a crawler-written, never-verified value is treated as automatic; anything a human could
-- have typed or checked is protected.
UPDATE provider_legal_info
SET legal_entity_name_origin = CASE
        WHEN legal_entity_name IS NULL THEN NULL
        WHEN data_source <> 'CRAWLER' OR verified_at IS NOT NULL THEN 'MANUAL'
        ELSE 'AUTO_CONFIRMED'
    END,
    registration_country_origin = CASE
        WHEN registration_country IS NULL THEN NULL
        WHEN data_source <> 'CRAWLER' OR verified_at IS NOT NULL THEN 'MANUAL'
        ELSE 'AUTO_CONFIRMED'
    END;

-- The DEFAULT makes every existing row and every writer that states no origin manual.
ALTER TABLE provider_payment_method
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD CONSTRAINT ck_provider_payment_method_origin CHECK (origin IN ('AUTO', 'MANUAL'));

ALTER TABLE location
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD CONSTRAINT ck_location_origin CHECK (origin IN ('AUTO', 'MANUAL'));

-- State of the latest enrichment attempt, and the evidence of the latest successful one.
-- `evidence` is a JSON document read and written whole by the application.
CREATE TABLE provider_enrichment (
    provider_id BIGINT PRIMARY KEY REFERENCES provider (id) ON DELETE CASCADE,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    last_outcome VARCHAR(32) NOT NULL,
    last_message VARCHAR(500) NULL,
    last_pages_fetched INTEGER NOT NULL DEFAULT 0,
    last_success_at TIMESTAMPTZ NULL,
    evidence TEXT NULL,
    CONSTRAINT ck_provider_enrichment_last_outcome CHECK (last_outcome IN (
        'SUCCESS', 'NOTHING_FOUND', 'BLOCKED', 'UNREACHABLE', 'ROBOTS_DISALLOWED', 'FAILED',
        'TIMEOUT', 'DEFERRED', 'NOT_DUE', 'SKIPPED', 'NO_PROVIDER', 'DISABLED'
    )),
    CONSTRAINT ck_provider_enrichment_last_pages_fetched CHECK (last_pages_fetched >= 0)
);

-- A value a human removed from automatic data; automation never re-adds it. `value` is a
-- PaymentMethod name or an ISO alpha-2 country code.
CREATE TABLE provider_enrichment_dismissal (
    provider_id BIGINT NOT NULL REFERENCES provider (id) ON DELETE CASCADE,
    facet VARCHAR(32) NOT NULL,
    value VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_id, facet, value),
    CONSTRAINT ck_provider_enrichment_dismissal_facet CHECK (facet IN ('PAYMENT_METHOD', 'LOCATION'))
);
