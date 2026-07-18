-- SETTLEMENT · the part of payments everybody forgets.
--
-- A merchant is NOT paid when the card is captured. They are paid later, in a
-- batch, NET OF FEES, and the gap between those two moments is where a
-- surprising amount of real payments engineering lives: it is why a merchant's
-- dashboard total never matches their bank statement, why refunds after a
-- payout are awkward, and why "revenue" and "money in the account" are two
-- different questions.
--
-- Modelling capture as "the merchant now has the money" is the single most
-- common way a payments simulation stops being one. It skips the fee, skips the
-- delay, and quietly asserts that a processor is a wallet.
--
-- So a capture creates a RECEIVABLE, and a settlement run turns receivables
-- into a payout.

CREATE TABLE IF NOT EXISTS settlements (
    id            TEXT PRIMARY KEY,             -- st_...
    merchant      TEXT        NOT NULL,
    currency      TEXT        NOT NULL,
    -- the business day being settled, not the day the run happened. A batch is
    -- named after the money it covers, so re-running a day is idempotent and a
    -- late arrival lands in the day it belongs to.
    business_date DATE        NOT NULL,
    gross         NUMERIC(20,2) NOT NULL,
    fee           NUMERIC(20,2) NOT NULL,
    net           NUMERIC(20,2) NOT NULL,
    item_count    INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ONE batch per merchant, currency and day. Running settlement twice must
    -- pay a merchant once, and this is what makes that true rather than hoped.
    CONSTRAINT settlement_one_per_day UNIQUE (merchant, currency, business_date),
    -- the arithmetic is a constraint, not a calculation somebody trusts
    CONSTRAINT settlement_adds_up CHECK (net = gross - fee)
);

-- Which payments a payout covered. Without this a merchant can be told the
-- total and never which sales it came from, which is the first thing they ask.
CREATE TABLE IF NOT EXISTS settlement_items (
    settlement_id     TEXT NOT NULL REFERENCES settlements(id),
    payment_intent_id TEXT NOT NULL,
    amount            NUMERIC(20,2) NOT NULL,
    fee               NUMERIC(20,2) NOT NULL,
    -- A payment may be settled ONCE. The reason this is a primary key rather
    -- than an index: paying a merchant twice for one sale is the failure this
    -- table exists to make impossible, and it must not depend on the batching
    -- query being written correctly forever.
    PRIMARY KEY (payment_intent_id)
);
CREATE INDEX IF NOT EXISTS settlement_items_by_batch ON settlement_items (settlement_id);

-- Which batch paid for this intent, if any. Denormalised on purpose: the
-- question "has this sale been paid out" is asked far more often than any other
-- and should not need a join.
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS settlement_id TEXT;
CREATE INDEX IF NOT EXISTS intents_awaiting_settlement
    ON payment_intents (merchant_ref, currency, business_at)
    WHERE status = 'succeeded' AND settlement_id IS NULL;
