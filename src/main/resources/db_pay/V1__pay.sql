-- minipay · V1. A very small payment processor.
--
-- Same ledger engine as everything else in this universe, different books.
-- Three account roles matter:
--   source:{customer}   the funding instrument. External, so it may go negative
--                       (that is what "charging a card" means).
--   holds:{merchant}    authorised but not yet captured. Money in limbo.
--   balance:{merchant}  captured. The merchant's money.
--
-- A PaymentIntent is a tiny state machine over those movements:
--   authorise: source -> holds      (requires_capture)
--   capture:   holds  -> balance    (succeeded)
--   cancel:    holds  -> source     (canceled)

CREATE TABLE IF NOT EXISTS accounts (
    id       BIGSERIAL PRIMARY KEY,
    ref      TEXT NOT NULL UNIQUE,
    kind     TEXT NOT NULL,
    currency TEXT NOT NULL,
    balance  NUMERIC(20,8) NOT NULL DEFAULT 0,
    CONSTRAINT accounts_balance_check CHECK (kind = 'external' OR balance >= 0)
);

CREATE TABLE IF NOT EXISTS transactions (
    id          UUID PRIMARY KEY,
    kind        TEXT NOT NULL,
    business_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS entries (
    id          BIGSERIAL PRIMARY KEY,
    tx_id       UUID   NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    account_id  BIGINT NOT NULL REFERENCES accounts(id)     ON DELETE CASCADE,
    amount      NUMERIC(20,8) NOT NULL CHECK (amount <> 0),
    business_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_entries_account ON entries(account_id, id);

CREATE OR REPLACE FUNCTION ledger_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'the ledger is append-only'; END
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS entries_immutable ON entries;
CREATE TRIGGER entries_immutable BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();

CREATE TABLE IF NOT EXISTS payment_intents (
    id           TEXT PRIMARY KEY,              -- pi_...
    amount       NUMERIC(20,8) NOT NULL CHECK (amount > 0),
    currency     TEXT NOT NULL,
    customer_ref TEXT NOT NULL,
    merchant_ref TEXT NOT NULL,
    status       TEXT NOT NULL,                 -- requires_capture | succeeded | canceled
    business_at  TIMESTAMPTZ NOT NULL,
    settled_at   TIMESTAMPTZ
);

-- THE IDEMPOTENCY LAYER. Stripe's most quietly important feature: a replayed
-- request does not merely refuse to double-charge, it returns the byte-identical
-- response the first call produced. The fingerprint guards against the same key
-- being reused for a DIFFERENT request, which is a caller bug worth shouting about.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    key         TEXT PRIMARY KEY,
    fingerprint TEXT NOT NULL,
    state       TEXT NOT NULL,                  -- in_flight | complete
    status_code INT,
    body        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
