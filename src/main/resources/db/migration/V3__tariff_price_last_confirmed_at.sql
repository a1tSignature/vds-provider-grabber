-- Adds `tariff_price.last_confirmed_at`: when a writer last asserted that this row's amount and
-- currency still hold. `PUT /api/v1/tariffs/{id}/price` with an unchanged value writes no new row,
-- so without this column a stable price is indistinguishable from one nobody looks at any more.
--
-- It is the second mutable column of a price row, next to `effective_to`; value columns stay
-- write-once. Nothing in the schema interprets it: no CHECK, no index.
--
-- Rows that already exist were last asserted when they were written, hence the backfill from
-- `created_at` before NOT NULL is applied.
--
-- The DEFAULT serves raw SQL inserts only (`dev-seed.sql`, schema tests); the application always
-- writes the column from its own clock.
ALTER TABLE tariff_price ADD COLUMN last_confirmed_at TIMESTAMPTZ;
UPDATE tariff_price SET last_confirmed_at = created_at;
ALTER TABLE tariff_price ALTER COLUMN last_confirmed_at SET NOT NULL;
ALTER TABLE tariff_price ALTER COLUMN last_confirmed_at SET DEFAULT now();
