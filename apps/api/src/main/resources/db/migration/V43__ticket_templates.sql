-- A shape a ticket can start in, at two levels.
--
-- The composer already seeds every new ticket from wherever you were standing, and
-- `creation-seed.ts` says in its own header that each value it places is a *suggestion* that
-- the dialog leaves editable. A template is that, larger: a title begun, a description with
-- its headings already written, a priority, a label. Nothing here enforces anything and
-- nothing here is written to a ticket — the composer fills a form, the person edits it, and
-- the ordinary creation path runs unchanged.
--
-- ---------------------------------------------------------------------------
-- `team_id` nullable, which `V35` refused for custom fields — and the refusal was right
-- there and does not reach here.
--
-- V35's header says an instance-wide *field* would be "an object with no owner: anybody who
-- may write anywhere could add a column to everybody's board". That is true because a field
-- changes the shape of every ticket in a team that already exists: define `Severity` and
-- every board, every detail panel and every MCP response grows a slot, retroactively, for
-- work filed months ago.
--
-- A template changes nothing that exists. It is a row in a picker. Choosing it fills a form
-- somebody is looking at and can edit; not choosing it means it may as well not be there.
-- The blast radius of a bad instance template is one bad suggestion in a list, which
-- somebody deletes. The blast radius of a bad instance field is every ticket in the
-- instance. So the boundary V35 was protecting is real and is not crossed here, and the two
-- migrations are one consistent position rather than two moods.
--
-- The smaller argument, which is also decisive: Kanso ships templates, and a fresh instance
-- has no team to ship them into. Either they belong to the instance, or the first team
-- created gets them seeded and the second does not.
-- ---------------------------------------------------------------------------
CREATE TABLE ticket_templates (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- NULL is the instance level: available in every team, editable by an instance admin. Set
  -- is a team's own, and the cascade is `labels`': the team going away takes its
  -- configuration with it.
  team_id    UUID REFERENCES teams(id) ON DELETE CASCADE,

  name       TEXT NOT NULL,

  -- The line drawn under the name in the picker. Nullable because a template whose name says
  -- the whole of it does not owe anybody a second sentence.
  summary    TEXT,

  -- The pre-fill, as one jsonb literal. A `ticket_template_fields` table was the other option
  -- and `V9` already answered it for document templates: a template is "read whole, copied
  -- whole, never edited a row at a time". Its *shape* is enforced by `TemplateBodyCodec`
  -- rather than by a CHECK, the same split `user_preferences.shortcuts` makes, because no
  -- constraint can reach inside a document to ask whether `priority` is one of five words.
  body       JSONB NOT NULL DEFAULT '{}'::jsonb,

  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Two partial indexes and not one `UNIQUE (team_id, name)`, and the difference is the whole
-- correctness of this table: in SQL `NULL` is not equal to `NULL`, so a plain composite
-- unique would let an instance admin create four templates all called `Bug` and the database
-- would agree with every one of them. The partial index is what actually says "one `Bug` at
-- instance level".
CREATE UNIQUE INDEX ticket_templates_team_name_uq
  ON ticket_templates (team_id, name) WHERE team_id IS NOT NULL;
CREATE UNIQUE INDEX ticket_templates_instance_name_uq
  ON ticket_templates (name) WHERE team_id IS NULL;

CREATE INDEX ticket_templates_team_idx ON ticket_templates (team_id);

-- Across the two levels a name may repeat, on purpose: a team that dislikes Kanso's `Bug`
-- writes its own, and the picker shows both under their level headings. Nothing resolves the
-- shadowing automatically — deciding which one to hide is a decision the team already made by
-- writing theirs, and hiding Kanso's on their behalf would be a second one nobody asked for.

-- ---------------------------------------------------------------------------
-- Categories: free text, many per template, managed by nobody.
--
-- A single `category TEXT` column would group the picker into a clean tree and would be less
-- to build. It also forces a false choice the first time somebody writes a template that is
-- both `Engineering` and `Urgent`. Labels on tickets made the same ruling for the same
-- reason. The picker pays for it by filtering rather than grouping — a template with three
-- categories would otherwise appear three times in one list.
--
-- No `categories` table and no id: a category exists exactly as long as some template names
-- it, and a screen administering a list with no independent existence would be a screen
-- administering nothing.
-- ---------------------------------------------------------------------------
CREATE TABLE ticket_template_categories (
  template_id UUID NOT NULL REFERENCES ticket_templates(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  PRIMARY KEY (template_id, name)
);

-- ---------------------------------------------------------------------------
-- Four seeds, and they are seeds rather than fixtures.
--
-- Nothing looks them up by name, no test asserts their content beyond their existence, and an
-- instance that deletes all four is in a perfectly valid state. This is the one place this
-- design departs from `doc_templates`, whose rows `DocTemplateRepository.findBySlug` addresses
-- directly and which therefore cannot be deleted.
--
-- The label names they carry are guesses about what a team is likely to have. A guess that
-- misses is not a failure here: `TemplateResolver` reports it as unresolved and the composer
-- says so out loud, which is the behaviour these seeds are also there to demonstrate.
--
-- The `::jsonb` on each is not decoration. Two string literals joined by `||` resolve to
-- `text`, and Postgres has no implicit cast from `text` to `jsonb`, so without it every one
-- of these rows is rejected at migration time.
-- ---------------------------------------------------------------------------
INSERT INTO ticket_templates (team_id, name, summary, body) VALUES
  (NULL, 'Bug', 'Something is broken, with the steps to see it',
   ('{"title": "[Bug] ", "priority": "high", "labels": ["bug"], "description":'
    || ' "## What happens\n\n## What should happen\n\n## How to reproduce\n\n1. \n"}')::jsonb),
  (NULL, 'Feature', 'Something new, with the reason it is wanted',
   ('{"priority": "medium", "description":'
    || ' "## What this is for\n\n## What it should do\n\n## Out of scope\n"}')::jsonb),
  (NULL, 'Chore', 'Maintenance nobody will notice until it is missing',
   ('{"priority": "low", "labels": ["chore"], "description":'
    || ' "## What needs doing\n\n## Why now\n"}')::jsonb),
  (NULL, 'Customer request', 'Something somebody outside the team asked for',
   ('{"title": "[Request] ", "priority": "medium", "description":'
    || ' "## Who asked\n\n## What they asked for\n\n## What they are trying to do\n"}')::jsonb);

INSERT INTO ticket_template_categories (template_id, name)
SELECT id, 'Engineering' FROM ticket_templates
 WHERE team_id IS NULL AND name IN ('Bug', 'Chore');
INSERT INTO ticket_template_categories (template_id, name)
SELECT id, 'Product' FROM ticket_templates
 WHERE team_id IS NULL AND name IN ('Feature', 'Customer request');
