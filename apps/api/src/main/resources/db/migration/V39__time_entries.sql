-- KAN-26 asks for the one number in this product that **cannot be derived**, and that is the
-- whole reason it needs a table when `KAN-23` needed none.
--
-- Cycle time shipped with no migration because `activity` had already timestamped every
-- status change since `V8`: "how long was this ticket in flight" was on disk, unrecognised.
-- Hours worked are not on disk and never will be. Nothing in this schema knows that Élie
-- shut the laptop at 18:00, spent forty minutes of the train home on KAN-142, and billed
-- none of Tuesday afternoon to anybody. An elapsed hour is a fact about the *ticket*; a
-- worked hour is a fact about the *person*, asserted by them, and there is no join that
-- recovers an assertion nobody made. `CycleTimeService` says its numbers are pessimistic on
-- purpose because they are measured; every number this table feeds is exactly as accurate as
-- the person who typed it, which is both worse and the only thing an invoice can rest on.
--
-- So the house rule is not being bent here, it is being read correctly: **derived values are
-- computed on read, and this is not one.** The rule's other half is what this migration is
-- careful about, and it is stated once: *the entry is stored, the total never is.*
--
-- ---------------------------------------------------------------------------
-- Why there is no `tickets.logged_minutes`, and why `V38` is the reason.
--
-- The obvious denormalisation is a running total on the ticket row, incremented on every
-- entry. `SUM(minutes) GROUP BY ticket_id` over an index on `ticket_id` costs nothing at the
-- size this table reaches — an agency logging forty entries a day fills it at fifteen
-- thousand rows a year — so performance was never the argument for the column. It is worth
-- writing down what the column would actually have cost, because it is not the usual
-- "somebody forgets to update it".
--
-- It would have made **stopping a timer look like editing the ticket**, and `V38` landed
-- yesterday measuring what that does. Every stopped timer would `UPDATE tickets`, so:
--
--   1. "Récemment mis à jour" would reorder on a total nobody edited — `V38`'s bug, with a
--      new writer.
--   2. Worse, and this is the one that is not cosmetic: `NotionPoller.kansoWins` decides a
--      conflict by asking whether `updated_at > notion_synced_at`. A stopped timer would
--      make that true with no edit behind it, so Kanso would win a conflict it has no claim
--      to and queue a corrective push that **reverts a real edit somebody made in Notion**.
--
-- The cure would then have been to add `logged_minutes` to `set_updated_at_on_edit`'s ignore
-- list on `tickets` — a third bookkeeping column beside the four mirror ones — and to hope
-- the next person adding a total remembers. Instead: **nothing in this feature writes a
-- column on `tickets` at all.** Inserting into a child table does not fire
-- `tickets_set_updated_at`, so `V38`'s question — does this bookkeeping write count as an
-- edit — has no occasion to be asked. That is the answer, and it is structural rather than
-- listed: a future column cannot forget to be on a list that does not exist.
--
-- The two rules agreeing is worth noticing. "Compute the total on read" and "a bookkeeping
-- write must not look like an edit" were argued from different places, a year apart, and
-- they recommend the same schema. Neither is a style preference.
--
-- ---------------------------------------------------------------------------
-- One table, two shapes: a duration somebody typed, and a clock the app is running.
--
-- The ticket does not say which of the two it wants, and both are needed — a stopwatch for
-- the work you are doing now, a typed duration for the call you took on Tuesday and are
-- logging on Thursday. Two tables was the alternative and it is worse: every read of a
-- ticket's hours would be a `UNION`, and the day somebody wants to *correct* a clocked entry
-- to forty minutes because they forgot to stop it, the row would have to migrate between
-- tables to stay correctable.
--
-- So one table, and the shape is: **`minutes` is the datum, `started_at` is provenance.**
--
--   * **running** — `started_at` set, `minutes` NULL. There is no duration yet. Elapsed time
--     is `now() - started_at`, computed on read, exactly as the house rule wants for the one
--     part of this feature that genuinely is derivable.
--   * **settled** — `minutes` set. `started_at` present if a clock produced it, NULL if a
--     person typed it.
--
-- **Stopping a timer writes `minutes`, and that is not a stored derived value.** The
-- distinction is `now()`. While the clock runs, the elapsed time is a function of the
-- current instant and must not be frozen; the moment it stops, the duration is a fact about
-- a finished stretch of the past that no longer has any other source. Keeping it as
-- `ended_at - started_at` instead would look purer and would break the one gesture this
-- feature exists to allow: a person who left a timer running overnight has to be able to say
-- "that was forty minutes, not nine hours", and correcting the *duration* by rewriting
-- `ended_at` would mean lying about when they stopped working. `KAN-81` is the whole
-- argument for not storing a timestamp somebody will later have to rewrite; this stores the
-- number they are allowed to correct, and leaves `started_at` alone as the record of when
-- the clock actually started.
--
-- `minutes >= 0` and **no upper bound in the schema**. A cap here would be a trap rather
-- than a guard: a timer forgotten for six weeks would fail its own `stop`, leaving a row the
-- person can never settle and an index slot they can never free. What a *person asserts* is
-- bounded instead, in `TimeEntryService`, at thirty-one days — an agency bills monthly and an
-- entry larger than the invoicing period is a typo, not a claim — and what the *clock*
-- measures is accepted whatever it says, then corrected by hand. Zero is legal and reachable
-- only from a stop under thirty seconds, which is a real thing fingers do; it displays as
-- `0m` and can be deleted.
--
-- ---------------------------------------------------------------------------
-- Minutes, as an integer.
--
-- Not seconds: nobody bills a second, and a column precise to one invites a screen that
-- reads like a lap timer. Not `NUMERIC` hours, which is the shape a spreadsheet would use
-- and the shape that cannot add up — twenty minutes is 0.333…, and a quarter's worth of
-- thirds of an hour summed in decimal drifts off the total the same rows would give in
-- minutes. An integer minute is exact, sums exactly, and round-trips every duration a person
-- actually types.
--
-- ---------------------------------------------------------------------------
-- `spent_on DATE`, and why it is not derived from either timestamp.
--
-- A timesheet is grouped by day, and neither timestamp on this row can supply it.
-- `created_at` is when the entry was *typed*, which for anybody who logs Friday's work on
-- Monday is the wrong day — and that is the normal case, not the edge one. `started_at` is
-- right for a clocked entry and absent for a typed one.
--
-- So it is its own column, `DATE` for the reason `V10` gives `cycles.starts_on`: a day is a
-- day, and a timezone on it would make "Tuesday's hours" depend on who is asking.
-- **The client sends it**, because the browser is the only party that knows the person's
-- actual day. `user_preferences.timezone` exists and is deliberately *not* read here: no
-- server code has ever read that column — it is a rendering preference — and making it
-- load-bearing for a billable record would silently file a Paris midnight against the wrong
-- day for every person who never set it. The server's own `LocalDate.now()` is the fallback
-- when the field is absent, which is wrong for at most the couple of hours either side of
-- UTC midnight and is honest about being a fallback.
--
-- Fixed when the row is created and **never recomputed on stop**: a timer started at 23:40
-- and stopped at 00:20 belongs to the day it started, which is what a person writes on a
-- timesheet and what they will look for it under.
--
-- ---------------------------------------------------------------------------
-- One running timer per person, in the database.
--
-- `time_entries_one_running_per_person_idx` is a unique index over `user_id` partial on
-- `minutes IS NULL`, and it is the constraint this design is proudest of: it says "a person
-- has one pair of hands" in eleven words of DDL, exactly, with no trigger and no read
-- before write.
--
-- **Per person and not per ticket**, and the index is where that is decided. Two people
-- clocking the same ticket at once is ordinary — pairing, or a call they both bill — and
-- nothing here objects. One person clocking two tickets at once is not a billing question at
-- all: it is a **broken clock**. Neither of those two durations is a claim anybody made; the
-- app made both, from one stretch of wall time, and it will settle both. Per-ticket
-- uniqueness would have permitted precisely that while sounding like a constraint.
--
-- ---------------------------------------------------------------------------
-- Settled entries may overlap, and that is a decision, not an omission.
--
-- Nothing stops two rows from covering the same hour. Refusing it was considered and
-- refused twice over:
--
--   * It is **not enforceable on half the table.** A typed entry has no interval — no
--     `started_at`, and `minutes` says how long but never when — so an exclusion constraint
--     over a `tstzrange` would police clocked rows and silently wave typed ones through. A
--     constraint that holds for some rows is worse than none: it reads as a guarantee.
--   * It would put Kanso in the middle of a **question it has no standing on**. Whether two
--     hours can be billed to two clients is the agency's policy — a shared call, a
--     retainer, pairing — and a tool that refuses the row is overruling a commercial
--     decision it cannot see. Kanso records what people say they did.
--
-- The one overlap that *is* the tool's business is the app clocking one person twice, and
-- that is the index above.
--
-- ---------------------------------------------------------------------------
-- No new activity kind, and this paragraph is the audit trail for that.
--
-- `activity_kind_chk`'s current authority is **`V36`** — grepped for in `db/migration`, and
-- worth stating because `V36`'s own header names `V35` as the authority it inherited from
-- and a reader copying that sentence would name the wrong file. `V36` holds seventeen kinds.
-- This migration adds none and does not restate the list.
--
-- Not because a kind was awkward, but because it would be a **second copy of a row that
-- already exists**. `activity` is the narrator of last resort for facts with no other home:
-- `V36` gave `pull_request_linked` a kind precisely because a link "has no other narrator",
-- and `V30` gave `token_revoked` one because revocation *deletes* the row it would have been
-- read from. A time entry is the opposite case in both respects — it is a durable row with
-- its own `user_id`, its own `created_at` and its own `updated_at`, and the timesheet on the
-- ticket lists every one of them with who and when. "Who logged these fourteen hours" is
-- answered by selecting them. A parallel `time_logged` kind would be a vocabulary to keep in
-- step with a table for no question it could answer that the table cannot.
--
-- ---------------------------------------------------------------------------
-- The foreign keys.
--
-- `ticket_id ON DELETE CASCADE`, like every other child of `tickets` since `V1`: hours
-- against a ticket that no longer exists bill nothing.
--
-- `user_id NOT NULL ON DELETE CASCADE`, which is the *unusual* half and is deliberately not
-- the `ON DELETE SET NULL` this schema gives authorship. `project_updates.author_id` and
-- `ticket_pull_requests.linked_by` are nullable because what they attach to survives its
-- author — a health note still says something once the person is gone, and `V36` spells out
-- that "a closed account does not un-draw the links they made". An entry has nothing that
-- survives: it is *only* an assertion about one person's hours, and "somebody spent three
-- hours" bills nobody at no rate. `NOT NULL` is also what makes the running-timer index
-- exact — a nullable owner would collapse every orphaned running row into one index slot and
-- leave timers no `stop` could ever reach. The cascade is unreachable through the product
-- either way: nothing in this codebase deletes a `users` row, accounts are retired by
-- `users.active`, and the clause states the intent rather than opening a route.
--
-- ---------------------------------------------------------------------------
-- `set_updated_at`, `V2`'s original, and not `V38`'s.
--
-- The same reading `V38` gives the ten tables it left alone: this table does no mirror
-- bookkeeping, nothing sweeps it, and every `UPDATE` it will ever see is a person correcting
-- a duration or the app settling a clock they started. "This row was written" is all
-- `updated_at` has to mean here, which is all it has ever meant outside the three tables the
-- mirror touches.
-- ---------------------------------------------------------------------------

CREATE TABLE time_entries (
  id         UUID PRIMARY KEY,
  ticket_id  UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  user_id    UUID NOT NULL REFERENCES users(id)   ON DELETE CASCADE,

  -- The timesheet day. See the header: sent by the client, never recomputed.
  spent_on   DATE NOT NULL,

  -- NULL means the clock is still running. Everything else about this table follows from
  -- that one sentence.
  minutes    INTEGER,

  -- When the clock started, or NULL for a duration somebody typed. Never rewritten — a
  -- correction moves `minutes`.
  started_at TIMESTAMPTZ,

  -- What the hour was spent on, in the words that go on the invoice line. Optional: a
  -- stopwatch pressed mid-task has nothing to say yet, and forcing a note would get "work".
  note       TEXT,

  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- A row is a settled duration or a running clock. Neither is not a row.
  CONSTRAINT time_entries_settled_or_running_chk
    CHECK (minutes IS NOT NULL OR started_at IS NOT NULL),

  -- Bounded below only. The header says why there is no ceiling: a cap the clock can exceed
  -- is a row nobody can settle.
  CONSTRAINT time_entries_minutes_chk
    CHECK (minutes IS NULL OR minutes >= 0),

  -- Trimmed to NULL by the service rather than stored empty, so `note IS NOT NULL` means
  -- there is something to read. The ceiling is an invoice line, not a comment thread.
  CONSTRAINT time_entries_note_chk
    CHECK (note IS NULL OR (length(note) BETWEEN 1 AND 500))
);

-- **A person has one pair of hands.** The whole rule, in the database, for the cost of an
-- index that only ever holds as many rows as there are people currently clocking something.
CREATE UNIQUE INDEX time_entries_one_running_per_person_idx
  ON time_entries (user_id) WHERE minutes IS NULL;

-- The cascade's direction, and the timesheet read: every entry on one ticket.
CREATE INDEX time_entries_ticket_idx ON time_entries (ticket_id);

-- "My hours, this week" — the read a person's own timesheet does, and the one the
-- running-timer lookup narrows into. `spent_on` second so the index answers the range.
CREATE INDEX time_entries_user_spent_idx ON time_entries (user_id, spent_on);

CREATE TRIGGER time_entries_set_updated_at
  BEFORE UPDATE ON time_entries
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
