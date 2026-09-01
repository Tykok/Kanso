-- Re-sizing a ticket is a decision, and the feed had no word for it.
--
-- `estimate` became a scalar of a ticket in V16, and it was the only one that moved
-- silently: every other scalar `TicketService.recordScalarChanges` watches already has
-- a kind of its own, precisely so a feed never degrades to "Tykok updated KAN-142".
-- Going from a 3 to a 13 is what somebody comes back looking for three weeks later.
--
-- This migration adds one word to one vocabulary, and nothing else. No column: what a
-- ticket is sized at is `tickets.estimate`, and what it *was* sized at is the payload of
-- the row this kind names. An `estimate_changed_at`, or a previous value kept beside the
-- current one, would be a second copy of what the log already states.
--
-- `activity.kind` is a closed vocabulary refused by the database as well as by
-- `ActivityKind` in Kotlin — the same two-sided guard `user_preferences` has. That is
-- why a new kind costs a migration: the enum alone would let a value through that every
-- other instance's database would reject.
--
-- Dropped and re-added rather than widened in place, for the reasons V17 records:
-- Postgres has no `ALTER CONSTRAINT` for a CHECK's expression, and re-stating the whole
-- list keeps the constraint readable in `\d activity` as one line per deployment rather
-- than as a chain of amendments. The re-add revalidates every existing row, which is
-- what we want — the list is otherwise the only claim that no `updated` ever got written.
ALTER TABLE activity DROP CONSTRAINT activity_kind_chk;

ALTER TABLE activity ADD CONSTRAINT activity_kind_chk
  CHECK (kind IN ('created', 'status_changed', 'priority_changed', 'assigned',
                  'unassigned', 'renamed', 'scheduled', 'archived', 'commented',
                  'labelled', 'mirror_pushed', 'carried_over', 'estimated'));
