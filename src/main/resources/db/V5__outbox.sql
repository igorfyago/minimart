-- V5 · the transactional outbox.
--
-- You cannot commit to Postgres and to Kafka atomically. So never write to two
-- systems: the event is INSERTed here inside the SAME transaction as the money
-- and the goods, and a relay ships it afterwards, marking it published only
-- once the broker has acknowledged it.
--
-- Crash anywhere and the event is resent, never lost. Loss becomes impossible,
-- duplicates become possible, and the consumers are built to expect them.

CREATE TABLE IF NOT EXISTS outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT NOT NULL,
    key          TEXT NOT NULL,          -- the partition key: ordering is per key
    payload      TEXT NOT NULL,
    business_at  TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
-- a partial index, because the only question ever asked is "what is unsent?"
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox(id) WHERE published_at IS NULL;

-- The receiving half of at-least-once. A consumer records the event key it has
-- applied; the PRIMARY KEY makes a redelivery a no-op rather than a promise.
CREATE TABLE IF NOT EXISTS handled_events (
    event_key   TEXT NOT NULL,
    consumer    TEXT NOT NULL,
    business_at TIMESTAMPTZ NOT NULL,
    handled_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_key, consumer)
);
