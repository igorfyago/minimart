-- V12 · the settlement half of a refund case, built before the rail is.
--
-- V11 recorded the debt and stopped, which left the table with a future
-- defect already scheduled: when minipay grows a refund rail, whatever
-- settles a case needs something to compare-and-set on, or a rail and a
-- human settling the same row both proceed · the exact double-refund the
-- table exists to prevent, deferred to the day it is hardest to notice.
--
-- So the lifecycle is closed now. A settler moves due to settled with
--
--     UPDATE refund_cases SET status = 'settled', settled_at = now(),
--     settled_by = ? WHERE order_id = ? AND status = 'due'
--
-- and an update count of 0 means somebody else already did: stop. The CHECK
-- pins the vocabulary, because a status column that accepts any text is a
-- state machine with unlisted states.
--
--   refunded · returned on minimart's own ledger, in the compensating commit
--   due      · standing at the processor, waiting on a rail or a human
--   settled  · the due money moved, and the row names who moved it
--
-- (Correcting V11's comment while here: payment mode is 'wallet' or 'psp',
-- and the table journals BOTH compensation verdicts, not only unpaid debt ·
-- a row per compensated order, whichever way the money went.)

ALTER TABLE refund_cases ADD COLUMN IF NOT EXISTS settled_at TIMESTAMPTZ;
ALTER TABLE refund_cases ADD COLUMN IF NOT EXISTS settled_by TEXT;
ALTER TABLE refund_cases DROP CONSTRAINT IF EXISTS refund_cases_status_chk;
ALTER TABLE refund_cases ADD CONSTRAINT refund_cases_status_chk
    CHECK (status IN ('refunded', 'due', 'settled'));
