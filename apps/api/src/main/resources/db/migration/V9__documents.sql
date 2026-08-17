-- Documents written in Kanso: a folder tree, pages, ordered blocks, three
-- templates, and the block-level backlink that makes a mentioned ticket the same
-- ticket.
--
-- `notion_docs` is not touched and is not widened. It is a four-column index row
-- for a page somebody wrote in *Notion*, and it exists only because a Notion
-- relation can point at nothing outside its own data source (see V4 and
-- architecture.md, lossy case 4). A `doc_page` is a different thing: a page
-- written here. Merging the two would trade the mirror's semantics for one fewer
-- table.
--
-- One deviation from V2's "updated_at maintained by the database, not by
-- application code": none of these tables carries a `set_updated_at` trigger. Two
-- reasons, and the first would be enough on its own. A page's last edit is *two*
-- facts — when, and by whom — and a trigger can supply only one, so half the pair
-- would be written in the service and half behind it. And `now()` is the
-- *transaction's* timestamp, not the statement's, so two touches inside one
-- transaction are indistinguishable — fatal for the one ordered read this schema
-- has, screen 22's "recently changed". The repositories write `updated_at` beside
-- `edited_by`, in one place.

-- ---------------------------------------------------------------------------
-- The tree.
--
-- Team-scoped for the same reason labels are: two teams both keeping a folder
-- called "Decisions" is normal, and one global namespace would make them fight
-- over the word. `parent_id` cascades, so deleting a branch takes the branch;
-- `doc_pages.folder_id` does not, so it never takes the writing (see below).
-- ---------------------------------------------------------------------------
CREATE TABLE doc_folders (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  team_id    UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  parent_id  UUID REFERENCES doc_folders(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  position   INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT doc_folders_name_chk CHECK (length(btrim(name)) > 0),
  -- A folder cannot be its own parent. The deeper cycle is refused in Kotlin, the
  -- same place team parenting is: no constraint can express reachability.
  CONSTRAINT doc_folders_not_own_parent_chk CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX doc_folders_team_idx ON doc_folders (team_id, parent_id, position);

-- ---------------------------------------------------------------------------
-- The pages.
--
-- `notion_page_id` is nullable and that is the point: a page written here has no
-- Notion page at all, and the drawn screen (07) shows both cases — a "Notion"
-- badge with an "Open in Notion" link when the page mirrors one, nothing when it
-- does not.
--
-- `folder_id` is `ON DELETE SET NULL`, not CASCADE: deleting a folder is filing,
-- not destruction, and a tree operation that silently takes the writing with it is
-- the one mistake nobody can undo. The page reappears at the root.
--
-- `edited_by` beside `author_id` because the footer says "edited by Tykok 3 min
-- ago", which is a different fact from who started the page — and the reason the
-- pair is written by the service rather than half of it by a trigger (see above).
-- ---------------------------------------------------------------------------
CREATE TABLE doc_pages (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  team_id        UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  folder_id      UUID REFERENCES doc_folders(id) ON DELETE SET NULL,
  title          TEXT NOT NULL,
  author_id      UUID REFERENCES users(id) ON DELETE SET NULL,
  edited_by      UUID REFERENCES users(id) ON DELETE SET NULL,
  notion_page_id TEXT UNIQUE,
  notion_url     TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT doc_pages_title_chk CHECK (length(btrim(title)) > 0)
);

-- Screen 22's "recently changed" list is the only ordered read of this table.
CREATE INDEX doc_pages_recent_idx ON doc_pages (team_id, updated_at DESC);
CREATE INDEX doc_pages_folder_idx ON doc_pages (folder_id);

-- ---------------------------------------------------------------------------
-- The blocks.
--
-- One row per block, `kind` closed by a CHECK — the precedent `user_preferences`
-- sets, and the reason it matters here is the same as for ticket status: an
-- unknown value is something to refuse, never something to adopt.
--
-- `position` carries no UNIQUE. It is a sort key, read as `ORDER BY position, id`,
-- and a unique constraint would make every reorder a two-phase write to protect a
-- property no reader depends on. The service rewrites the whole page's positions
-- as 0..n-1 on every insert and move, so they stay dense anyway.
--
-- `content` is jsonb because the seven kinds do not share a shape: a checkbox has
-- a state, a table has columns and rows, a heading has a level. Seven nullable
-- columns would encode the same thing and let six of them be wrong at once. What
-- `content` deliberately does *not* carry is a ticket id — that lives in
-- `doc_block_tickets`, so the backlink has exactly one definition.
-- ---------------------------------------------------------------------------
CREATE TABLE doc_blocks (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  page_id    UUID NOT NULL REFERENCES doc_pages(id) ON DELETE CASCADE,
  position   INT NOT NULL,
  kind       TEXT NOT NULL,
  content    JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT doc_blocks_kind_chk CHECK (kind IN (
    'paragraph', 'heading', 'numbered_list', 'checkbox', 'callout', 'ticket_link', 'table'
  ))
);

CREATE INDEX doc_blocks_page_idx ON doc_blocks (page_id, position);

-- ---------------------------------------------------------------------------
-- The backlink `ticket_docs` never had.
--
-- `ticket_docs` links a ticket to a *Notion* index row, at page granularity. This
-- is its counterpart for pages written here, at block granularity — which is what
-- lets a ticket link block render the live status pill rather than a sentence that
-- was true when somebody typed it. That pill is the product's whole premise: the
-- document stays true without being re-read.
--
-- Both sides cascade, and the drawing's own detail depends on which way: deleting
-- a document that mentioned two tickets deletes neither ticket. Only the reference
-- goes.
-- ---------------------------------------------------------------------------
CREATE TABLE doc_block_tickets (
  block_id  UUID NOT NULL REFERENCES doc_blocks(id) ON DELETE CASCADE,
  ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  PRIMARY KEY (block_id, ticket_id)
);

-- "Which pages mention this ticket" — the reverse direction the primary key does
-- not index, and the one a ticket panel asks.
CREATE INDEX doc_block_tickets_ticket_idx ON doc_block_tickets (ticket_id);

-- ---------------------------------------------------------------------------
-- The templates.
--
-- Instance-wide rather than team-scoped: the three the deck draws are shapes of
-- writing, not a team's vocabulary, and there is no screen to manage a fourth.
--
-- `blocks` is one jsonb array rather than a `doc_template_blocks` table because a
-- template is a literal: read whole, copied whole, never edited a row at a time.
-- A table would buy ordering machinery for something with no writer.
-- ---------------------------------------------------------------------------
CREATE TABLE doc_templates (
  id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug    TEXT UNIQUE NOT NULL,
  name    TEXT NOT NULL,
  summary TEXT NOT NULL,
  blocks  JSONB NOT NULL
);

-- The three cards on screen 22, with the captions the drawing gives them. The
-- cycle note's "ticket query included" is a `table` block carrying a `query`
-- rather than an eighth block kind: the drawn vocabulary is closed at seven, and a
-- table whose rows come from a filter is still a table.
INSERT INTO doc_templates (slug, name, summary, blocks) VALUES
('cycle-note', 'Cycle note', 'Ticket query included', '[
  {"kind": "heading",   "content": {"text": "What shipped", "level": 2}},
  {"kind": "table",     "content": {"columns": ["Ticket", "Status"],
                                    "rows": [],
                                    "query": {"status": ["in_progress", "in_review", "done"]}}},
  {"kind": "heading",   "content": {"text": "What slipped, and why", "level": 2}},
  {"kind": "paragraph", "content": {"text": ""}}
]'::jsonb),
('decision', 'Decision', 'Context, options, settled', '[
  {"kind": "heading",   "content": {"text": "Context", "level": 2}},
  {"kind": "paragraph", "content": {"text": ""}},
  {"kind": "heading",   "content": {"text": "Options", "level": 2}},
  {"kind": "numbered_list", "content": {"items": ["", ""]}},
  {"kind": "callout",   "content": {"title": "Settled", "text": ""}}
]'::jsonb),
('incident-report', 'Incident report', 'Timeline and follow-ups', '[
  {"kind": "callout",   "content": {"title": "Impact", "text": ""}},
  {"kind": "heading",   "content": {"text": "Timeline", "level": 2}},
  {"kind": "table",     "content": {"columns": ["Time", "What happened"], "rows": [["", ""]]}},
  {"kind": "heading",   "content": {"text": "Follow-ups", "level": 2}},
  {"kind": "checkbox",  "content": {"text": "", "checked": false}}
]'::jsonb);
