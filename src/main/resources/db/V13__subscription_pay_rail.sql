-- A subscription remembers how its customer pays. The renewal must ride the
-- same rail the shopper chose at the till: an estate subscriber's renewal is
-- a real bank charge (statement or card), never a mystery processor debit.
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS pay_rail TEXT NOT NULL DEFAULT 'psp';
