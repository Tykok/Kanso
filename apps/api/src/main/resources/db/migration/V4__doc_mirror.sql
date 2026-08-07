-- A Notion relation can only point at pages inside the data source it targets, so
-- a project cannot relate directly to an arbitrary page elsewhere in the
-- workspace. The mirrored Docs database therefore holds one index row per
-- referenced page, carrying its URL, and the relations point at those rows.
--
-- Two different Notion ids per doc, hence two columns:
--   notion_page_id : the real page someone wrote, referenced by Kanso.
--   mirror_page_id : Kanso's index row inside the mirrored Docs database.

ALTER TABLE notion_docs
  ADD COLUMN mirror_page_id          TEXT UNIQUE,
  ADD COLUMN sync_state              TEXT NOT NULL DEFAULT 'pending',
  ADD COLUMN notion_synced_at        TIMESTAMPTZ,
  ADD COLUMN notion_last_edited_time TIMESTAMPTZ;

ALTER TABLE notion_docs ADD CONSTRAINT notion_docs_sync_state_chk
  CHECK (sync_state IN ('pending', 'synced', 'failed', 'disabled'));
