-- V3 · how an order will be paid is a fact about the order, decided when it is
-- created. Inferring it later from payment_intent_id was a bug: that column is
-- only filled once authorisation SUCCEEDS, so the compensation path (authorise
-- failed, give the goods back) saw NULL and tried to refund a wallet that was
-- never charged.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_mode TEXT NOT NULL DEFAULT 'wallet';
