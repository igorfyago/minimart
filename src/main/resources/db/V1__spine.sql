-- V1 · THE SPINE.
--
-- Two ledgers share one set of tables. Money accounts hold SIMEUR. Stock
-- accounts hold UNIT:{variantId}, where the "balance" is whole units in a
-- warehouse. That single move is the thesis of this system: overselling
-- becomes structurally impossible, because it is the same non-negative CHECK
-- and the same ordered row locks that stop a customer overdrawing.
--
-- TIME: business_at is the only timestamp any business logic may read. It is
-- a parameter, never now(). created_at exists purely as an audit trail. Under
-- a compressed simulation clock a year passes in minutes, and nothing that
-- reads business_at can tell the difference.

CREATE TABLE IF NOT EXISTS accounts (
    id       BIGSERIAL PRIMARY KEY,
    ref      TEXT NOT NULL UNIQUE,          -- natural key: wallet:cust:42, stock:onhand:MAD:v-mots
    kind     TEXT NOT NULL,                 -- customer | external | holds | stock
    currency TEXT NOT NULL,                 -- SIMEUR, or UNIT:{variantId}
    balance  NUMERIC(20,8) NOT NULL DEFAULT 0,
    -- external accounts are where value enters the world and may go negative.
    -- everything else, money or goods, may never go below zero.
    CONSTRAINT accounts_balance_check CHECK (kind = 'external' OR balance >= 0)
);

CREATE TABLE IF NOT EXISTS transactions (
    id          UUID PRIMARY KEY,           -- caller-minted: this IS the idempotency gate
    kind        TEXT NOT NULL,
    business_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()   -- audit only, never filtered on
);

CREATE TABLE IF NOT EXISTS entries (
    id          BIGSERIAL PRIMARY KEY,
    tx_id       UUID   NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    account_id  BIGINT NOT NULL REFERENCES accounts(id)     ON DELETE CASCADE,
    amount      NUMERIC(20,8) NOT NULL CHECK (amount <> 0),
    business_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_entries_account ON entries(account_id, id);
CREATE INDEX IF NOT EXISTS idx_entries_tx ON entries(tx_id);

-- the ledger is append-only, enforced by the database rather than by manners
CREATE OR REPLACE FUNCTION ledger_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'the ledger is append-only'; END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS entries_immutable ON entries;
CREATE TRIGGER entries_immutable BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();

DROP TRIGGER IF EXISTS transactions_immutable ON transactions;
CREATE TRIGGER transactions_immutable BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();

-- ------------------------------------------------------------------ catalog

CREATE TABLE IF NOT EXISTS tenants (
    slug          TEXT PRIMARY KEY,
    base_currency TEXT NOT NULL DEFAULT 'SIMEUR'
);

CREATE TABLE IF NOT EXISTS variants (
    id     TEXT PRIMARY KEY,
    tenant TEXT NOT NULL REFERENCES tenants(slug),
    title  TEXT NOT NULL,
    price  NUMERIC(20,8) NOT NULL CHECK (price >= 0)
);

-- ------------------------------------------------------------------- orders

CREATE TABLE IF NOT EXISTS orders (
    id          UUID PRIMARY KEY,           -- caller-minted, same id as the ledger tx
    tenant      TEXT   NOT NULL REFERENCES tenants(slug),
    customer_id BIGINT NOT NULL,
    variant_id  TEXT   NOT NULL REFERENCES variants(id),
    location    TEXT   NOT NULL,
    qty         BIGINT NOT NULL CHECK (qty > 0),
    amount      NUMERIC(20,8) NOT NULL,
    state       TEXT   NOT NULL,            -- reserved | fulfilled | aborted
    business_at TIMESTAMPTZ NOT NULL
);

-- the reservation row is the serialisation point between capture and expiry.
-- Derived transaction ids alone cannot referee that race, because the reserved
-- account is pooled per (location, variant): one reservation's release could
-- otherwise fund another's capture and the books would still balance.
CREATE TABLE IF NOT EXISTS reservations (
    id          UUID PRIMARY KEY,
    order_id    UUID   NOT NULL REFERENCES orders(id),
    variant_id  TEXT   NOT NULL REFERENCES variants(id),
    location    TEXT   NOT NULL,
    qty         BIGINT NOT NULL CHECK (qty > 0),
    state       TEXT   NOT NULL,            -- held | captured | released
    expires_at  TIMESTAMPTZ NOT NULL,
    business_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reservations_expiry ON reservations(expires_at) WHERE state = 'held';
