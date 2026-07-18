-- V7__idempotency_namespace.sql
--
-- V6 added idempotency_keys.caller and nothing ever wrote it. The namespace
-- lived only as a prefix concatenated into the key in Java, and it was
-- concatenated for keyed callers ONLY, which made it forgeable: an
-- unauthenticated caller sending Idempotency-Key "key:pk_victim:K" landed
-- inside a real merchant's namespace and could read back a stored response
-- that was never theirs.
--
-- The code now prefixes EVERY caller, anonymous included, and writes the
-- namespace into caller as well. This migration brings the rows that predate
-- that into the same world, so a caller retrying a key from last week still
-- replays instead of paying twice.
--
-- Additive only. No column is dropped, no type is changed, and the key column
-- stays the primary key, because the single-statement INSERT ... ON CONFLICT
-- (key) claim is what makes two racing retries produce exactly one payment.

-- Rows written before identity existed are the anonymous world by definition.
-- The NOT EXISTS guard is for the pathological case where somebody had already
-- sent the literal key "anon:X": moving "X" on top of it would be a primary
-- key collision, and a migration that aborts a deploy over one stale
-- debugging row is a worse outcome than one orphaned row.
UPDATE idempotency_keys k
   SET caller = 'anon', key = 'anon:' || k.key
 WHERE k.caller IS NULL
   AND k.key NOT LIKE 'key:%'
   AND NOT EXISTS (SELECT 1 FROM idempotency_keys o WHERE o.key = 'anon:' || k.key);

-- Rows already carrying a keyed prefix keep their key untouched: it is a
-- primary key and other rows may hold its stored response. Only the namespace
-- is lifted out of the string and into the column where it belongs.
UPDATE idempotency_keys
   SET caller = split_part(key, ':', 1) || ':' || split_part(key, ':', 2)
 WHERE caller IS NULL;

-- /v1/keys shows a caller their own recent activity and nobody else's.
CREATE INDEX IF NOT EXISTS idx_idempotency_caller ON idempotency_keys(caller, created_at DESC);
