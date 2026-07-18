-- V2 · the store stops being its own payment processor.
--
-- A shop does not hold your wallet; a processor charges your instrument. So an
-- order now carries the id of a PaymentIntent living in another service, in
-- another database, reachable only over HTTP.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_intent_id TEXT;
