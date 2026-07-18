-- THE POISON MESSAGE.
--
-- A consumer reading a partition in order has a failure mode with no equivalent
-- in a request/response service: ONE event it cannot handle stops every event
-- behind it, forever. Commit the offset and the event is lost. Refuse to commit
-- and the partition never advances. Retry in place and the consumer spins on the
-- same record while the lag climbs and nothing else in that partition is served.
--
-- This is the same shape as the billing defect fixed earlier, where one
-- unfulfillable subscription ended the whole renewal pass, and it wants the same
-- answer: each item gets its own failure domain, and a failure that cannot be
-- retried is recorded somewhere a human will look rather than blocking the queue.
--
-- So a failed event moves OUT of the topic and into a local retry queue, the
-- offset advances, and the events behind it are served. Retries then happen on
-- our own schedule rather than the broker's, and an event that exhausts them
-- becomes a dead letter instead of an outage.

CREATE TABLE IF NOT EXISTS event_retries (
    event_key    TEXT        NOT NULL,
    consumer     TEXT        NOT NULL,
    topic        TEXT        NOT NULL,
    -- the payload is kept HERE rather than re-read from the topic, because by
    -- the time we retry, the offset has long since moved on and the retention
    -- window may have closed. A retry queue that depends on the broker still
    -- holding the message is not a retry queue.
    payload      TEXT        NOT NULL,
    attempts     INT         NOT NULL DEFAULT 1,
    last_error   TEXT,
    business_at  TIMESTAMPTZ NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_try_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_key, consumer)
);

-- Exhausted retries. Not deleted, not silently dropped: an event nobody could
-- handle is a fact about the system, and the payload is kept so that once the
-- cause is fixed it can be replayed rather than reconstructed from a log line.
CREATE TABLE IF NOT EXISTS dead_letters (
    id          BIGSERIAL PRIMARY KEY,
    event_key   TEXT        NOT NULL,
    consumer    TEXT        NOT NULL,
    topic       TEXT        NOT NULL,
    payload     TEXT        NOT NULL,
    attempts    INT         NOT NULL,
    last_error  TEXT,
    business_at TIMESTAMPTZ NOT NULL,
    died_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at TIMESTAMPTZ,
    -- one dead letter per (event, consumer): a second death is the same death
    CONSTRAINT dead_letters_once UNIQUE (event_key, consumer)
);
CREATE INDEX IF NOT EXISTS dead_letters_unreplayed ON dead_letters (consumer) WHERE replayed_at IS NULL;
