-- V9 · the outbox's missing twin: a durable record that a REMOTE step was tried.
--
-- The outbox solved half of the two-writes problem. An event and the money it
-- announces commit together, and a relay ships the event afterwards, so nothing
-- is ever lost between Postgres and Kafka.
--
-- The other half was never solved. Every call minimart makes to minipay ends up
-- as a boolean in a local variable: void the hold, capture the money, authorise
-- the card. If the process dies, or the answer is a timeout, or the boolean is
-- simply discarded, NOTHING ANYWHERE REMEMBERS THAT THE STEP WAS ATTEMPTED. The
-- goods move on minimart's side and the money does not move on minipay's, and
-- both services' own audits still pass, because each ledger balances internally.
--
-- This table is that memory. A row is written BEFORE the request goes out and
-- updated when the answer comes back, so a step that never got an answer is
-- distinguishable from one that got a "no". That distinction is the point:
-- a read timeout is not a decline, it is a question, and the reconciler is what
-- eventually answers it.

CREATE TABLE IF NOT EXISTS remote_steps (
    order_id    UUID NOT NULL,
    action      TEXT NOT NULL,          -- authorize | capture | cancel | fulfil
    intent_id   TEXT NOT NULL,
    state       TEXT NOT NULL,          -- in_flight | ok | failed | unknown
    detail      TEXT,
    business_at TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, action)
);

-- The only question ever asked of this table in anger is "what did not finish?",
-- so the index answers exactly that and nothing else. A completed step is
-- history; an unfinished one is work.
CREATE INDEX IF NOT EXISTS idx_remote_steps_unsettled
    ON remote_steps(order_id) WHERE state <> 'ok';
