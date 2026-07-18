-- V6 · the event key becomes part of the contract.
--
-- Before this, the producer wrote no key and the CONSUMER invented one by
-- string-building `type + ":" + id` at the point of use. That is a contract
-- gap wearing a convention: two consumers would have invented two different
-- keys for the same event and both would have believed they were deduping.
--
-- The key is TEXT and readable on purpose (`order.placed:<uuid>`), because the
-- place you actually need it is a dead-letter queue at 3am, and an opaque hash
-- tells you nothing there.

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS event_key TEXT;

-- backfill anything written before the contract existed, deterministically
UPDATE outbox SET event_key = topic || ':' || id WHERE event_key IS NULL;

ALTER TABLE outbox ALTER COLUMN event_key SET NOT NULL;
-- UNIQUE is the producer-side half of exactly-once: the same business event
-- cannot be announced twice even if the calling code is retried.
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_event_key ON outbox(event_key);
