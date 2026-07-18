-- CLEARING · the message that turns an authorisation into money owed.
--
-- Authorisation is real time, because a customer is standing there and somebody
-- has to answer. Clearing is not: it is the acquirer telling the issuer, later
-- and in bulk, which of those authorisations it actually completed, and it is
-- where the two banks work out what one owes the other.
--
-- That split is the whole architecture of card payments in one sentence, and it
-- is the reason this system uses both a synchronous call and a broker:
--
--   SYNCHRONOUS where somebody is waiting for a decision.
--   ASYNCHRONOUS everywhere else.
--
-- A system that clears synchronously has not made a mistake exactly, but it has
-- built something that cannot survive its issuer being slow, cannot batch, and
-- has nowhere to put interchange. Real networks move a file. This moves a batch.
--
-- INTERCHANGE is the piece that makes clearing worth modelling at all. The
-- issuer does not hand over the full amount: it keeps a fee for having lent the
-- customer the money and carried the risk, and the acquirer receives the rest.
-- So gross, interchange and net are three different numbers that must agree
-- across two banks who share no database, and proving they agree is the whole
-- point of the exercise.

CREATE TABLE IF NOT EXISTS clearing_batches (
    id             TEXT PRIMARY KEY,            -- cb_...
    issuer         TEXT        NOT NULL,
    currency       TEXT        NOT NULL,
    business_date  DATE        NOT NULL,
    -- what the merchant charged
    gross          NUMERIC(20,2) NOT NULL,
    -- what the issuer keeps for carrying the risk
    interchange    NUMERIC(20,2) NOT NULL,
    -- what the acquirer expects to receive
    net            NUMERIC(20,2) NOT NULL,
    item_count     INT         NOT NULL,
    -- built | submitted | acknowledged | rejected
    state          TEXT        NOT NULL DEFAULT 'built',
    -- what the ISSUER said it computed. Stored separately from our own numbers
    -- on purpose: the entire value of clearing is that two parties compute the
    -- same total independently and then compare, and overwriting ours with
    -- theirs would destroy the only evidence that they ever agreed.
    issuer_net     NUMERIC(20,2),
    issuer_ref     TEXT,
    business_at    TIMESTAMPTZ NOT NULL,
    submitted_at   TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    CONSTRAINT clearing_state_ck CHECK (state IN ('built','submitted','acknowledged','rejected')),
    CONSTRAINT clearing_adds_up CHECK (net = gross - interchange),
    -- one batch per issuer, currency and day, so a re-run clears once
    CONSTRAINT clearing_one_per_day UNIQUE (issuer, currency, business_date)
);

CREATE TABLE IF NOT EXISTS clearing_items (
    batch_id          TEXT NOT NULL REFERENCES clearing_batches(id),
    payment_intent_id TEXT NOT NULL,
    -- the issuer's own reference for the authorisation being cleared. Without
    -- it the issuer cannot match our line to its hold, and a clearing message
    -- that cannot be matched is a dispute rather than a payment.
    authorization_ref TEXT NOT NULL,
    amount            NUMERIC(20,2) NOT NULL,
    interchange       NUMERIC(20,2) NOT NULL,
    -- a payment clears ONCE, and the primary key is what makes that true rather
    -- than the batching query being correct forever
    PRIMARY KEY (payment_intent_id)
);
CREATE INDEX IF NOT EXISTS clearing_items_by_batch ON clearing_items (batch_id);

ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS clearing_batch TEXT;
CREATE INDEX IF NOT EXISTS intents_awaiting_clearing
    ON payment_intents (rail, currency, business_at)
    WHERE status = 'succeeded' AND clearing_batch IS NULL;
