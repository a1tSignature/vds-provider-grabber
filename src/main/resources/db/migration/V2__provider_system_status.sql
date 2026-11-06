-- Adds `provider.system_status` (increment vds-004): our own relationship to a catalog row, which
-- is independent of `provider.status` (a claim about the provider's business, untouched here).
--
-- Follows the conventions `V1__core_schema.sql` states at its top: an enum-like column is
-- VARCHAR + CHECK (col IN (...)), never a native PostgreSQL ENUM, and every constraint has an
-- explicit, readable name. The first additive migration of this project; V1 stays untouched.
--
--   CANDIDATE  -- discovered by the discovery leg, nobody has described how to read this site yet;
--                 hidden from the default listing.
--   CONFIGURED -- a normal catalog member; set automatically by the first crawl run for that slug
--                 that succeeded and actually wrote something.
--   REJECTED   -- terminal "won't do"; set only by a human and never promoted automatically.
--
-- The DEFAULT is permanent, not a migration-only convenience: a write that omits the field must
-- produce a *visible* provider (the safe direction for a hand-created row), and discovery always
-- sets the value explicitly. It is also what makes this migration safe for the rows that already
-- exist -- every one of them comes out CONFIGURED, so the public catalog keeps listing exactly
-- what it listed before (asserted by ProviderSystemStatusMigrationTest).
ALTER TABLE provider ADD COLUMN system_status VARCHAR(32) NOT NULL DEFAULT 'CONFIGURED';
ALTER TABLE provider ADD CONSTRAINT ck_provider_system_status
    CHECK (system_status IN ('CANDIDATE', 'CONFIGURED', 'REJECTED'));
