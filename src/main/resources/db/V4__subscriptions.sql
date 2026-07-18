-- V4 · recurring billing.
--
-- The design rule that makes this small: A RENEWAL IS NOT A SPECIAL CASE, IT
-- IS AN ORDER. Every renewal goes through the same checkout the storefront
-- uses, with an order id derived from (subscription, period), so the whole
-- authorise/reserve/capture machinery and all of its idempotency come for free.
--
-- The period claim is the idempotency gate: UNIQUE(subscription_id, period_index)
-- means a scheduler that fires twice, or a driver that crashes mid-run and
-- replays, can only ever bill a period once.

CREATE TABLE IF NOT EXISTS subscriptions (
    id                   UUID PRIMARY KEY,
    tenant               TEXT   NOT NULL REFERENCES tenants(slug),
    customer_id          BIGINT NOT NULL,
    variant_id           TEXT   NOT NULL REFERENCES variants(id),
    location             TEXT   NOT NULL,
    status               TEXT   NOT NULL,          -- active | past_due | paused | canceled
    interval_days        INT    NOT NULL CHECK (interval_days > 0),
    period_index         INT    NOT NULL DEFAULT 0,
    next_renewal_at      TIMESTAMPTZ NOT NULL,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT false,
    business_at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_subs_due ON subscriptions(next_renewal_at) WHERE status IN ('active', 'past_due');

CREATE TABLE IF NOT EXISTS invoices (
    id              UUID PRIMARY KEY,
    subscription_id UUID   NOT NULL REFERENCES subscriptions(id),
    period_index    INT    NOT NULL,
    order_id        UUID,
    amount          NUMERIC(20,8) NOT NULL,
    status          TEXT   NOT NULL,               -- paid | failed
    business_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (subscription_id, period_index)         -- the gate: one bill per period, ever
);

CREATE TABLE IF NOT EXISTS dunning_attempts (
    id              BIGSERIAL PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    period_index    INT  NOT NULL,
    attempt         INT  NOT NULL,
    outcome         TEXT NOT NULL,                 -- retry_scheduled | recovered | given_up
    business_at     TIMESTAMPTZ NOT NULL
);
