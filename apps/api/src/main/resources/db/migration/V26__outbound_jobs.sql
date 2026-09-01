-- `sync_jobs` was never Notion-specific in anything but its name. What V2 built is
-- a general outbox: priorities that encode a dependency order, exponential backoff
-- with a per-job `next_attempt_at`, `FOR UPDATE SKIP LOCKED` claiming, a lock that a
-- supervisor can reclaim from a dead worker, and a partial unique index that
-- collapses repeated edits of one row into one queued push. Webhooks and the GitHub
-- push want all of it and none of Notion.
--
-- Generalised now rather than when the second consumer lands, because a consumer
-- written against a Notion-shaped queue gets written Notion-shaped, and the cost of
-- that is not a bad name — it is a second queue, with its own retry policy, its own
-- stuck-job detector and its own way of being behind.

-- ---------------------------------------------------------------------------
-- Renamed, never recreated.
--
-- The queue is not empty in a running instance: a pending row is a change somebody
-- made that has not left the building yet, a failed row is what the inbox's failures
-- tab is showing right now, and a running row is a push that may be in flight. A
-- rename carries every one of them across with its status, its attempt count, its
-- backoff and its lock intact — so nothing is lost, and nothing already done is
-- replayed. Postgres does not follow the table rename into the objects hanging off
-- it, so the sequence, the constraints, the indexes and the trigger are renamed by
-- hand: a schema reached by migration has to be indistinguishable from one built
-- from scratch, or the next migration finds a name that is only there sometimes.
-- ---------------------------------------------------------------------------
ALTER TABLE sync_jobs RENAME TO outbound_jobs;
ALTER SEQUENCE sync_jobs_id_seq RENAME TO outbound_jobs_id_seq;

ALTER TABLE outbound_jobs RENAME CONSTRAINT sync_jobs_pkey         TO outbound_jobs_pkey;
ALTER TABLE outbound_jobs RENAME CONSTRAINT sync_jobs_status_chk    TO outbound_jobs_status_chk;
ALTER TABLE outbound_jobs RENAME CONSTRAINT sync_jobs_entity_chk    TO outbound_jobs_entity_chk;
ALTER TABLE outbound_jobs RENAME CONSTRAINT sync_jobs_operation_chk TO outbound_jobs_operation_chk;

ALTER TRIGGER sync_jobs_set_updated_at ON outbound_jobs RENAME TO outbound_jobs_set_updated_at;

-- ---------------------------------------------------------------------------
-- The discriminator is two columns, not one compound kind.
--
-- `entity_type` answers "which Kanso thing" and `destination` answers "which system
-- it is going to". They are two axes and they are read by two different pieces of
-- code: the worker dispatches on the destination to pick a handler, and the inbox's
-- failures tab joins on the entity type to put a title and a `KAN-12` on the row. A
-- single 'notion.ticket' column would force one of those two to answer by string
-- prefix — and the failures tab is the one that would lose, because "name the thing
-- this failure is about" is destination-independent and should stay a join.
--
-- The pair is not a full cross product: nothing will ever push a doc to GitHub. That
-- is left to the handler, which declares what it accepts, rather than to a CHECK
-- enumerating legal pairs — the enumeration is the compound kind again, wearing a
-- constraint.
--
-- 'notion' as a default only long enough to backfill. Notion is the only consumer
-- that has ever written to this table, so every row in it is a Notion job by
-- construction and the backfill needs no guesswork. The default is dropped
-- immediately after so the next consumer has to say where its work is going instead
-- of inheriting an answer.
-- ---------------------------------------------------------------------------
ALTER TABLE outbound_jobs ADD COLUMN destination TEXT NOT NULL DEFAULT 'notion';
ALTER TABLE outbound_jobs ALTER COLUMN destination DROP DEFAULT;

-- Closed in the database as well as in Kotlin, like every other vocabulary here. One
-- value today is the honest count: this migration adds no consumer, and the ticket
-- that adds one widens this list in its own migration.
ALTER TABLE outbound_jobs ADD CONSTRAINT outbound_jobs_destination_chk
  CHECK (destination IN ('notion'));

-- ---------------------------------------------------------------------------
-- The collapse rule, now per destination.
--
-- V2's rule was "at most one queued job per entity", and it is what keeps a
-- five-hundred-row bulk edit from queuing five hundred pushes: a push writes the
-- entity's full current state, so a second queued push for the same row is
-- redundant and the enqueue coalesces onto the existing one. Claiming a job flips it
-- to 'running', which frees the slot, so an edit made while a push is in flight is
-- still queued.
--
-- `destination` leads the index so that rule reads "one pending job per
-- (destination, thing)" and not "one pending job per thing, whoever wanted it". Left
-- out, a ticket queued for Notion would swallow the same ticket queued for GitHub
-- and one of the two would silently never happen. Every `NOT EXISTS ... other`
-- guard in the repository matches on the destination for the same reason.
-- ---------------------------------------------------------------------------
DROP INDEX sync_jobs_pending_uniq;
CREATE UNIQUE INDEX outbound_jobs_pending_uniq
  ON outbound_jobs (destination, entity_type, entity_id) WHERE status = 'pending';

-- The worker claims one destination at a time, so the scan starts there too.
-- Without it, an outage at one destination that leaves thousands of jobs backing off
-- into the future would make finding another destination's next job a walk over all
-- of them.
DROP INDEX sync_jobs_ready;
CREATE INDEX outbound_jobs_ready
  ON outbound_jobs (destination, priority, next_attempt_at, id) WHERE status = 'pending';

-- The stuck-job sweep is deliberately not per destination: a worker that died took
-- everything it held with it, whoever the work was for.
ALTER INDEX sync_jobs_stuck RENAME TO outbound_jobs_stuck;
