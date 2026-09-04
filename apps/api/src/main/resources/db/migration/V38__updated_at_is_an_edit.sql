-- `updated_at` answers two questions today, and the screens ask the first while receiving
-- the second: *when did a person edit this ticket*, and *when was a row in this table
-- written*. `V2` made the database the writer of that column — right, and this migration
-- does not take it back — but its trigger stamps on **any** UPDATE, so the mirror's own
-- bookkeeping counts as an edit.
--
-- The measured consequence, on a stack with the mirror disabled: four tickets created 290 ms
-- apart come out of the outbound drain with their four stamps **5.1 ms apart**, because
-- `TicketRepository.markSyncState` writes `sync_state = 'disabled'` on each of them ~400 ms
-- after creation. At rest that is invisible — the rows are rewritten in creation order, so
-- the order does not change — which is why it survived this long. It stops being invisible
-- the moment a bookkeeping sweep touches rows a person edited at different times. Measured,
-- on four tickets of which exactly one had been renamed by hand: before
-- `POST /api/admin/notion/reconcile` the renamed one is first, correctly, 29 s ahead of the
-- other three. After it — with no edit made by anybody — the four stamps land 2.2 ms apart
-- and the renamed one is **last**.
--
-- The resulting order is worth naming, because it is not the tie-break. The stamps differ by
-- fractions of a millisecond rather than being equal, so `number DESC` never engages and the
-- list comes back in the order the outbound worker happened to pick the jobs up: Beta,
-- Gamma, Delta, Alpha. Not edit order, not creation order, not number order. "Récemment mis
-- à jour" becomes an ordering of the queue.
--
-- ---------------------------------------------------------------------------
-- Why the trigger and not the sort.
--
-- Sorting the list on something else was the cheaper fix and it was refused: it repairs one
-- screen and leaves the column lying to everybody else. There is already a second reader,
-- and it is not a screen. `NotionPoller.kansoWins` decides who wins a conflict by asking
-- whether `updated_at > notion_synced_at` — "did the Postgres row move since our last
-- push". A bookkeeping write makes that true with nothing behind it, so Kanso wins a
-- conflict it has no claim to and queues a corrective push that reverts a real edit somebody
-- made in Notion. That is the failure the mirror exists to avoid, and no amount of reordering
-- a list touches it. Fixing the column fixes both readers at once; fixing the sort fixes
-- neither of them honestly.
--
-- ---------------------------------------------------------------------------
-- Why not read the answer out of `activity`, which is where the house would normally look.
--
-- It is the right instinct and it was the first thing tried: derived values are computed on
-- read here and never stored, `V23` and `KAN-23` both argue it, and cycle time and WIP
-- shipped that way with no new column. `MAX(activity.created_at)` per ticket is one
-- aggregate join, not a query per row, so the cost is not the objection.
--
-- The objection is that `activity` does not know. `TicketService.recordScalarChanges` logs
-- **seven** scalars — title, status, priority, archived, estimate, start, due — and the
-- ticket has more than seven. **Rewriting a description writes no activity row at all**, and
-- neither does re-parenting, re-filing under a project, or attaching a doc (`setDocs` says
-- in its own comment that it is deliberately not logged). Sorting on `activity` would take
-- today's bug — a row nobody touched floats up — and trade it for a worse one: a ticket
-- somebody just rewrote never moves. Promoting an untouched row is confusing; hiding a real
-- edit is wrong.
--
-- And `activity` is not clean of bookkeeping either: `mirror_pushed` is one of its
-- seventeen kinds. A sort over it would need its own closed list of "kinds that mean a
-- person edited this" — a second vocabulary to keep in step with the first, in Kotlin,
-- restated for every reader. The distinction belongs in the one place every reader already
-- passes through.
--
-- ---------------------------------------------------------------------------
-- The shape.
--
-- One new function, and `set_updated_at` is left exactly as `V2` wrote it. The ten other
-- tables that call it — `comments`, `cycles`, `saved_views`, `outbound_jobs`,
-- `notion_request_bases` and the rest — are not claiming a person edited anything; their
-- `updated_at` means "this row was written", which is all they have ever needed. Only the
-- three tables `V2` gave the trigger to are re-pointed, because they are the three the
-- mirror does bookkeeping on.
--
-- The columns to ignore are trigger arguments rather than a name check inside the function,
-- so the list is per-table and readable at the site that installs it. `to_jsonb` of the two
-- rows minus those columns is what decides: the test is *which columns actually moved*, not
-- which code path did the writing. A future bookkeeping column that nobody remembers to add
-- to this list fails safe — it counts as an edit, the way it does today — and a future
-- bookkeeping *write path* needs no cooperation at all, which is the whole reason this is
-- not eight `it[updatedAt] = now` calls in `TicketRepository`. `V9` took that other road for
-- `doc_pages` and had a reason to (a page's last edit is two facts, and a trigger can supply
-- one); here there is no second fact, and eight writers each of which can silently forget
-- is a worse trade than one comparison.
--
-- The else-branch pins `NEW.updated_at` to `OLD.updated_at` rather than leaving it alone: a
-- bookkeeping statement must not be able to move the stamp by writing the column itself.
-- Nothing writes `tickets.updated_at` from Kotlin today — the only repositories that write
-- an `updated_at` are the `docs` ones, on tables that have no trigger — and this keeps it
-- that way by construction.
--
-- One deliberate behaviour change beyond the bug: `TicketRepository.update` writes the whole
-- row every time, so a PATCH that changes nothing used to move the stamp. It no longer does.
-- That is the same rule as the bug, read the other way round, and it is what
-- `recordScalarChanges` has always done — "nothing for a patch that changed none". The row
-- count `update` returns is unaffected, so its `changed == 0` "no such ticket" signal still
-- means what it did.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION set_updated_at_on_edit() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  -- `updated_at` is always in the comparison's blind spot: it is the answer, not evidence.
  -- `array_append` rather than `||`, which resolves against `anyarray || anyarray` here and
  -- fails at runtime trying to read the string as an array literal.
  bookkeeping TEXT[] := array_append(TG_ARGV, 'updated_at');
BEGIN
  IF to_jsonb(NEW) - bookkeeping IS DISTINCT FROM to_jsonb(OLD) - bookkeeping THEN
    NEW.updated_at = now();
  ELSE
    NEW.updated_at = OLD.updated_at;
  END IF;
  RETURN NEW;
END;
$$;

-- The four mirror columns are `V2`'s own, added to all three tables in one statement there
-- and written only by `markSynced`, `markSyncState` and `recordNotionEdit`. `notion_page_id`
-- is in the list because the mirror assigns it on the first successful push: learning which
-- Notion page a ticket became is not an edit to the ticket.
DROP TRIGGER tickets_set_updated_at ON tickets;
CREATE TRIGGER tickets_set_updated_at BEFORE UPDATE ON tickets
  FOR EACH ROW EXECUTE FUNCTION set_updated_at_on_edit(
    'notion_page_id', 'sync_state', 'notion_synced_at', 'notion_last_edited_time');

DROP TRIGGER projects_set_updated_at ON projects;
CREATE TRIGGER projects_set_updated_at BEFORE UPDATE ON projects
  FOR EACH ROW EXECUTE FUNCTION set_updated_at_on_edit(
    'notion_page_id', 'sync_state', 'notion_synced_at', 'notion_last_edited_time');

-- `teams` carries a fifth, and it is the same bug wearing different clothes:
-- `ticket_counter` is incremented every time somebody files a ticket, so creating a ticket
-- counted as editing its team. `kansoWins` reads `teams.updated_at` too (`NotionPoller`
-- line 276), which means a busy team's page in Notion could not be edited by hand at all —
-- the next ticket filed anywhere in it would win the conflict and push the edit away.
DROP TRIGGER teams_set_updated_at ON teams;
CREATE TRIGGER teams_set_updated_at BEFORE UPDATE ON teams
  FOR EACH ROW EXECUTE FUNCTION set_updated_at_on_edit(
    'notion_page_id', 'sync_state', 'notion_synced_at', 'notion_last_edited_time',
    'ticket_counter');

-- Existing rows are left as they are. Every `updated_at` in the database was written by the
-- old rule and there is nothing to recompute them from: `activity` cannot answer for the
-- reason above, and guessing `created_at` would erase real edit history to hide a stamp
-- that is at most a few hundred milliseconds wrong. The column starts telling the truth from
-- here; the rows it already has stay as accurate as they ever were.
