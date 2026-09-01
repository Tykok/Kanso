-- Closing a cycle carries its unfinished work into the next one, and the feed has to
-- be able to say so.
--
-- This migration adds one word to one vocabulary, and nothing else. In particular it
-- adds no column: where a ticket is, is `ticket_cycles.cycle_id`, and that table is
-- keyed on the ticket, so the carry-over is an UPDATE of a row that already exists.
-- A `carried_over BOOLEAN` on `tickets`, or a `carried_from` on `ticket_cycles`, would
-- be a second copy of a fact the activity row below already states, going stale the
-- first time somebody moves the ticket by hand.
--
-- `activity.kind` is a closed vocabulary refused by the database as well as by
-- `ActivityKind` in Kotlin — the same two-sided guard `user_preferences` has. That is
-- exactly why a new kind costs a migration: the enum alone would let a value through
-- that every other instance's database would reject.
--
-- Dropped and re-added rather than widened in place: Postgres has no
-- `ALTER CONSTRAINT` for a CHECK's expression, and re-stating the whole list keeps the
-- constraint readable in `\d activity` as one line per deployment rather than as a
-- chain of amendments. The re-add revalidates every existing row, which is what we
-- want — the list is otherwise the only claim that no `updated` ever got written.
ALTER TABLE activity DROP CONSTRAINT activity_kind_chk;

ALTER TABLE activity ADD CONSTRAINT activity_kind_chk
  CHECK (kind IN ('created', 'status_changed', 'priority_changed', 'assigned',
                  'unassigned', 'renamed', 'scheduled', 'archived', 'commented',
                  'labelled', 'mirror_pushed', 'carried_over'));
