-- The shop window: which tickets a stranger may read, and how a stranger votes.
--
-- `public` defaults to FALSE, and that default is the whole safety property. Every
-- ticket that already exists, and every ticket created by code that has never heard
-- of this column, is private — so publishing is an act somebody performed, never a
-- state a row drifted into.
ALTER TABLE tickets ADD COLUMN public BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial: the public projection only ever asks for the true rows, and on an instance
-- where twelve tickets out of nine hundred are public, an index over all nine hundred
-- would be read past rather than used.
CREATE INDEX tickets_public_idx ON tickets (public) WHERE public;

-- Votes, without accounts.
--
-- `voter_key` is a keyed hash of the voter's address and the current day — never the
-- address itself. The trade is deliberate and it is not the cautious-looking one: an
-- IP column would stop a determined visitor from voting twice, and would also mean
-- that a database dump of a self-hosted instance hands out a list of who looked at
-- the roadmap. A day-scoped hash stops the accidental double vote (the reload, the
-- second tab), lets the determined one through, and stores nothing about anybody.
-- The key is computed in VoterKeys.kt, where the same argument is made against the
-- code that does it.
--
-- The hash is *keyed* (HMAC, per-instance secret) rather than plain: an unsalted
-- SHA-256 of an IPv4 address is reversible by enumerating four billion inputs, which
-- would make this column an IP column with extra steps.
CREATE TABLE votes (
  ticket_id  UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  voter_key  TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- One vote per voter per ticket. The primary key *is* the constraint: an upsert
  -- onto it is what makes a second click a no-op instead of an error the client has
  -- to interpret.
  PRIMARY KEY (ticket_id, voter_key)
);

-- Where a contributor should look first: the three or four paths that carry the
-- problem, each with the reason it is on the list.
--
-- A table rather than prose inside `description` because screen 28 draws it as a
-- list with two columns, and because a path is a thing that goes stale — a rename
-- should be able to find it. `position` orders it: the order a maintainer put the
-- files in is advice, and sorting alphabetically would throw that advice away.
CREATE TABLE ticket_files (
  ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  path      TEXT NOT NULL,
  note      TEXT,
  position  INTEGER NOT NULL,
  PRIMARY KEY (ticket_id, path)
);

CREATE INDEX ticket_files_order_idx ON ticket_files (ticket_id, position);
