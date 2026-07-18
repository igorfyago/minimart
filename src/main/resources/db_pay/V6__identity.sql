-- V6__identity.sql
-- Two identity paths, one table each, because this service is an acquirer:
-- its callers are merchants and platforms, not customers.
--
-- sso_accounts: estate users (auth.b4rruf3t.com subjects) linked to the
-- processor's own cus_ records. usr_... is who the person is; cus_... is
-- what the processor charges. The mapping lands here and nowhere else.
--
-- api_keys: the Stripe role. External software (a platform, an NPC's
-- containment shell) authenticates as ITSELF — a key is bound to one
-- merchant at issue and can never widen. Only the hash of the secret is
-- stored; a leaked row is not a leaked key.

CREATE TABLE IF NOT EXISTS sso_accounts (
    sso_sub      TEXT PRIMARY KEY,        -- the JWT sub claim, usr_...
    customer_id  TEXT NOT NULL UNIQUE,    -- the processor's cus_... record
    linked_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS api_keys (
    key_id       TEXT PRIMARY KEY,        -- pk_... (public half, safe for logs)
    key_hash     TEXT NOT NULL,           -- sha-256 of the sk_... secret half
    merchant     TEXT NOT NULL,           -- the ONE merchant this key is
    owner_name   TEXT NOT NULL,           -- "Agentic Visitors Inc", for humans
    scope        TEXT NOT NULL,           -- 'charge', 'read', 'full'
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_api_keys_merchant ON api_keys(merchant);

-- Idempotency keys become per-caller: two different callers sending the
-- same key string must not collide, or one NPC silently replays another's
-- payment. NULL caller = the anonymous, pre-identity world; existing rows
-- stay valid under it, so the ledger's history is untouched.
ALTER TABLE idempotency_keys ADD COLUMN IF NOT EXISTS caller TEXT;
