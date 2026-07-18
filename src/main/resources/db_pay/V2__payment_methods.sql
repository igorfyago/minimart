-- THE PROCESSOR LEARNS THAT NOT ALL MONEY ARRIVES THE SAME WAY.
--
-- Until now every payment was settled inside minipay's own ledger, which made
-- it a merchant's wallet with good manners rather than a processor. A processor
-- is defined by the opposite property: one API in front of several RAILS, so a
-- merchant integrates once and never learns that a card, a wallet and a bank
-- debit reach completely different places.
--
-- Three rails, and the distinction between the first two is a real one with a
-- real name:
--
--   ON_US    · the card was issued by minibank, which is in the same group as
--              this processor. The authorisation goes straight to that issuer
--              and never touches a network. Genuinely cheaper and faster, and
--              genuinely what an acquirer does when it happens to have issued
--              the card. What it must NEVER become is a database read: on-us
--              means skipping the NETWORK, not skipping the boundary.
--   EXTERNAL · the card was issued by somebody else. It goes to a foreign
--              issuer that this system does not own and cannot inspect, which
--              is the case that makes "external customers can buy from us" true.
--   WALLET   · a balance held here. No issuer at all.
--
-- The merchant chooses a payment method. It does not choose a rail, and it is
-- not told which one ran.

CREATE TABLE IF NOT EXISTS customers (
    -- cus_ prefixed, because a processor's customer is not the merchant's
    -- customer and confusing the two is how a refund reaches the wrong person
    id          TEXT PRIMARY KEY,
    -- what the MERCHANT calls this person, so the merchant can find them again.
    -- Deliberately opaque here: minipay never learns a name, an email, or
    -- anything a person could be identified by.
    merchant_ref TEXT NOT NULL,
    merchant     TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customers_one_per_merchant_ref UNIQUE (merchant, merchant_ref)
);

CREATE TABLE IF NOT EXISTS payment_methods (
    id           TEXT PRIMARY KEY,               -- pm_...
    customer_id  TEXT NOT NULL REFERENCES customers(id),
    -- card | wallet
    type         TEXT NOT NULL,
    rail         TEXT NOT NULL,
    -- The issuer's own token for a card. minipay stores it and never inspects
    -- it: it means nothing here, which is exactly the property that makes it
    -- safe to hold. There is no PAN in this system and there never will be.
    instrument   TEXT,
    -- what a receipt may show
    brand_label  TEXT,
    last4        TEXT,
    status       TEXT NOT NULL DEFAULT 'active',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pm_type_ck CHECK (type IN ('card','wallet')),
    CONSTRAINT pm_rail_ck CHECK (rail IN ('ON_US','EXTERNAL','WALLET')),
    CONSTRAINT pm_card_needs_instrument CHECK (type <> 'card' OR instrument IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS pm_by_customer ON payment_methods (customer_id);

-- Which method paid for which intent, and what the rail said.
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS payment_method TEXT;
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS rail TEXT;
-- The issuer's own reference for the authorisation. Without it a capture has
-- no way to name what it is capturing, and a processor that cannot reference
-- its own authorisations cannot settle them.
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS issuer_authorization TEXT;
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS decline_reason TEXT;

-- THE PROCESSOR'S OUTBOX.
--
-- The authorisation is synchronous because a customer is standing there and
-- somebody has to answer. Everything AFTER the answer is bookkeeping: telling
-- the merchant, telling analytics, and eventually clearing and settling. None
-- of that may block the customer, and none of it may be lost, so it goes
-- through an outbox written in the same commit as the money.
--
-- That split is the whole architecture in one sentence: SYNCHRONOUS WHERE
-- SOMEBODY IS WAITING FOR A DECISION, ASYNCHRONOUS EVERYWHERE ELSE. It is also
-- how real card systems work, where authorisation is real time and clearing is
-- a file that moves later.
CREATE TABLE IF NOT EXISTS outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT NOT NULL,
    event_key    TEXT NOT NULL UNIQUE,
    key          TEXT NOT NULL,
    payload      TEXT NOT NULL,
    business_at  TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS pay_outbox_pending ON outbox (id) WHERE published_at IS NULL;
