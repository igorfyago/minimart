-- WHAT HAPPENS TO THE MONEY THAT PREDATES A NEW MODEL.
--
-- Found in production, on the first settlement run after capture started
-- creating a RECEIVABLE instead of paying the merchant directly. Settlement
-- gathered every succeeded, unsettled payment for the day and tried to draw the
-- total from the receivable account. It failed, because payments captured under
-- the OLD model had credited the merchant's balance directly and had never
-- posted a receivable at all. The ledger refused to let the account go negative,
-- which is exactly what it is for, and settlement stopped entirely.
--
-- The bug is not really in settlement. It is that introducing a new money model
-- left an unanswered question, and the unanswered question became a crash: what
-- is the status of a payment that completed before the concept existed?
--
-- The answer here is explicit rather than clever. A payment either posted a
-- receivable or it did not, the flag says which, and settlement only ever
-- settles the ones that did. Nothing is silently swept up and nothing is
-- silently dropped: the ones that predate the model are countable, and the
-- count is what makes them a known quantity rather than a mystery in a total.
ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS receivable_posted BOOLEAN NOT NULL DEFAULT FALSE;

-- Everything ALREADY settled clearly needs no receivable: it was paid out under
-- the old model and is finished. Marking it keeps the audit honest by excluding
-- it from "waiting for a receivable" rather than from "waiting for money".
UPDATE payment_intents SET receivable_posted = TRUE WHERE settlement_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS intents_settleable
    ON payment_intents (merchant_ref, currency, business_at)
    WHERE status = 'succeeded' AND settlement_id IS NULL AND receivable_posted;
