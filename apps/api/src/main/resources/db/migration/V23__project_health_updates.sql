-- How a project is going, said in a sentence by a person on a date.
--
-- **Health is not status, and this table is what keeps them apart.** `projects.status`
-- says where the work *is* — planned, in progress, paused, completed, canceled — and it
-- is a fact about the plan. Health says whether the thing will *land*, and it is a
-- judgement about the plan. A project can sit at `in_progress` for eleven weeks while
-- quietly becoming undeliverable, and today nothing in this schema can say so: every
-- column on `projects` would still read exactly as it did on day one. So health gets its
-- own rows, nothing here writes back to `projects.status`, and nothing derives one from
-- the other. Two questions, two answers, and a project that is `in_progress` and
-- `off_track` is not a contradiction — it is the single most useful sentence this table
-- can produce.
--
-- **The current health is the latest row, never a column.** `V10` states the house rule
-- outright and this obeys it: a stored `projects.health` would be wrong from the moment
-- the next update lands, and it would be wrong *silently*, because nothing else on the
-- row would have changed to hint that the cached value had aged. The read is one
-- `DISTINCT ON (project_id) … ORDER BY project_id, at DESC` against the index below, which
-- is the same cost as reading a column and cannot disagree with the history under it.
--
-- **A project with no row here has no health, and that is not `on_track`.** "Nobody has
-- said" and "somebody looked and said it is fine" are different facts, and defaulting the
-- first to the second is exactly how a health signal turns into noise: every project in
-- the instance would come up green on the day this ships, including the ones nobody has
-- ever assessed, and a reader who learns that green is the resting state stops reading
-- green. Absence is stored as absence — no row, no `DEFAULT`, and the API answers null —
-- the same argument `V16` makes for an unestimated ticket not being zero.
--
-- **`body` is NOT NULL and the service refuses an empty one.** A health value with no
-- sentence under it is a colour nobody can act on: the whole point of an update is the
-- reason, and "at risk" alone leaves the reader exactly where they started.
--
-- `author_id` is `ON DELETE SET NULL`, following `activity.actor_id` rather than
-- `comments.author_id`'s RESTRICT. A comment is somebody's sentence in a conversation and
-- deleting the account is the moment to decide what happens to it; an update is a record
-- of what was known about this project in August, and losing the account must not lose
-- that. The row keeps its health, its body and its date, and stops naming a person.
--
-- No `updated_at` and no trigger: an update is not edited. It is a dated statement, and
-- the correction for a wrong one is the next update — which is also the only correction
-- that leaves the history readable. There is no writer for this column and so there is no
-- column.
CREATE TABLE project_updates (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  health     TEXT NOT NULL,
  body       TEXT NOT NULL,
  author_id  UUID REFERENCES users(id) ON DELETE SET NULL,
  at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- The three words and no fourth, enforced here as well as by `ProjectHealth`, following
  -- `tickets_status_chk`: a vocabulary the database does not refuse is one that drifts to
  -- whatever the importer or a psql session happened to write. Three and not five —
  -- there is no `unknown` (that is the absence of a row) and no `complete` (that is
  -- `projects.status`, which is the distinction this whole table exists to preserve).
  CONSTRAINT project_updates_health_chk
    CHECK (health IN ('on_track', 'at_risk', 'off_track'))
);

-- `(project_id, at DESC)` is the shape of every read there is: the latest one for the
-- header, the last handful for the history, the latest across a whole list of projects
-- for the sidebar. The same composite serves all three, and the DESC is in the index
-- rather than left to a sort because the one-row-per-project read is on the path that
-- draws every project page.
CREATE INDEX project_updates_project_idx ON project_updates (project_id, at DESC);

-- --- the feed ---------------------------------------------------------------
--
-- MERGE HAZARD, read before resolving a conflict here.
--
-- This is the only statement in this file that touches a shared object, and it is last
-- and alone for that reason. Another branch is widening this same constraint with a
-- different kind. Postgres has no "add one value to a CHECK", so both branches must drop
-- and recreate it with the whole vocabulary spelled out, and whichever lands second
-- silently *removes* the first one's kind — a conflict that compiles, migrates and only
-- shows up as a constraint violation the first time that other kind is written.
--
-- The resolution is therefore always the **union** of the two lists, never one side of
-- the diff. Keep one `ALTER … DROP` / `ALTER … ADD` pair, in whichever migration has the
-- higher version, listing every kind both branches added; delete the other pair outright.
-- `ActivityKind` in `domain/Model.kt` is the list to check the result against — the two
-- are the same vocabulary and this constraint is the half that catches a writer which
-- never came through Kotlin.
--
-- Why a health update earns a kind at all: it is the first thing in Kanso to write an
-- `entity_type = 'project'` row. Nothing does today, which is why the feed down the right
-- of the project page has always drawn empty. And an update is precisely what someone
-- goes looking for later — "when did this start being at risk, and who said so" is a
-- question about history, which is what this table is. The row names the update and
-- carries the health it moved to; it does not quote the body, following `commented`: an
-- activity row outlives what it describes, and a feed holding a copy of the sentence is a
-- second copy to keep in step.
-- `estimated` is V21's, restated here and not dropped: this statement re-states the
-- whole vocabulary rather than amending it, so a list written without V21's word would
-- silently revoke it — Kotlin would keep accepting `ESTIMATED` and the database would
-- start refusing the row. The two migrations were written on branches that could not
-- see each other; the union is the merge's job, and this is it.
ALTER TABLE activity DROP CONSTRAINT activity_kind_chk;

ALTER TABLE activity ADD CONSTRAINT activity_kind_chk
  CHECK (kind IN ('created', 'status_changed', 'priority_changed', 'assigned',
                  'unassigned', 'renamed', 'scheduled', 'archived', 'commented',
                  'labelled', 'mirror_pushed', 'carried_over', 'estimated',
                  'health_posted'));
