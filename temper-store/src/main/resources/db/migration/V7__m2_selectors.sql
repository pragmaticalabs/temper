-- M2 WP-C (m2-design §2.3): subscribers.selectors keeps its jsonb string array, and its content becomes the typed form
-- (job:<id>, sha:<40 hex>, ref:<name>). Under R-12 the untyped rows are reset instead of converted; no verb could set
-- them, so this is expected to touch nothing. A reset subscriber sees every job again (empty means all).
UPDATE subscribers SET selectors = '[]'::jsonb WHERE selectors <> '[]'::jsonb;

-- R-M2-12 (skip-ack): every selector change bumps the version, and a poll advances the cursor over the events it
-- scanned without a match only while the version is still the one it read, so a change that lands mid-poll is never
-- skipped over.
ALTER TABLE subscribers ADD COLUMN selectors_version bigint NOT NULL DEFAULT 0;
