-- Organising: the cycle a ticket is committed to, the triage gate it came through,
-- and the saved question a team keeps re-asking.
--
-- Nothing here stores a derived number. A cycle's progress, its burn-down and which
-- tickets will slip are all computed on read from `tickets.completed_at` and the
-- cycle's own two dates, because a stored rate is a number that is wrong from the
-- moment the next ticket closes.

-- Trigram similarity, for "looks like KAN-142 — 68 %" on the triage screen. The first
-- extension this schema asks for: `gen_random_uuid()` is core in Postgres 13+, so
-- nothing before this needed one. Also computed on read — a stored score would be a
-- second copy of a title, going stale on every rename.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE cycles (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  team_id    UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  number     INTEGER NOT NULL,
  starts_on  DATE NOT NULL,
  ends_on    DATE NOT NULL,
  state      TEXT NOT NULL DEFAULT 'upcoming',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT cycles_team_number_uniq UNIQUE (team_id, number),
  -- The three states the sidebar draws, and no fourth. Enforced here rather than in
  -- the service, following `tickets_status_chk`: an unknown value is refused by the
  -- database or it is refused nowhere.
  CONSTRAINT cycles_state_chk CHECK (state IN ('upcoming', 'active', 'closed')),
  CONSTRAINT cycles_dates_chk CHECK (ends_on >= starts_on)
);

CREATE INDEX cycles_team_idx ON cycles (team_id, number DESC);

-- One active cycle per team, as a partial unique index rather than a rule in Kotlin.
-- `/cycles/current` has to resolve to exactly one row, and two active cycles would
-- make that route answer differently depending on the sort.
CREATE UNIQUE INDEX cycles_one_active_per_team ON cycles (team_id) WHERE state = 'active';

CREATE TRIGGER cycles_set_updated_at BEFORE UPDATE ON cycles
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- `ticket_id` is the primary key, not half of a composite: a ticket belongs to at
-- most one cycle. "Move to cycle 25" is then a move rather than an addition, which is
-- what makes the will-slip list actionable — a ticket in two cycles at once has no
-- answer to "does it fit in the days left".
CREATE TABLE ticket_cycles (
  ticket_id UUID PRIMARY KEY REFERENCES tickets(id) ON DELETE CASCADE,
  cycle_id  UUID NOT NULL REFERENCES cycles(id) ON DELETE CASCADE,
  added_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ticket_cycles_cycle_idx ON ticket_cycles (cycle_id);

-- The trace the triage screen promises: "nothing is lost — the queue keeps a record of
-- what was closed". A decision is what removes a ticket from the queue, so the absence
-- of a row here is the queue's own definition rather than a flag somebody has to
-- remember to clear.
CREATE TABLE triage_decisions (
  ticket_id    UUID PRIMARY KEY REFERENCES tickets(id) ON DELETE CASCADE,
  decision     TEXT NOT NULL,
  -- Set only by 'duplicate', and required by it. Two constraints in one: a duplicate
  -- that names nothing is not a duplicate, and an accepted ticket pointing at another
  -- is a decision nobody made.
  duplicate_of UUID REFERENCES tickets(id) ON DELETE SET NULL,
  decided_by   UUID REFERENCES users(id) ON DELETE SET NULL,
  decided_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT triage_decisions_decision_chk
    CHECK (decision IN ('accepted', 'backlogged', 'duplicate', 'closed')),
  CONSTRAINT triage_decisions_duplicate_chk
    CHECK ((decision = 'duplicate') = (duplicate_of IS NOT NULL)),
  CONSTRAINT triage_decisions_not_self_chk CHECK (duplicate_of <> ticket_id)
);

CREATE INDEX triage_decisions_decided_idx ON triage_decisions (decided_at DESC);

-- A saved view is a stored question, not a stored answer: `filters` names the
-- predicate and the rows are matched on every read. Caching the ids would make a view
-- lie the moment somebody changed a status outside it.
--
-- `filters` is jsonb because the set of facets is open-ended by design — the drawing
-- shows project, status and label chips, and the next one will show something else.
-- The keys are validated in `SavedViewService` against a closed list; a CHECK here
-- would have to be rewritten for every new facet, which is the one thing jsonb is for.
CREATE TABLE saved_views (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  team_id    UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  -- "Shared with the team" in the drawing's header. False means it is the author's
  -- own, which is why `created_by` is not nullable-by-accident: an unshared view with
  -- no owner would be reachable by nobody.
  shared     BOOLEAN NOT NULL DEFAULT FALSE,
  filters    JSONB NOT NULL DEFAULT '{}'::jsonb,
  group_by   TEXT NOT NULL DEFAULT 'status',
  sort_by    TEXT NOT NULL DEFAULT 'priority',
  created_by UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT saved_views_team_name_uniq UNIQUE (team_id, name),
  CONSTRAINT saved_views_group_by_chk
    CHECK (group_by IN ('status', 'priority', 'assignee', 'project', 'none')),
  CONSTRAINT saved_views_sort_by_chk
    CHECK (sort_by IN ('priority', 'updated', 'created', 'title'))
);

CREATE INDEX saved_views_team_idx ON saved_views (team_id, name);

CREATE TRIGGER saved_views_set_updated_at BEFORE UPDATE ON saved_views
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Trigram index on the titles the triage screen compares. Without it, `similarity()`
-- over every ticket in the instance is a sequential scan per keypress.
CREATE INDEX tickets_title_trgm_idx ON tickets USING gin (title gin_trgm_ops);
