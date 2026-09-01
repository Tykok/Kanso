-- A ticket may exist before anybody has decided whose it is.
--
-- `V2` made `team_id` NOT NULL with a one-line argument: "without this a ticket with no
-- project belongs to nobody: no team view, no short identifier". Both halves of that are
-- still true — what changed is that *belonging to nobody yet* turned out to be a state
-- worth having rather than a bug to forbid. Somebody types a thought during a meeting and
-- files it later; forcing the filing first is how the thought does not get typed.
--
-- So the column becomes nullable, and the rest of this migration is about what a row is
-- still not allowed to be.
--
-- **`number` goes nullable with it, and the two are chained.** The number comes from
-- `teams.ticket_counter` and is unique only within a team — `KAN-142` is the pair, not the
-- integer. A row with a team and no number has no name; a row with a number and no team
-- has a name nothing can pronounce, and would collide with the first team that reaches
-- that count. Neither is a state any reader could make sense of, so
-- `tickets_team_number_together_chk` refuses both rather than trusting six services to
-- write the pair atomically. It is the same two-sided guard the statuses have: Kotlin
-- refuses it with a sentence, and the database refuses it whatever wrote the row.
--
-- `UNIQUE (team_id, number)` is left exactly as it was and needs no exception: Postgres
-- treats two NULLs as distinct in a unique index, so any number of team-less rows coexist
-- under it while the constraint keeps doing its real job the moment a team is named.
--
-- **`created_by` is added because losing the team loses the only access rule there was.**
-- Who may edit a ticket is decided by its team (`TicketAccess`) — membership of it, or of
-- a team above it, or nobody having claimed the chain at all. A ticket outside every team
-- is outside the only boundary this product has, and answering "then everybody may edit
-- it" would turn a private draft into instance-wide writable scratch. So a team-less
-- ticket belongs to the person who wrote it: they may see and edit it, instance
-- admins may, and nobody else. The moment it gains a team the team's rule takes over and
-- this column stops deciding anything — it stays on the row as history, not as a second
-- permission system running beside the first.
--
-- Nullable, and null for every row that already exists: nobody recorded an author for the
-- tickets written before today, and inventing one would hand somebody rights they were
-- never given. A null author on a team-less ticket therefore resolves to "admins only",
-- which is the safe reading — and no existing row is team-less anyway, so no ticket
-- shipped today changes hands.
--
-- ON DELETE SET NULL rather than CASCADE, following `trash_entries.deleted_by`: closing an
-- account must not delete the work, and the draft simply falls back to the admins.
ALTER TABLE tickets ALTER COLUMN team_id DROP NOT NULL;
ALTER TABLE tickets ALTER COLUMN number  DROP NOT NULL;

ALTER TABLE tickets ADD CONSTRAINT tickets_team_number_together_chk
  CHECK ((team_id IS NULL) = (number IS NULL));

ALTER TABLE tickets ADD COLUMN created_by UUID REFERENCES users(id) ON DELETE SET NULL;

-- The drafts of one person, which is the only list this column is ever read as. Partial on
-- `team_id IS NULL` because that is the whole of the set worth indexing: once a ticket has
-- a team, `created_by` is history nothing filters on, and an index over every ticket ever
-- written would be maintained on every insert to answer a question nobody asks.
CREATE INDEX tickets_drafts_idx ON tickets (created_by) WHERE team_id IS NULL;
