-- KAN-28. A team's own words for its work.
--
-- `KAN-1` derived the five categories from the status enum and said, in `DefaultStatus`'s
-- own docstring, that the mapping *is* the definition. That is what unblocked this: every
-- consumer that reasons — the burndown, cycle time, WIP, the cycle rollover, velocity, a
-- project's progress, the public roadmap — reads the category, so the *word* is free to
-- become a team's business. This migration is where it stops being Kanso's.
--
-- The catalogue is per team and **copied** at creation rather than inherited from a
-- default living elsewhere. A team's list is then always its own rows, and every reader
-- has one case to handle instead of "its rows, or else the enum" — which is the shape of
-- bug that survives review. The cost is stated plainly: changing the seed later does not
-- reach teams already created.
--
-- `category` is a CHECK and not a table, for the reason `activity_kind_chk` is one. The
-- vocabulary is closed, `StatusCategory` mirrors it, and a sixth category should be a
-- migration somebody writes on purpose rather than a row somebody inserts.
--
-- `position` gets **no** unique index, and that is deliberate rather than lax. A reorder
-- is a swap, and a unique index is checked as an UPDATE walks its rows: trading two
-- positions in one statement raises a violation halfway through unless the constraint is
-- deferred — a footgun bought to enforce an invariant nothing reads. Readers sort by
-- `(position, key)`, so a gap or a tie is invisible, and a reorder cannot half-fail.
CREATE TABLE team_statuses (
  team_id   uuid NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
  key       text NOT NULL,
  label     text NOT NULL,
  category  text NOT NULL,
  position  integer NOT NULL,
  PRIMARY KEY (team_id, key),
  CONSTRAINT team_statuses_category_chk
    CHECK (category IN ('backlog', 'unstarted', 'started', 'completed', 'canceled'))
);

-- The second half of the duplicate guard, and it exists for the *sentence*. The primary
-- key already makes `In Progress` and `in progress` the same row, since the key is
-- derived from the label; without this index the refusal arrives as a conflict on a key
-- nobody typed. `users_email_lower_uniq` is in this schema for exactly that reason.
CREATE UNIQUE INDEX team_statuses_label_uniq ON team_statuses (team_id, lower(label));

-- The six, once, as a function — because two callers need exactly this list and one of
-- them is a trigger. `StatusOrder.WORKFLOW` is its counterpart in Kotlin and holds the
-- same order for the same reason; a migration is a fact about a moment and must not move
-- when Kotlin does, so the words are written here rather than read from there.
CREATE FUNCTION seed_team_statuses(team uuid) RETURNS void
LANGUAGE sql AS $$
  INSERT INTO team_statuses (team_id, key, label, category, position)
  SELECT team, s.key, s.label, s.category, s.position
  FROM (VALUES
    ('backlog',     'Backlog',     'backlog',   0),
    ('todo',        'Todo',        'unstarted', 1),
    ('in_progress', 'In progress', 'started',   2),
    ('in_review',   'In review',   'started',   3),
    ('done',        'Done',        'completed', 4),
    ('canceled',    'Canceled',    'canceled',  5)
  ) AS s (key, label, category, position);
$$;

-- Every team that already exists gets the six it already had.
SELECT seed_team_statuses(id) FROM teams;

-- And every team created from now on, whoever creates it.
--
-- A trigger and not a line in `TeamService.create`, which is where this was going to go
-- until the constraint below was switched on and 550 tests went red: half of them build a
-- team through `TeamRepository` and never touch the service, and so could any write path
-- added next year — an import, an MCP tool, a fixture. A team with no statuses cannot hold
-- a single ticket, so it is not a team in a state anybody should be able to produce, and
-- the only place that cannot be bypassed is here. `set_updated_at_on_edit` in `V38` is the
-- same argument about a different invariant.
CREATE FUNCTION seed_team_statuses_on_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM seed_team_statuses(NEW.id);
  RETURN NEW;
END;
$$;

CREATE TRIGGER teams_seed_statuses AFTER INSERT ON teams
  FOR EACH ROW EXECUTE FUNCTION seed_team_statuses_on_insert();

-- The seed and the trigger above first, or the foreign key below has nothing to point at
-- and no way to acquire anything.
--
-- `V2__sync_engine.sql` wrote `tickets_status_chk` as a CHECK listing the six. What
-- replaces it is a composite foreign key, and the draft case is answered by Postgres
-- rather than by us: under the default MATCH SIMPLE, a row with NULL in *any* referencing
-- column satisfies the constraint without a lookup. So a ticket with no team — `KAN-9`
-- made those ordinary, and `tickets_drafts_idx` exists for them — passes untouched, while
-- a ticket that belongs to a team is unable to hold a status that team has not defined.
--
-- In the database and not in a service, because a repository can forget and a write path
-- added next year never knew.
ALTER TABLE tickets DROP CONSTRAINT tickets_status_chk;
ALTER TABLE tickets ADD CONSTRAINT tickets_status_fk
  FOREIGN KEY (team_id, status) REFERENCES team_statuses (team_id, key);
