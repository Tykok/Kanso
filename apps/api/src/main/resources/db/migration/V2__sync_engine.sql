-- Everything the sync engine and the keyboard-first UI need on top of the core
-- schema. Additive only: no column from V1 is altered or dropped.

-- ---------------------------------------------------------------------------
-- updated_at maintained by the database, not by application code.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER teams_set_updated_at BEFORE UPDATE ON teams
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER projects_set_updated_at BEFORE UPDATE ON projects
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tickets_set_updated_at BEFORE UPDATE ON tickets
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- Mirror bookkeeping, per entity.
--   notion_last_edited_time : what Notion reported after our last successful
--                             push. Used both to arbitrate conflicts and to
--                             recognise the echo of our own writes.
--   sync_state              : surfaced in the UI so a stuck mirror is visible.
-- ---------------------------------------------------------------------------
ALTER TABLE teams
  ADD COLUMN notion_last_edited_time TIMESTAMPTZ,
  ADD COLUMN notion_synced_at        TIMESTAMPTZ,
  ADD COLUMN sync_state              TEXT NOT NULL DEFAULT 'pending';
ALTER TABLE projects
  ADD COLUMN notion_last_edited_time TIMESTAMPTZ,
  ADD COLUMN notion_synced_at        TIMESTAMPTZ,
  ADD COLUMN sync_state              TEXT NOT NULL DEFAULT 'pending';
ALTER TABLE tickets
  ADD COLUMN notion_last_edited_time TIMESTAMPTZ,
  ADD COLUMN notion_synced_at        TIMESTAMPTZ,
  ADD COLUMN sync_state              TEXT NOT NULL DEFAULT 'pending';

ALTER TABLE teams    ADD CONSTRAINT teams_sync_state_chk
  CHECK (sync_state IN ('pending', 'synced', 'failed', 'disabled'));
ALTER TABLE projects ADD CONSTRAINT projects_sync_state_chk
  CHECK (sync_state IN ('pending', 'synced', 'failed', 'disabled'));
ALTER TABLE tickets  ADD CONSTRAINT tickets_sync_state_chk
  CHECK (sync_state IN ('pending', 'synced', 'failed', 'disabled'));

-- Status and priority vocabularies are closed. Notion silently invents a select
-- option when you write an unknown one, so the database has to be the one that
-- says no — otherwise the vocabulary drifts from whatever someone typed there.
ALTER TABLE projects ADD CONSTRAINT projects_status_chk
  CHECK (status IN ('planned', 'in_progress', 'paused', 'completed', 'canceled'));
ALTER TABLE tickets ADD CONSTRAINT tickets_status_chk
  CHECK (status IN ('backlog', 'todo', 'in_progress', 'in_review', 'done', 'canceled'));
ALTER TABLE tickets ADD CONSTRAINT tickets_priority_chk
  CHECK (priority IS NULL OR priority IN ('none', 'low', 'medium', 'high', 'urgent'));
ALTER TABLE team_members ADD CONSTRAINT team_members_role_chk
  CHECK (role IN ('member', 'admin'));

-- ---------------------------------------------------------------------------
-- Team key + ticket counter. The counter is bumped inside the ticket insert
-- transaction (UPDATE ... RETURNING), so the row lock on the team serialises
-- allocation: no gaps, no collisions, no extra sequence to keep in step.
-- ---------------------------------------------------------------------------
ALTER TABLE teams
  ADD COLUMN key            TEXT,
  ADD COLUMN ticket_counter INT NOT NULL DEFAULT 0;

UPDATE teams SET key = upper(substr(replace(id::text, '-', ''), 1, 4)) WHERE key IS NULL;

ALTER TABLE teams ALTER COLUMN key SET NOT NULL;
ALTER TABLE teams ADD CONSTRAINT teams_key_uniq   UNIQUE (key);
ALTER TABLE teams ADD CONSTRAINT teams_key_format CHECK (key ~ '^[A-Z0-9]{2,8}$');

-- ---------------------------------------------------------------------------
-- A team owns its tickets; a project only groups them. Without this a ticket
-- with no project belongs to nobody: no team view, no short identifier.
-- ---------------------------------------------------------------------------
ALTER TABLE tickets ADD COLUMN team_id UUID REFERENCES teams(id) ON DELETE CASCADE;

UPDATE tickets t
   SET team_id = p.team_id
  FROM projects p
 WHERE t.project_id = p.id
   AND t.team_id IS NULL
   AND p.team_id IS NOT NULL;

ALTER TABLE tickets ALTER COLUMN team_id SET NOT NULL;

ALTER TABLE tickets ADD COLUMN number INT;

WITH numbered AS (
  SELECT id, row_number() OVER (PARTITION BY team_id ORDER BY created_at, id) AS n
    FROM tickets
)
UPDATE tickets t SET number = numbered.n FROM numbered WHERE t.id = numbered.id;

UPDATE teams SET ticket_counter = COALESCE(
  (SELECT count(*) FROM tickets WHERE tickets.team_id = teams.id), 0);

ALTER TABLE tickets ALTER COLUMN number SET NOT NULL;
ALTER TABLE tickets ADD CONSTRAINT tickets_team_number_uniq UNIQUE (team_id, number);

-- ---------------------------------------------------------------------------
-- Outbox queue.
--   priority        : dependency order — a project page cannot be created in
--                     Notion before its team page exists.
--   next_attempt_at : without it there is no backoff, only a hot retry loop.
--   locked_at       : lets a supervisor reclaim jobs abandoned by a dead worker.
-- ---------------------------------------------------------------------------
ALTER TABLE sync_jobs
  ADD COLUMN priority        INT         NOT NULL DEFAULT 100,
  ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN locked_at       TIMESTAMPTZ,
  ADD COLUMN locked_by       TEXT,
  ADD COLUMN payload         JSONB,
  ADD COLUMN updated_at      TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE sync_jobs ADD CONSTRAINT sync_jobs_status_chk
  CHECK (status IN ('pending', 'running', 'done', 'failed'));
ALTER TABLE sync_jobs ADD CONSTRAINT sync_jobs_entity_chk
  CHECK (entity_type IN ('team', 'project', 'ticket', 'doc'));
ALTER TABLE sync_jobs ADD CONSTRAINT sync_jobs_operation_chk
  CHECK (operation IN ('upsert', 'archive', 'delete'));

CREATE TRIGGER sync_jobs_set_updated_at BEFORE UPDATE ON sync_jobs
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- At most one queued job per entity. Pushes always write the entity's full
-- current state, so two queued pushes are redundant — the enqueue coalesces
-- onto the existing row instead. Claiming a job flips it to 'running', which
-- frees the slot: a change made while a push is in flight still gets queued.
CREATE UNIQUE INDEX sync_jobs_pending_uniq
  ON sync_jobs (entity_type, entity_id) WHERE status = 'pending';

CREATE INDEX sync_jobs_ready
  ON sync_jobs (priority, next_attempt_at, id) WHERE status = 'pending';

CREATE INDEX sync_jobs_stuck
  ON sync_jobs (locked_at) WHERE status = 'running';

-- ---------------------------------------------------------------------------
-- Notion wiring, discovered at bootstrap and persisted — never guessed.
-- The 2025 API nests data sources under databases; queries target the data
-- source id, page creation targets it too, so both are stored.
-- ---------------------------------------------------------------------------
CREATE TABLE notion_databases (
  kind           TEXT PRIMARY KEY,
  database_id    TEXT NOT NULL,
  data_source_id TEXT NOT NULL,
  parent_page_id TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT notion_databases_kind_chk
    CHECK (kind IN ('teams', 'projects', 'tickets', 'docs'))
);

CREATE TRIGGER notion_databases_set_updated_at BEFORE UPDATE ON notion_databases
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Cursor for the incremental inbound poll, one row per data source.
CREATE TABLE notion_sync_cursors (
  data_source_id TEXT PRIMARY KEY,
  last_edit_time TIMESTAMPTZ,
  last_run_at    TIMESTAMPTZ,
  last_error     TEXT
);

-- ---------------------------------------------------------------------------
-- Indexes for the default views.
-- ---------------------------------------------------------------------------
CREATE INDEX tickets_team_status_idx ON tickets (team_id, status) WHERE NOT archived;
CREATE INDEX tickets_project_idx     ON tickets (project_id)      WHERE NOT archived;
CREATE INDEX tickets_updated_idx     ON tickets (updated_at DESC);
CREATE INDEX projects_team_idx       ON projects (team_id)        WHERE NOT archived;
CREATE INDEX teams_parent_idx        ON teams (parent_team_id);
CREATE INDEX ticket_assignees_user_idx ON ticket_assignees (user_id);
