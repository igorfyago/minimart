-- V1 · minifreight's books.
--
-- A carrier is a different trust domain from the merchant that hands it a
-- parcel, the same way a processor is a different trust domain from the shop.
-- So freight gets its own database, and the only things that cross its border
-- are HTTP calls to carriers and events on the broker. No table here is
-- visible to minimart, and no query here can reach minimart's orders: what
-- freight knows about an order is exactly what the order.fulfilled event said,
-- and nothing more.

-- One shipment per order, and the UNIQUE constraint is the idempotency
-- argument in one line: however many times the event that creates a shipment
-- is delivered, there is one row it can create.
CREATE TABLE IF NOT EXISTS shipments (
    id           UUID PRIMARY KEY,
    order_id     UUID NOT NULL UNIQUE,
    tenant       TEXT NOT NULL,
    variant      TEXT NOT NULL,
    qty          BIGINT NOT NULL,
    destination  TEXT NOT NULL,
    -- requested · labelled · in_transit · delivered · failed · stuck
    state        TEXT NOT NULL,
    carrier      TEXT,                    -- who accepted the parcel, once somebody has
    tracking_ref TEXT,                    -- the carrier's name for it, not ours
    attempts     INT NOT NULL DEFAULT 0,  -- passes that ended in 'unknown', bounded by the driver
    -- the driver's lease. Two drivers reading 'requested' at the same moment
    -- would both call the carrier, and the journal cannot referee a race it
    -- has not been told about. The claim is taken by compare-and-set on this
    -- column, in its own committed step, before anything remote happens.
    claimed_until TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_shipments_attention
    ON shipments(state) WHERE state IN ('requested', 'labelled', 'in_transit');

-- The journal of every call made to a carrier, written BEFORE the call.
--
-- The request id is minted from (shipment, carrier), so retrying the same
-- carrier for the same parcel is the same request, and the carrier's own
-- idempotency can recognise it. 'unknown' is a real state, not a failure to
-- pick one: a timeout after the request left this process means the label MAY
-- exist over there, and treating that as 'rejected' is how one parcel gets two
-- labels.
CREATE TABLE IF NOT EXISTS carrier_steps (
    id          BIGSERIAL PRIMARY KEY,
    shipment_id UUID NOT NULL,
    carrier     TEXT NOT NULL,
    request_id  TEXT NOT NULL UNIQUE,
    -- requested · accepted · rejected · unknown
    state       TEXT NOT NULL,
    detail      TEXT,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (shipment_id, carrier)
);

-- What the carrier told us happened to the parcel, deduped at the door.
--
-- Carriers redeliver webhooks, and one of ours does it on purpose. The UNIQUE
-- event key makes the second delivery a no-op here, so the shipment state
-- machine downstream never has to ask "have I seen this before".
CREATE TABLE IF NOT EXISTS tracking_events (
    id          BIGSERIAL PRIMARY KEY,
    shipment_id UUID NOT NULL,
    carrier     TEXT NOT NULL,
    status      TEXT NOT NULL,
    event_key   TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- scoped to the carrier: an event key is the CARRIER'S name for the event,
    -- and two carriers are entitled to pick the same one
    UNIQUE (carrier, event_key)
);

-- The same outbox and claim tables every service in this estate carries,
-- because freight announces and consumes on its own books. Same shape as
-- minimart's, deliberately: the shared helpers join whatever connection they
-- are handed, and these columns are the contract they expect.
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
CREATE INDEX IF NOT EXISTS idx_freight_outbox_unpublished ON outbox(id) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS handled_events (
    event_key   TEXT NOT NULL,
    consumer    TEXT NOT NULL,
    business_at TIMESTAMPTZ NOT NULL,
    handled_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_key, consumer)
);
