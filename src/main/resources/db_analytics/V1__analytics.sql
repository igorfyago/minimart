-- ANALYTICS · a separate service, so a SEPARATE DATABASE.
--
-- Nothing in here has a foreign key into minimart. It cannot, they are
-- different Postgres databases and no query can span them. That constraint is
-- the point of the exercise: everything this service knows, it learned from an
-- event, and if an event did not carry a fact then this service does not have
-- that fact. The alternative, reaching into the producer's tables for the bits
-- the event forgot, is the single most common way a "microservice" turns out to
-- be a distributed monolith holding a shared database hostage.

-- The consumer's own idempotency. AT-LEAST-ONCE delivery means a rebalance, a
-- redeploy or a relay retry will hand over the same event again, and it must
-- land exactly once. This table lives HERE, next to the state it protects, so
-- the claim and the effect commit together.
CREATE TABLE IF NOT EXISTS handled_events (
    event_key   TEXT        NOT NULL,
    consumer    TEXT        NOT NULL,
    handled_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    business_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_key, consumer)
);

-- MRR AS A MOVEMENT LEDGER, not a stored number.
--
-- The same reasoning as the money ledger: a current balance you can overwrite
-- is a number nobody can audit. Store the MOVEMENTS and MRR becomes their sum,
-- which means every figure can be traced to the events that produced it and
-- "why did MRR drop in March" is answerable instead of a matter of opinion.
CREATE TABLE IF NOT EXISTS mrr_movements (
    id              BIGSERIAL PRIMARY KEY,
    tenant          TEXT        NOT NULL,
    subscription_id UUID        NOT NULL,
    customer_id     BIGINT      NOT NULL,
    variant_id      TEXT        NOT NULL,
    -- new | reactivation | churn | expansion | contraction
    kind            TEXT        NOT NULL,
    -- signed, and normalised to a MONTH regardless of billing interval
    amount          NUMERIC(20,4) NOT NULL,
    -- MEASURED  · read directly off an event, the strongest kind of fact
    -- DERIVED   · computed from measured facts, correct but not observed
    -- AUTHORED  · seeded or entered by hand, and never to be mistaken for either
    provenance      TEXT        NOT NULL DEFAULT 'MEASURED',
    event_key       TEXT        NOT NULL,
    business_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT mrr_kind_ck CHECK (kind IN ('new','reactivation','churn','expansion','contraction')),
    CONSTRAINT mrr_provenance_ck CHECK (provenance IN ('MEASURED','DERIVED','AUTHORED'))
);
CREATE INDEX IF NOT EXISTS mrr_by_tenant_time ON mrr_movements (tenant, business_at);
CREATE INDEX IF NOT EXISTS mrr_by_subscription ON mrr_movements (subscription_id);

-- One movement per event, forever. Belt and braces alongside handled_events:
-- even a bug in the dedup path cannot double-count revenue.
CREATE UNIQUE INDEX IF NOT EXISTS mrr_one_per_event ON mrr_movements (event_key);

-- The projection. Rebuildable from mrr_movements at any time, which is the
-- test of whether the ledger really is the source of truth.
CREATE TABLE IF NOT EXISTS subscription_state (
    subscription_id UUID PRIMARY KEY,
    tenant          TEXT        NOT NULL,
    customer_id     BIGINT      NOT NULL,
    variant_id      TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    monthly_amount  NUMERIC(20,4) NOT NULL,
    renewals        INT         NOT NULL DEFAULT 0,
    -- cash actually collected, which is NOT the same question as MRR
    collected       NUMERIC(20,4) NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ NOT NULL,
    last_event_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS sub_state_by_tenant ON subscription_state (tenant, status);
