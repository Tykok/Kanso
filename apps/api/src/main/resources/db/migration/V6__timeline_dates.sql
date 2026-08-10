-- Dates become instants with an explicit granularity.
--
-- A value with has_time = false is *floating*: stored as an instant so the
-- scheduler can do arithmetic on it, but never converted for display. "Ends on
-- the 12th" has to read as the 12th for a reader five hours behind, on data they
-- did not touch. A value with has_time = true names a moment and is converted.

ALTER TABLE tickets
  ADD COLUMN start_at       TIMESTAMPTZ,
  ADD COLUMN start_has_time BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN due_at         TIMESTAMPTZ,
  ADD COLUMN due_has_time   BOOLEAN NOT NULL DEFAULT FALSE,
  -- When the ticket entered `done`. `updated_at` cannot answer this: it moves on
  -- every edit, so renaming a ticket would change the start date of its project.
  ADD COLUMN completed_at   TIMESTAMPTZ;

-- `AT TIME ZONE 'UTC'` rather than a bare cast to timestamptz: the bare cast reads
-- the date in the *session's* timezone, so a server running in Paris would store
-- the 12th as 22:00 on the 11th UTC and the day would read back wrong for
-- everyone. A floating day is anchored at midnight UTC, which is what the
-- application then renders without converting.
UPDATE tickets SET
  start_at = start_date::timestamp AT TIME ZONE 'UTC',
  due_at   = due_date::timestamp AT TIME ZONE 'UTC';

ALTER TABLE tickets DROP COLUMN start_date, DROP COLUMN due_date;

ALTER TABLE projects
  ADD COLUMN start_at       TIMESTAMPTZ,
  ADD COLUMN start_has_time BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN end_at         TIMESTAMPTZ,
  ADD COLUMN end_has_time   BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE projects SET
  start_at = start_date::timestamp AT TIME ZONE 'UTC',
  end_at   = end_date::timestamp AT TIME ZONE 'UTC';

ALTER TABLE projects DROP COLUMN start_date, DROP COLUMN end_date;

-- The reader's timezone, not the instance's: every instant is stored in UTC and
-- rendered per person. Seeded from the browser on first load rather than detected
-- per render, which would make every bar jump when Kanso is opened abroad.
ALTER TABLE user_preferences
  ADD COLUMN timezone TEXT NOT NULL DEFAULT 'UTC';
