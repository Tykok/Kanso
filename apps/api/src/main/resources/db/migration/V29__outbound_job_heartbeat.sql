-- Telling "the worker died" from "the worker is slow".
--
-- `outbound_jobs` has carried exactly one piece of evidence about an in-flight push
-- since V2: `locked_at`, stamped once when the job was claimed and never touched
-- again. The stuck-job sweep reads it as an age and reclaims anything held longer
-- than `stuck-job-timeout`. So what the sweep actually measures is "how long has this
-- push been running", and what it concludes from it is "the process holding it is
-- gone". Those are two different questions, and a push that is merely slow answers
-- the first one exactly like a process that died mid-flight.
--
-- The consequence is a replay, not a delay: the sweep returns the row to the queue, a
-- drain claims it, and the same operation goes out a second time while the first one
-- is still in the air. Notion has hidden this so far, by accident — a second `upsert`
-- writes the same state, and `archive` on an already-archived page is a no-op — and
-- neither property is owed to us by the next destination. A webhook POST is a delivery
-- somebody counts on the far side, and a comment pushed twice to GitHub is two
-- comments.
--
-- Lengthening the timeout does not close this, it moves it. Any value is a guess at
-- the slowest legitimate push to the slowest remote Kanso will ever talk to; the guess
-- is wrong in one of two directions, and being wrong upwards means a genuinely dead
-- worker's jobs sit undrained for however long the guess was.
--
-- So the row gains a second timestamp, and the difference between the two is that this
-- one is *refreshed*. `locked_at` keeps meaning "when this push started";
-- `heartbeat_at` means "when the process holding it was last known to be alive", which
-- is what the sweep needed all along. `OutboundWorker` rewrites it every few seconds
-- for as long as a push is in flight, so a slow push keeps its lock for as long as it
-- needs and a dead one stops proving anything within one heartbeat interval. The
-- timeout stops being a guess about somebody else's remote and becomes a count of
-- consecutive missed heartbeats — a question about this process, which this process is
-- able to answer.
--
-- Two columns rather than one refreshed in place. Rewriting `locked_at` would need no
-- migration at all and would cost the only record of how long a push has actually been
-- running — the first number a human debugging a wedged queue reads. "Held forty
-- minutes, still beating" and "claimed four seconds ago" are precisely the two shapes
-- worth telling apart, and one column cannot say both.
--
-- The other shape that genuinely answers was a session-level advisory lock
-- (`pg_advisory_lock` on the job id) held for the push's duration: liveness would then
-- be Postgres's own bookkeeping, a dead connection releases its locks, and there would
-- be no timeout left to tune. It was rejected on what it costs the connection pool. A
-- session lock lives on one physical connection, so holding it across the remote call
-- means pinning that connection for the whole round trip — and `OutboundWorker` is
-- built specifically not to do that: "holding a database connection across a round trip
-- of hundreds of milliseconds would exhaust the pool long before the rate limit". With
-- `maximum-pool-size: 10`, one pinned connection per in-flight push is survivable while
-- there is one drain per destination and stops being survivable the day a destination
-- drains in parallel. It also needs the *same* connection for the take and the release,
-- which a pooled `JdbcClient` does not promise, so it would arrive with connection
-- handling by hand wrapped around every push. A heartbeat costs one short statement
-- every few seconds and holds nothing.

ALTER TABLE outbound_jobs ADD COLUMN heartbeat_at TIMESTAMPTZ;

-- A row already in flight when this migration lands was claimed by a build that had
-- nothing to beat with. Seeding from `locked_at` leaves it with exactly the behaviour
-- it has today — reclaimed on the age of its lock — rather than a NULL the sweep would
-- have to interpret.
UPDATE outbound_jobs SET heartbeat_at = locked_at WHERE status = 'running';

-- ---------------------------------------------------------------------------
-- The sweep's predicate moved, so the index it reads moves with it.
--
-- `COALESCE` rather than the bare column, and the fallback direction is the point: a
-- 'running' row with no heartbeat is one whose holder never got as far as beating, and
-- reading that as "never seen alive" keeps it reclaimable. The other reading follows
-- from NULL comparing false against everything — the row becomes invisible to the sweep
-- and stays 'running' forever, holding its entity's pending slot. An immortal job is
-- the worse of the two failures by a distance, and it is the silent one.
-- ---------------------------------------------------------------------------
DROP INDEX outbound_jobs_stuck;
CREATE INDEX outbound_jobs_stuck
  ON outbound_jobs ((COALESCE(heartbeat_at, locked_at))) WHERE status = 'running';
