-- Core domain schema for vds-provider-grabber: providers, their legal info, accepted payment
-- methods, server locations, tariffs with an append-only price history (SCD type 2) and a frozen
-- USD equivalent, plus the auth tables (app_user/role/user_role).
--
-- Conventions followed throughout (see task vds-001.4):
--   * money is NUMERIC, never float/double; timestamps are TIMESTAMPTZ, stored in UTC;
--   * country codes are ISO 3166-1 alpha-2 CHAR(2) uppercase with a CHECK; currencies are
--     ISO 4217 CHAR(3) uppercase with a CHECK;
--   * enum-like columns are VARCHAR + CHECK (col IN (...)), never a native PostgreSQL ENUM type,
--     so extending the allowed set later is a transactional, reversible migration;
--   * created_at/updated_at default to now() on insert; updated_at is maintained by the
--     application in the same UPDATE statement -- no triggers;
--   * every CHECK/unique constraint has an explicit, readable name.
--
-- Table order follows FK dependencies. In particular app_user (and role/user_role) are created
-- before tariff_price, which references app_user(id) via recorded_by.

CREATE TABLE provider (
    id BIGSERIAL PRIMARY KEY,
    slug VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    website_url TEXT NOT NULL,
    description TEXT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_slug UNIQUE (slug),
    CONSTRAINT ck_provider_slug_format CHECK (slug ~ '^[a-z0-9-]+$'),
    CONSTRAINT ck_provider_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'UNKNOWN'))
);

-- 0..1 per provider: legal entity details, filled in with varying confidence as they are
-- discovered (manually, by a future crawler, by WHOIS, ...).
CREATE TABLE provider_legal_info (
    provider_id BIGINT PRIMARY KEY REFERENCES provider (id) ON DELETE CASCADE,
    legal_entity_name VARCHAR(255) NULL,
    registration_country CHAR(2) NULL,
    registration_number VARCHAR(128) NULL,
    legal_address TEXT NULL,
    data_source VARCHAR(32) NOT NULL,
    confidence VARCHAR(16) NOT NULL,
    source_url TEXT NULL,
    verified_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_provider_legal_info_registration_country
        CHECK (registration_country IS NULL OR registration_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_provider_legal_info_data_source
        CHECK (data_source IN ('MANUAL', 'CRAWLER', 'WHOIS', 'PROVIDER_SITE', 'UNKNOWN')),
    CONSTRAINT ck_provider_legal_info_confidence
        CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNVERIFIED'))
);

CREATE TABLE provider_payment_method (
    provider_id BIGINT NOT NULL REFERENCES provider (id) ON DELETE CASCADE,
    method VARCHAR(32) NOT NULL,
    note TEXT NULL,
    PRIMARY KEY (provider_id, method),
    -- Exactly these 20 values; extensible only by a new migration.
    CONSTRAINT ck_provider_payment_method_method CHECK (method IN (
        'VISA_MASTERCARD', 'AMERICAN_EXPRESS', 'UNIONPAY', 'MIR', 'SBP', 'RU_BANK_TRANSFER',
        'BANK_WIRE', 'PAYPAL', 'ALIPAY', 'WECHAT_PAY', 'APPLE_PAY', 'GOOGLE_PAY',
        'CRYPTO_BTC', 'CRYPTO_ETH', 'CRYPTO_USDT', 'CRYPTO_OTHER', 'WEBMONEY', 'PERFECT_MONEY',
        'CASH', 'OTHER'
    ))
);

CREATE TABLE location (
    id BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES provider (id) ON DELETE CASCADE,
    country_code CHAR(2) NOT NULL,
    region VARCHAR(128) NULL,
    city VARCHAR(128) NULL,
    datacenter_name VARCHAR(255) NULL,
    latitude NUMERIC(9, 6) NULL,
    longitude NUMERIC(9, 6) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_location_country_code CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_location_latitude_range CHECK (latitude IS NULL OR (latitude BETWEEN -90 AND 90)),
    CONSTRAINT ck_location_longitude_range
        CHECK (longitude IS NULL OR (longitude BETWEEN -180 AND 180)),
    CONSTRAINT ck_location_lat_lon_both_or_neither CHECK ((latitude IS NULL) = (longitude IS NULL)),
    -- Target of the composite FK from tariff_location below.
    CONSTRAINT uq_location_id_provider_id UNIQUE (id, provider_id)
);

-- Idempotent upsert target for the future crawler: one location per
-- provider/country/city/datacenter, case-insensitively, treating NULL city/datacenter as ''.
CREATE UNIQUE INDEX uq_location_provider_country_city_dc
    ON location (provider_id, country_code, lower(coalesce(city, '')), lower(coalesce(datacenter_name, '')));

CREATE TABLE tariff (
    id BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES provider (id) ON DELETE CASCADE,
    external_id VARCHAR(128) NULL,
    name VARCHAR(255) NOT NULL,
    url TEXT NULL,
    vcpu_count SMALLINT NOT NULL,
    cpu_model VARCHAR(128) NULL,
    ram_mb INTEGER NOT NULL,
    disk_gb INTEGER NOT NULL,
    disk_type VARCHAR(16) NOT NULL,
    bandwidth_mbps INTEGER NULL,
    -- NULL = unmetered traffic.
    traffic_limit_gb INTEGER NULL,
    ipv4_count SMALLINT NOT NULL DEFAULT 1,
    ipv6_supported BOOLEAN NULL,
    virtualization VARCHAR(16) NOT NULL,
    -- The plan still exists in the catalog.
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    -- The provider is currently selling it; a temporarily sold-out plan gets in_stock = false,
    -- not is_active = false.
    in_stock BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tariff_vcpu_count_positive CHECK (vcpu_count > 0),
    CONSTRAINT ck_tariff_ram_mb_positive CHECK (ram_mb > 0),
    CONSTRAINT ck_tariff_disk_gb_positive CHECK (disk_gb > 0),
    CONSTRAINT ck_tariff_disk_type CHECK (disk_type IN ('NVME', 'SSD', 'HDD', 'UNKNOWN')),
    CONSTRAINT ck_tariff_ipv4_count_non_negative CHECK (ipv4_count >= 0),
    CONSTRAINT ck_tariff_virtualization
        CHECK (virtualization IN ('KVM', 'OPENVZ', 'LXC', 'XEN', 'HYPERV', 'UNKNOWN')),
    -- Target of the composite FK from tariff_location below.
    CONSTRAINT uq_tariff_id_provider_id UNIQUE (id, provider_id)
);

CREATE UNIQUE INDEX uq_tariff_provider_name ON tariff (provider_id, lower(name));

CREATE UNIQUE INDEX uq_tariff_provider_external_id
    ON tariff (provider_id, external_id)
    WHERE external_id IS NOT NULL;

-- Many-to-many between tariff and location. Both composite FKs include provider_id so a
-- cross-provider link is impossible at the database level, protecting the future crawler even
-- though the service layer also pre-checks and returns 409.
CREATE TABLE tariff_location (
    tariff_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    provider_id BIGINT NOT NULL,
    PRIMARY KEY (tariff_id, location_id),
    CONSTRAINT fk_tariff_location_tariff FOREIGN KEY (tariff_id, provider_id)
        REFERENCES tariff (id, provider_id) ON DELETE CASCADE,
    CONSTRAINT fk_tariff_location_location FOREIGN KEY (location_id, provider_id)
        REFERENCES location (id, provider_id) ON DELETE CASCADE
);

-- Auth tables. Created ahead of tariff_price, which references app_user(id) via recorded_by.

CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(255) NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    token_version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE role (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    description TEXT NULL,
    CONSTRAINT uq_role_code UNIQUE (code)
);

INSERT INTO role (code, description) VALUES
    ('ADMIN', 'Full access: manage providers, tariffs, prices and other users'),
    ('EDITOR', 'Can create and edit providers, tariffs and prices'),
    ('VIEWER', 'Read-only access to the catalog');

-- The primary key (user_id, role_id) already leads with user_id, so "load status, token_version
-- and roles for user id X" is one indexed query through app_user.id and this PK -- no separate
-- index on user_id is added here.
CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- Append-only price history (SCD type 2): a new row is inserted for every price change, the
-- previous current row's effective_to is closed out. The USD equivalent (amount_usd/usd_rate) is
-- frozen at write time and never recomputed when FX rates move later.
CREATE TABLE tariff_price (
    id BIGSERIAL PRIMARY KEY,
    tariff_id BIGINT NOT NULL REFERENCES tariff (id) ON DELETE CASCADE,
    billing_period VARCHAR(16) NOT NULL,
    amount NUMERIC(12, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_usd NUMERIC(12, 4) NULL,
    usd_rate NUMERIC(20, 12) NULL,
    usd_rate_at TIMESTAMPTZ NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ NULL,
    data_source VARCHAR(32) NOT NULL,
    recorded_by BIGINT NULL REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tariff_price_billing_period CHECK (billing_period IN (
        'HOURLY', 'MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL', 'BIENNIAL', 'TRIENNIAL'
    )),
    CONSTRAINT ck_tariff_price_amount_non_negative CHECK (amount >= 0),
    CONSTRAINT ck_tariff_price_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_tariff_price_data_source CHECK (data_source IN ('MANUAL', 'CRAWLER')),
    CONSTRAINT ck_tariff_price_effective_to_after_from
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- The central invariant of this increment: at most one *current* price per (tariff, billing
-- period). A row with effective_to IS NULL is the current one; closed (historical) rows are
-- unrestricted.
CREATE UNIQUE INDEX uq_tariff_price_current
    ON tariff_price (tariff_id, billing_period)
    WHERE effective_to IS NULL;

CREATE INDEX ix_tariff_price_tariff_period_effective_from
    ON tariff_price (tariff_id, billing_period, effective_from DESC);

-- FX rate history. amount_usd = amount * rate_to_usd.
CREATE TABLE currency_rate (
    id BIGSERIAL PRIMARY KEY,
    currency CHAR(3) NOT NULL,
    rate_to_usd NUMERIC(20, 12) NOT NULL,
    -- The upstream's own "rates as of" timestamp.
    source_rate_at TIMESTAMPTZ NOT NULL,
    -- Our fetch time.
    fetched_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL,
    CONSTRAINT ck_currency_rate_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_currency_rate_rate_to_usd_positive CHECK (rate_to_usd > 0),
    CONSTRAINT uq_currency_rate_currency_source_rate_at UNIQUE (currency, source, source_rate_at)
);

CREATE INDEX ix_currency_rate_currency_source_rate_at_fetched_at
    ON currency_rate (currency, source_rate_at DESC, fetched_at DESC);
