-- Effort in points, on the ticket.
--
-- One nullable column and one CHECK, and both halves of that are the design.
--
-- **Nullable, and never defaulted to 0.** "Not estimated yet" and "estimated at zero"
-- are two different states, and a default would collapse them into one the day this
-- column ships — silently, for every row that already exists. Everything the later
-- work wants to compute out of this column is an average of some kind (a team's
-- velocity, a person's, how long a ticket of this size usually takes), and an average
-- over a pile of invented zeroes is a number that reads as measured and is not. So the
-- absence is stored as an absence, every sum here excludes it rather than adding zero,
-- and every screen that shows a sum also shows how many tickets it could not speak for.
--
-- **The scale is a closed vocabulary.** A truncated Fibonacci sequence — 1, 2, 3, 5, 8,
-- 13 — and nothing between its values. The gaps are the whole point: a free integer
-- invites someone to write 7, and 7 is an argument about half a point rather than an
-- estimate, while a short scale forces the choice and keeps two people's 5 comparable.
-- Enforced here as well as in Kotlin, following `tickets_status_chk`: a vocabulary the
-- database does not refuse is a vocabulary that drifts to whatever the importer, a
-- migration or a psql session happened to write. 13 is the ceiling on purpose — work
-- that does not fit on the scale is work to split, not a bigger number.
--
-- SMALLINT rather than INTEGER: the largest value the constraint admits is 13, and the
-- column is read on every ticket of every list.
--
-- Nothing derived is stored, as `V10` already argues for the cycle's own numbers: there
-- is no points total on `cycles`, none on `projects` and none per person. A sum is
-- wrong from the moment the next ticket is estimated, and a cached one is a number
-- nobody can explain when it disagrees with the list underneath it.
ALTER TABLE tickets ADD COLUMN estimate SMALLINT;

ALTER TABLE tickets ADD CONSTRAINT tickets_estimate_chk
  CHECK (estimate IS NULL OR estimate IN (1, 2, 3, 5, 8, 13));
