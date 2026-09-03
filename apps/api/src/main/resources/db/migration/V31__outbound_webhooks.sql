-- The second destination.
--
-- `V26` generalised the outbox and said the quiet part out loud: "this migration adds no
-- consumer, and the ticket that adds one widens this list in its own migration". This is
-- that ticket. What arrives here is a destination, two tables and no queue — no second
-- `next_attempt_at`, no second backoff, no second stuck-job detector. Draining, ordering,
-- retrying, backing off and giving up stay in `OutboundWorker`, where they were put
-- precisely so this file could be short.
--
-- Two changes that landed just before this one are load-bearing for it, and neither is
-- optional:
--
--   * `V29`'s heartbeat. The stuck-job sweep used to read the age of a lock, which a slow
--     push and a dead worker answer identically — so a slow push was reclaimed under
--     itself and the operation went out twice. `V29` says why that was invisible while
--     Notion was alone ("a second `upsert` writes the same state") and why it stops being
--     invisible here: "a webhook POST is a delivery somebody counts on the far side". A
--     subscriber's endpoint taking eight seconds must not become two deliveries.
--   * `KAN-57`'s per-destination clock. A webhook pass is bounded by somebody else's
--     server, and before that change a slow pass here would have held Notion's next tick
--     for its whole duration.
--
-- ---------------------------------------------------------------------------
-- Why a webhook body is thin, and what that buys.
--
-- The body Kanso signs and sends is the `KansoEvent` — entity, kind, id, team, project,
-- when — and never the ticket. That is the same decision `Events.kt` already records for
-- the realtime bus ("deliberately thin: the id and enough scope to decide whether a given
-- view cares. Receivers refetch or patch their own cache"), and it is worth more here than
-- there for three reasons that are specific to sending it off the box:
--
--   1. **Authorisation.** A URL is not a reader. A fat payload would post a ticket's title
--      and description to whatever address a configurator typed, with no access check
--      anywhere in the path — including for a private team's tickets. Thin, the subscriber
--      learns an id and must come back through `/api/tickets/:id` with an `api_tokens`
--      credential, where `TicketAccess` decides what they may see. The permission model
--      stays in one place instead of being forked into "and also whatever webhooks send".
--   2. **A delete can be delivered at all.** By the time a delete's job is drained the
--      Postgres row is gone. A fat payload would need the entity snapshotted at enqueue
--      time — which is a copy of ticket contents in the queue, and then a second copy in
--      the delivery log below. Thin, there is nothing to snapshot: the id is the message.
--   3. **The log stays cheap.** `webhook_deliveries.payload` keeps the exact bytes that
--      were signed, because a replay and an audit both need them. At roughly two hundred
--      bytes of identifiers that is affordable per delivery per subscription; the same
--      column holding a description would be ticket contents stored twice, which the
--      schema refuses everywhere else.
-- ---------------------------------------------------------------------------

-- The vocabulary widens, and the list is restated from the migration that set it.
--
-- `V26` is that migration and its list is `('notion')` — read there and nowhere else. A
-- CHECK is replaced whole, so restating it from an older file, or from memory, silently
-- drops whatever a newer one added. Postgres has no `ALTER CONSTRAINT ... ADD VALUE`
-- for a CHECK the way it does for an enum type, which is what makes this the one kind of
-- constraint whose history has to be read before it is rewritten.
ALTER TABLE outbound_jobs DROP CONSTRAINT outbound_jobs_destination_chk;
ALTER TABLE outbound_jobs ADD CONSTRAINT outbound_jobs_destination_chk
  CHECK (destination IN ('notion', 'webhook'));

-- ---------------------------------------------------------------------------
-- Who is listening.
-- ---------------------------------------------------------------------------
CREATE TABLE webhook_subscriptions (
  id           UUID PRIMARY KEY,

  -- Where to POST. **Immutable**: there is no update endpoint, and that is a decision the
  -- delivery log depends on rather than a missing feature. `webhook_deliveries` answers
  -- "who was called" by joining to this row, so an editable URL would make every historical
  -- delivery report the address that is configured *now* — an audit log that quietly
  -- rewrites itself. Changing where events go is delete and create, which also forces a new
  -- secret on the new endpoint, which is the correct thing to happen when the receiver
  -- changes.
  url          TEXT NOT NULL,

  -- What the person called it. The same argument as `api_tokens.name`, for the same screen:
  -- rows here are otherwise told apart only by a URL, and "delete the right one" is the
  -- gesture this table has to support.
  description  TEXT NOT NULL,

  -- The signing secret, **encrypted and not hashed** — the one place this schema departs
  -- from `V27`, and it departs because the credential is used in the other direction.
  --
  -- `api_tokens.hash` can be a one-way digest because the secret is *presented*: the
  -- caller holds it, Kanso receives it, and a digest is enough to recognise it. A webhook
  -- secret is never presented to Kanso. Kanso *computes* with it — an HMAC over every body
  -- it sends — so it has to get the bytes back, and no digest can give them back. That is
  -- not a weaker choice than SHA-256 for the same job; it is a different job, and the
  -- hashing option does not exist for it.
  --
  -- So the real choice was plaintext or encrypted, and the property being bought is exactly
  -- the one `V27` names: "a stolen database dump is not a set of live credentials". AES-GCM
  -- under `kanso.webhooks.signing-key` moves the material out of the dump and into the
  -- deployment's configuration, so `pg_dump` output, a leaked backup or a read-only replica
  -- yields ciphertext. GCM rather than CBC because it authenticates: a tampered row fails
  -- to decrypt loudly instead of signing with attacker-chosen bytes.
  --
  -- **What it does not buy, said plainly.** The key and the database password are, in the
  -- shipped `docker-compose.yml`, environment on the same service. An attacker who has the
  -- host has both, and this column protects nothing from them. The threat this closes is
  -- the dump that leaves without the host — which is the common one — and `WebhookSecret`
  -- repeats the caveat from the Kotlin side so nobody has to find it here.
  --
  -- Base64 of `iv || ciphertext || tag`, one string rather than three columns: the three are
  -- meaningless apart, and a row half-migrated between them would be a secret that cannot
  -- be read.
  secret_cipher TEXT NOT NULL,

  -- `kanso_whsec_AbCdEf…`, so the screen can print something beside the description and a
  -- subscriber holding a secret in their own configuration can tell which row is theirs.
  --
  -- The second derived value in this schema that is stored rather than computed on read,
  -- and for `V27`'s reason rather than a new one: it is derived from the *plaintext*, and
  -- the plaintext is not what the row keeps. Deriving it from `secret_cipher` would mean
  -- decrypting, which needs the key — so the settings screen could not label a row on an
  -- instance whose key had been rotated away, which is precisely when labelling matters.
  --
  -- Six characters past a distinct marker. `ApiTokenSecret.MARKER` reserved the distinction
  -- in advance: "a webhook signing key (KAN-17) is also a `kanso_…` string and must not be
  -- confusable with a credential that acts as a person". `kanso_pat_` authenticates as
  -- somebody; `kanso_whsec_` authenticates nothing and verifies a body.
  secret_prefix TEXT NOT NULL,

  -- Which changes this subscriber wants, as `OutboundEntityType.wire` values.
  --
  -- Reusing the outbox's own axis rather than inventing an event vocabulary. A second list
  -- of nouns — 'ticket.created', 'issue', 'card' — would be a second answer to "what kinds
  -- of thing does Kanso have", free to disagree with the first, and the disagreement would
  -- surface as a subscription that silently matches nothing. The queue already carries
  -- `entity_type` on every row, so this filter is a containment test against a column that
  -- is there anyway.
  --
  -- A filter and not a convenience: it is the first defence against the failure the ticket
  -- names. A subscriber who wants team changes and is sent every ticket edit in the
  -- instance is a subscriber who will be sent a bulk edit of five hundred rows.
  entities     TEXT[] NOT NULL,

  -- Null means the whole instance; a team id narrows it to that team's changes.
  --
  -- Nullable rather than a second boolean beside it, because "all teams" and "this team"
  -- are one question at two widths — the shape `InstanceRole` already argues for over "a
  -- `read_only BOOLEAN` beside this enum". The cascade is not housekeeping: a subscription
  -- scoped to a deleted team is a subscription whose filter can never match again.
  team_id      UUID REFERENCES teams(id) ON DELETE CASCADE,

  -- Who set it up, kept for the settings screen and lost rather than blocking a deletion.
  -- `SET NULL` and not `CASCADE`, unlike `api_tokens.user_id`: a token *is* its owner's and
  -- must die with the account, while a subscription is the instance's configuration and an
  -- integration must not stop the day the admin who added it leaves.
  created_by   UUID REFERENCES users(id) ON DELETE SET NULL,

  -- Set when Kanso stops trying, and it is what makes a dead subscription visible instead
  -- of merely quiet. Null is healthy; anything else is both the reason and the off switch —
  -- one column rather than an `enabled BOOLEAN` that could disagree with the reason beside
  -- it about whether this row is being delivered to.
  disabled_reason TEXT,

  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT webhook_subscriptions_desc_chk CHECK (length(btrim(description)) BETWEEN 1 AND 80),

  -- https only, refused at the database as well as in Kotlin.
  --
  -- The body carries a signature and no secret, so plaintext would not leak the key — but
  -- it would leak which tickets exist and when they move to anybody on the path, and it
  -- would let that same party rewrite the body. The signature makes tampering *detectable*
  -- by a subscriber who checks; it does nothing for one who does not, and TLS does not
  -- depend on the subscriber getting their verification right.
  --
  -- The one exception is a loopback address, because that is how this gets developed and
  -- tested against a real receiver, and a loopback URL has no path to be on. `%` in a
  -- `LIKE` is a wildcard, so `http://localhost%` also admits `http://localhost.evil.com` —
  -- hence the explicit `/` or `:` after the host, which nothing can extend past.
  CONSTRAINT webhook_subscriptions_url_chk CHECK (
    url LIKE 'https://%'
    OR url LIKE 'http://localhost:%' OR url LIKE 'http://localhost/'
    OR url LIKE 'http://127.0.0.1:%' OR url LIKE 'http://127.0.0.1/'
  ),

  -- Two conditions, for the reason `api_tokens_scopes_chk` gives at length: containment
  -- alone accepts the empty array, and a subscription matching nothing is not a narrower
  -- subscription but a broken one. The upper bound is the size of `OutboundEntityType`.
  --
  -- Distinctness is again not asserted and again does not need to be: the column is read
  -- into a Kotlin `Set`, so a repeat collapses before anything can act on it. The database
  -- closes the vocabulary; the set-ness is Kotlin's.
  CONSTRAINT webhook_subscriptions_entities_chk CHECK (
    entities <@ ARRAY['team', 'project', 'ticket', 'doc']::TEXT[]
    AND cardinality(entities) BETWEEN 1 AND 4
  )
);

-- The fan-out's only read, and it runs once per drained job: every live subscription whose
-- filter might match. Partial on the healthy rows because a disabled subscription is never
-- a candidate, and the index should not carry the ones that have been given up on.
CREATE INDEX webhook_subscriptions_live_idx
  ON webhook_subscriptions (team_id) WHERE disabled_reason IS NULL;

CREATE TRIGGER webhook_subscriptions_set_updated_at
  BEFORE UPDATE ON webhook_subscriptions
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- What was actually sent.
--
-- This table is two things at once and both are needed, which is why it is one table:
-- the journal the ticket asks for, and the **ledger that makes a shared retry safe**.
--
-- The second is the one worth explaining. An outbox job is one entity's change; a webhook
-- is one delivery to one subscription; and there are N subscriptions. The queue's
-- discriminator is `(destination, entity_type, entity_id)` and has no room for a
-- subscription, so a single job fans out to N POSTs and the worker's retry retries *the
-- job*. Without a per-subscription record of what already succeeded, a retry caused by one
-- dead endpoint would re-POST to the three that answered 200 — the duplicate delivery
-- `V29` exists to prevent, arriving from the other side.
--
-- So a row here is created before the POST and keyed uniquely on `(job_id,
-- subscription_id)`. The handler reads it back on every attempt and skips whatever is
-- already 'delivered'. Attempts accumulate on the row, so the log answers "how many tries"
-- per subscriber rather than per job.
--
-- **What this does not fix, and what the queue cannot express.** Attempts and backoff are
-- the *job's*, so they are shared across the fan-out: two subscriptions that both failed on
-- one pass retry together on the job's schedule, and when the job exhausts `max-attempts`
-- both stop. A subscription that succeeded is never re-sent and never affected. Expressing a
-- genuinely per-subscription backoff would need a third discriminator column on
-- `outbound_jobs` and a matching change to every `NOT EXISTS ... other` guard in
-- `OutboundJobRepository` — a change to the queue Notion shares, to buy an independent
-- retry schedule for the rare case of two subscribers failing at once. That trade was
-- refused: the shared thing is a schedule, never a duplicate, and the ticket's instruction
-- was to reuse the file rather than widen it.
-- ---------------------------------------------------------------------------
CREATE TABLE webhook_deliveries (
  id              UUID PRIMARY KEY,

  subscription_id UUID NOT NULL REFERENCES webhook_subscriptions(id) ON DELETE CASCADE,

  -- The queue row this came from, and deliberately **not** a foreign key.
  --
  -- `OutboundJobRepository` deletes job rows outright in three places — a superseded retry,
  -- a superseded defer, the sweep's first statement — so a real reference would either
  -- cascade the journal away when the queue tidied up, or refuse the tidying. The join is
  -- for debugging a wedged queue while the job still exists; the delivery is the record
  -- afterwards and has to outlive it.
  --
  -- A replay carries one too, because a replay goes through the queue like everything else
  -- — that is what buys it the retry, the backoff and the rate limit rather than a second
  -- copy of all three. `OutboundJobRepository.enqueueQuiet` is how it gets one: it returns
  -- the id of the pending job for the entity, creating one only if there is none, and
  -- never rewriting what an already-queued job was going to say.
  job_id          BIGINT,

  -- Copied rather than joined, because the entity may be gone: this is a log, and "which
  -- ticket was this about" has to still answer after a delete is delivered. `entity_id` is
  -- likewise not a foreign key for the same reason.
  entity_type     TEXT NOT NULL,
  entity_id       UUID NOT NULL,

  -- The exact bytes that were signed, which is what makes both of this table's jobs
  -- possible: a replay re-sends them, and an audit can recompute the HMAC over them. Thin
  -- by construction — see the header — so this is identifiers and not ticket contents.
  --
  -- **The handler signs this column and never `outbound_jobs.payload`**, which is what
  -- keeps a replay honest. A replay is the *old* event sent again; the job it rides may
  -- meanwhile be carrying a newer one, since an enqueue for an entity already queued
  -- coalesces onto it. Reading the body from the delivery row means each row sends what it
  -- was created to send, and a replay that happens to share a job with a fresh change
  -- results in two deliveries saying two different true things rather than one saying the
  -- wrong one.
  payload         TEXT NOT NULL,

  status          TEXT NOT NULL,
  attempts        INT NOT NULL DEFAULT 0,

  -- The receiver's HTTP status, null when the request never got an answer — a timeout, a
  -- refused connection, DNS. Two different facts and the null tells them apart; `error`
  -- carries the sentence in either case.
  response_status INT,
  error           TEXT,

  -- A replay is a **new row**, and this points at the delivery somebody asked to repeat.
  --
  -- The alternative — another attempt on the original row — was rejected because it
  -- destroys the thing the log is for. A delivery row is the record of one HTTP request
  -- that actually happened, at a time, with an outcome; a replay is a different request,
  -- made later, by a person, and *literally different bytes on the wire* since the
  -- signature covers a fresh timestamp. Folding it into the original would overwrite
  -- `response_status` and `attempts` for the failure that prompted it, so the evidence
  -- would disappear at the moment somebody started acting on it.
  --
  -- `SET NULL` rather than cascade: losing the pointer is better than losing the replay's
  -- own record of having happened.
  replay_of       UUID REFERENCES webhook_deliveries(id) ON DELETE SET NULL,

  -- Who asked for the replay. Null for the automatic deliveries, which is every row that
  -- is not a replay — so this column and `replay_of` are null together and say the same
  -- thing from two sides: nobody asked for this one, the queue produced it.
  requested_by    UUID REFERENCES users(id) ON DELETE SET NULL,

  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- When a 2xx came back. Null while pending and after a failure, so "delivered, and when"
  -- is one column rather than a status that has to be cross-read with a timestamp.
  delivered_at    TIMESTAMPTZ,

  CONSTRAINT webhook_deliveries_status_chk CHECK (status IN ('pending', 'delivered', 'failed')),

  CONSTRAINT webhook_deliveries_entity_chk
    CHECK (entity_type IN ('team', 'project', 'ticket', 'doc'))
);

-- The ledger's uniqueness, and the reason it is partial.
--
-- One automatic delivery per (job, subscription): this is what the handler's "have I
-- already sent this one" read probes, and what stops a second pass over the same job from
-- inserting a second row instead of finding the first. Replays are excluded because there
-- may be many of them for the same job and subscription — that is what asking again means
-- — and they carry a null `job_id` anyway, which this index would not constrain.
CREATE UNIQUE INDEX webhook_deliveries_job_uniq
  ON webhook_deliveries (job_id, subscription_id) WHERE replay_of IS NULL;

-- The journal screen's read: one subscription's deliveries, newest first.
CREATE INDEX webhook_deliveries_recent_idx
  ON webhook_deliveries (subscription_id, created_at DESC);

CREATE TRIGGER webhook_deliveries_set_updated_at
  BEFORE UPDATE ON webhook_deliveries
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
