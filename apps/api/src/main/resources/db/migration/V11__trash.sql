-- Deleted is not archived, and this migration deliberately adds nothing to `archived`.
--
-- Archived is a decision: somebody chose to put this away and it stays away, with no
-- clock on it. Deleted is a countdown: thirty days, then it is gone. Screen 26 draws
-- both, in two tabs, with two different counts — so repurposing `archived` would
-- collapse two facts into one column and lose the tab that has 64 rows in it.

-- One row per thing in the trash, whatever kind of thing it is.
--
-- The four kinds are one closed vocabulary refused by the database, following
-- `user_preferences`: a fifth kind is a migration, not a value a service lets through.
-- Only `ticket` has a table behind it today — `doc`, `view` and `folder` name the
-- tables slices B and C are writing right now, and the vocabulary is closed here so
-- that landing them adds rows to this table rather than a branch to the service.
--
-- Two things are worth stating rather than discovering:
--
-- There is no `deleted` flag on `tickets`. The existence of a row here *is* the fact,
-- which means there is exactly one place it lives and no way for a bit and a timestamp
-- to drift apart. It also means the three tables that do not exist yet need no column
-- of their own when they land: integration adds a filter to their reads, not a second
-- migration to their schemas. The cost is that excluding the trash from a live read is
-- a subquery on this table's primary key rather than a boolean on the row, which for a
-- set this small is not a cost worth a denormalisation.
--
-- There is no foreign key on `entity_id`, because it points at four different tables.
-- So an entity destroyed by another path — the team disposition deletes tickets
-- outright, with its own consent model — can leave a row here with nothing behind it.
-- The read skips such a row rather than drawing a blank one; `follow-ups.md` records
-- what a real cure would cost.
CREATE TABLE trash_entries (
  entity_type TEXT NOT NULL,
  entity_id   UUID NOT NULL,
  deleted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- Who threw it away, for the "Deleted by" column. SET NULL rather than CASCADE: the
  -- countdown belongs to the entity, not to the person, and losing the account must not
  -- quietly restore something by making its deletion disappear.
  deleted_by  UUID REFERENCES users(id) ON DELETE SET NULL,

  PRIMARY KEY (entity_type, entity_id),
  CONSTRAINT trash_entries_entity_type_chk
    CHECK (entity_type IN ('ticket', 'doc', 'view', 'folder'))
);

-- What the retention sweep reads: the oldest entries first, whatever kind they are.
CREATE INDEX trash_entries_deleted_at_idx ON trash_entries (deleted_at);
