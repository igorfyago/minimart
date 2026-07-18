-- V8__sso_customers.sql
-- The seam between the estate's identity and the shop's customers.
-- auth.b4rruf3t.com owns who you are; minimart owns what you buy.
-- This table is the only place the two meet.
--
-- minimart's customers have always been bare numbers minted by callers
-- (the simulation's agents make their own). An SSO-linked customer is the
-- same kind of number — the difference is that only the SSO subject on
-- this row may act as it. Unlinked numbers stay exactly as open as they
-- are today, because the permissive rollout says nothing changes until
-- activation.

CREATE TABLE IF NOT EXISTS sso_customers (
    sso_sub      TEXT PRIMARY KEY,        -- the JWT sub claim, usr_...
    customer_id  BIGINT NOT NULL UNIQUE,  -- the minimart customer number
    linked_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sso_customers_customer ON sso_customers(customer_id);
