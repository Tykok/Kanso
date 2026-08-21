-- Which Notion page a Kanso row was imported from.
--
-- Not `teams.notion_page_id` / `projects.notion_page_id` / `tickets.notion_page_id`:
-- those hold the page the *mirror* created inside `Kanso · Tickets`, which Kanso
-- overwrites on every push. Putting somebody's own page there would point that push at
-- the workspace they just imported and rewrite it — the one thing the import screen
-- promises never happens.
--
-- The primary key is the rule: one Notion page becomes at most one Kanso row, enforced
-- by Postgres rather than by remembering to check. A second import of the same base
-- therefore skips rather than duplicating, which is what makes the import safe to press
-- twice.
--
-- No foreign key: the reference is polymorphic, and the alternative is four nullable
-- columns and a CHECK that exactly one is set. A row whose entity was deleted resolves
-- to nothing, which the resolver already treats as "unlinked" and falls back on.
CREATE TABLE notion_import_origin (
  notion_page_id TEXT PRIMARY KEY,
  entity_type    TEXT NOT NULL CHECK (entity_type IN ('team', 'project', 'ticket', 'doc')),
  entity_id      UUID NOT NULL,
  data_source_id TEXT NOT NULL,
  imported_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (entity_type, entity_id)
);

-- "Has this base been imported before, and how much of it" — the question the preview
-- asks once per plan row.
CREATE INDEX notion_import_origin_source_idx ON notion_import_origin (data_source_id);
