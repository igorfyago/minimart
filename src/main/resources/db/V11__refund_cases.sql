-- V11 · the refund case: money the shop owes back and has not yet moved.
--
-- When a shipment fails after capture, two different kinds of money exist.
-- Wallet money lives on minimart's own ledger, and the compensation refunds
-- it in the same transaction that restocks the goods, because both sides of
-- that truth are ours to write. Card money is standing at the processor, and
-- minipay has no refund rail yet, so the debt is RECORDED where a person will
-- see it instead of being wired through a hole in the wall. A liability the
-- books can name beats a payment improvised through an API that does not
-- exist.
--
-- One row per order, ever: compensation is a verdict, not a ledger of tries.
CREATE TABLE IF NOT EXISTS refund_cases (
    order_id    UUID PRIMARY KEY,
    tenant      TEXT NOT NULL,
    customer_id BIGINT NOT NULL,
    amount      NUMERIC(14,2) NOT NULL,
    mode        TEXT NOT NULL,           -- wallet · intent
    intent_id   TEXT,                    -- the capture standing at minipay, when mode says so
    -- refunded · money already returned on our own books (wallet mode)
    -- due      · owed through the processor, waiting on a rail or a human
    status      TEXT NOT NULL,
    reason      TEXT,
    opened_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_refund_cases_due ON refund_cases(status) WHERE status = 'due';
