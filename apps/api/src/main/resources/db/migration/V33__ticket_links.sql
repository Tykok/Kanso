-- `ticket_dependencies` knows exactly one thing about two tickets: that one finishes
-- before the other starts. V7 said so on purpose — "no lag, no link type. Both are
-- cheap, non-destructive additions the day one is actually asked for, and neither has
-- been." One of the two has now been asked for, and this is that cheap addition.
--
-- What is missing is not scheduling, it is navigation. "This is related to that" has
-- nowhere to live, so it lives in prose in a description and nothing can follow it.
-- "This duplicates that" is worse than missing: it exists, as `triage_decisions.
-- duplicate_of`, but only as the record of a ruling made once at the triage gate. A
-- ticket marked duplicate three weeks after triage has no way to say so, and a ticket
-- that *was* marked duplicate cannot be navigated to its original from its own page.
--
-- So the table stops being about scheduling and becomes about the graph, with the kind
-- of edge as a column. The scheduler then reads one kind of it and the ticket page
-- reads all three.

-- ---------------------------------------------------------------------------
-- Renamed, never recreated
-- ---------------------------------------------------------------------------
-- The same argument V26 made for the outbox. Every row in this table is a dependency
-- somebody drew, that the cascade is enforcing right now and that the Notion mirror
-- has already pushed as a relation. `CREATE TABLE ticket_links` + `INSERT ... SELECT`
-- + `DROP TABLE ticket_dependencies` would copy them; a rename carries them, with
-- their `created_at` and their identity intact, and cannot half-succeed.
--
-- How many rows is that? Every row the table has — this migration converts the whole
-- table and leaves nothing behind, whether that is zero rows in a fresh instance or
-- all of them in the maintainer's. The count does not need to be known because the
-- conversion does not depend on it, and that is the point of the next paragraph.
--
-- The conversion is lossless because the old table had exactly one meaning. A row in
-- `ticket_dependencies` is a finish-to-start dependency and there was never any other
-- kind to confuse it with: the table has no type column to have been wrong, and V7 is
-- the only migration that ever touched it, so no later file quietly widened what a row
-- could mean. `DEFAULT 'blocks'` therefore reads every existing row correctly by
-- construction rather than by guess — it is not a backfill heuristic, it is the name
-- of the thing those rows already were.
--
-- Postgres does not follow a table or column rename into the objects hanging off them,
-- so the primary key, both foreign keys, the CHECK and the index are renamed or
-- rebuilt by hand. A schema reached by migration has to be indistinguishable from one
-- built from scratch, or the next migration finds a name that is only there sometimes.
-- V7's self-link CHECK was never given a name and Postgres called it
-- `ticket_dependencies_check`; it gets a real one on the way past.
ALTER TABLE ticket_dependencies RENAME TO ticket_links;

-- `predecessor`/`successor` are the vocabulary of a schedule, and two of the three
-- kinds of edge are not schedules. `from`/`to` name the direction without claiming
-- what the direction means — which is now the type's job. Spelled `*_ticket_id`
-- because `from` and `to` are reserved words and a quoted column name is a trap
-- every query afterwards pays for.
ALTER TABLE ticket_links RENAME COLUMN predecessor_id TO from_ticket_id;
ALTER TABLE ticket_links RENAME COLUMN successor_id   TO to_ticket_id;

ALTER TABLE ticket_links RENAME CONSTRAINT ticket_dependencies_check TO ticket_links_not_self_chk;
ALTER TABLE ticket_links RENAME CONSTRAINT ticket_dependencies_predecessor_id_fkey TO ticket_links_from_fkey;
ALTER TABLE ticket_links RENAME CONSTRAINT ticket_dependencies_successor_id_fkey   TO ticket_links_to_fkey;

-- The self-link CHECK is inherited rather than rewritten, and it now says more than it
-- did: no ticket blocks, relates to or duplicates itself. All three readings are ones
-- we want, and `TriageService` already refuses the third by hand ("A ticket cannot
-- duplicate itself") — so this is the constraint that finally makes that refusal
-- structural instead of a service-level courtesy.

-- ---------------------------------------------------------------------------
-- The type, closed in the database as well as in Kotlin
-- ---------------------------------------------------------------------------
-- 'blocks' as a default only long enough to convert, then dropped, so that the next
-- link has to say what kind it is instead of inheriting an answer. That is V26's
-- pattern and the reason for it is the same: a defaulted discriminator is a column
-- that lies the first time somebody forgets to set it.
ALTER TABLE ticket_links ADD COLUMN type TEXT NOT NULL DEFAULT 'blocks';
ALTER TABLE ticket_links ALTER COLUMN type DROP DEFAULT;

-- Three values, and the list is complete rather than open-ended: a fourth kind of edge
-- is a product decision with a screen attached, not a string somebody passes.
--
-- This list is *new*, which is the only reason it can be stated in full here. V30's
-- lesson is that re-stating a vocabulary means re-stating the whole current one, and
-- the precedent file is not the authority — the newest migration that set it is. So it
-- was grepped for: `ticket_dependencies` appears in V7 and nowhere else in
-- `db/migration`, the table has never carried a type, and there is consequently no
-- earlier list to drop a value from. `TicketLinkType` in Kotlin holds these same three
-- and `TicketLinkTypeTest` cross-checks the two directions, so neither side can gain a
-- value the other refuses.
ALTER TABLE ticket_links ADD CONSTRAINT ticket_links_type_chk
  CHECK (type IN ('blocks', 'relates', 'duplicates'));

-- The type joins the key, because two tickets can stand in more than one relation at
-- once. "A blocks B" and "A relates to B" are two facts and a key of (from, to) would
-- make the second one overwrite the first — silently, since an upsert is how links get
-- drawn. It also keeps the useful refusal: the same pair cannot carry the same kind of
-- edge twice.
ALTER TABLE ticket_links DROP CONSTRAINT ticket_dependencies_pkey;
ALTER TABLE ticket_links ADD PRIMARY KEY (from_ticket_id, to_ticket_id, type);

-- ---------------------------------------------------------------------------
-- Directed per type, and one of the three is not
-- ---------------------------------------------------------------------------
-- 'blocks' is directed and the direction is the whole content: A before B is a
-- schedule, B before A is a different schedule, and swapping them is a bug the cascade
-- would act on.
--
-- 'duplicates' is directed too, and it is worth saying why since it is less obvious.
-- "A duplicates B" nominates B as the survivor. That asymmetry is the only reason the
-- link is worth reading: a reader of A wants "this was already reported, go here", and
-- a reader of B wants "these came in again". Storing it symmetrically would make those
-- two sentences the same sentence and neither would be true.
--
-- 'relates' is **symmetric**. There is no survivor, no order and no first mover; "A
-- relates to B" and "B relates to A" are one fact, and a person who draws it from
-- either end means the same thing.
--
-- A symmetric relation stored in a directed table can be held two ways, and both cost
-- something:
--
--   * Two rows, one per direction. Reads stay one-directional and trivial, and the
--     price is that the two rows can drift — a delete that removes one leaves a
--     half-link that shows on one ticket and not the other, and nothing in the schema
--     notices. It also doubles the row count for a relation that is one fact.
--   * One row, canonically ordered. One fact, one row, and a delete cannot half-fire.
--     The price is paid on every read.
--
-- The second was chosen, and the CHECK below is what makes it real: a 'relates' row
-- must be stored with the smaller uuid first. Nothing is lost by imposing the order,
-- precisely because the relation is symmetric — there is no direction the order could
-- be destroying. And the constraint is in the database rather than in the writer, so
-- the pair (A,B) and the pair (B,A) cannot both exist, which is what turns the primary
-- key above into a genuine "one relates edge per pair".
--
-- **What a reader must therefore do**: a 'relates' query for ticket X cannot match on
-- one column. It must look both ways — `from_ticket_id = X OR to_ticket_id = X` — and
-- take the other end as `CASE WHEN from_ticket_id = X THEN to_ticket_id ELSE
-- from_ticket_id END`. A writer must sort the pair before inserting or this CHECK
-- refuses it. `TicketLinkRepository` does both in one place so that no caller has to
-- remember, and `TicketLinkTest` asserts a relates edge is found from both ends.
ALTER TABLE ticket_links ADD CONSTRAINT ticket_links_relates_canonical_chk
  CHECK (type <> 'relates' OR from_ticket_id < to_ticket_id);

-- ---------------------------------------------------------------------------
-- A duplicates link does not move a status
-- ---------------------------------------------------------------------------
-- There is already a duplicate-shaped gesture in Kanso and it does move one:
-- `TriageService.decide` with 'duplicate' patches the ticket to `canceled` and writes
-- `triage_decisions.duplicate_of`. Making this link do the same would be wrong twice
-- over.
--
-- They are different kinds of thing. A triage ruling is an event: it happens once —
-- `findDecision` refuses a second — it records who ruled and when, and cancelling is
-- the *point*, because triage's job is to empty a queue. A link is current state: it
-- is drawn and undrawn from a ticket page by anyone who can edit, at any time, in
-- either direction. If drawing one cancelled a ticket, an editing gesture would
-- destroy work, and undrawing it would have to un-cancel — restoring a status the
-- ticket may have long since moved past, from a table that never recorded what the
-- status had been.
--
-- So the link is inert, and the two are reconciled the other way round: the ruling
-- also draws the link. `triage_decisions` keeps being the record of the decision — who,
-- when, and why this ticket is cancelled — and `ticket_links` becomes the single answer
-- to "what does this duplicate?", for hand-drawn and triage-drawn alike. One writer
-- more, one source of truth fewer; a ticket page that had to consult two tables and
-- reconcile them would be the second queue V26 warned about, wearing a join.

-- The primary key indexes (from_ticket_id, ...) already; walking the graph backwards
-- needs the other direction, as it did in V7. The type joins it because every backward
-- walk that matters is per type — the scheduler's is 'blocks' and only 'blocks'.
DROP INDEX ticket_dependencies_successor_idx;
CREATE INDEX ticket_links_to_idx ON ticket_links (to_ticket_id, type);
