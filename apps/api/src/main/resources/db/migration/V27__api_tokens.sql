-- A door for something that is not a browser.
--
-- `SecurityConfig` has had exactly two ways in and both of them end in a session cookie,
-- so every non-browser caller Kanso is about to grow — the outbound webhook signer, the
-- GitHub push, a CLI, an SDK, a cron job on somebody's laptop — has had nothing to
-- present. `V18` looks like it already solved this and does not: an OAuth2 grant is
-- *interactive*, it begins with a person on a consent screen and a browser redirect, and
-- a thing with no browser cannot start one. So this is the non-interactive half, and the
-- two are deliberately different doors rather than one door with two shapes.
--
-- What is reused, though, is the vocabulary. `scopes` here holds the same two strings
-- `OAuthScopes` already defines and the same two a consent screen already renders. A
-- second permission vocabulary — `api:read`, `token:write`, anything — would be a second
-- answer to "what may an agent do", free to disagree with the first the day a third value
-- is argued about, and the disagreement would be invisible because the two would be read
-- by different filters.

CREATE TABLE api_tokens (
  id           UUID PRIMARY KEY,

  -- A token is a member's, never an instance's, and the cascade is the point rather than
  -- housekeeping: deactivating an account already stops a cookie, and a long-lived
  -- credential that outlived the person it speaks for is the failure this column exists
  -- to make impossible. Deleting the row deletes the token, because that is all a token
  -- is (see `hash` below).
  user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  -- What the person called it. Not decoration: the settings screen lists rows that are
  -- otherwise indistinguishable from each other — same owner, same scopes, opaque
  -- digest — and "revoke the right one" is the whole gesture this table has to support.
  -- A list of four unnamed tokens is a list nobody dares revoke from.
  name         TEXT NOT NULL,

  -- The first few characters of the secret, kept so the screen can print
  -- `kanso_pat_AbCdEf…` beside the name and somebody holding a token in a `.env` file can
  -- tell which row is theirs.
  --
  -- This is the one place Kanso stores something derived and does not compute it on read,
  -- and the exception is forced rather than chosen: it is derived from the *plaintext*,
  -- and the plaintext is precisely what this table refuses to keep. Deriving it from
  -- `hash` is not possible, which is the property `hash` is there for.
  --
  -- Six characters of a 256-bit secret is 36 bits given away and 220 bits left, so this
  -- is a label and not a head start. `ApiToken.PREFIX_LENGTH` is the same number from the
  -- other side.
  prefix       TEXT NOT NULL,

  -- SHA-256 of the presented secret, lowercase hex. Never the secret.
  --
  -- **Not BCrypt**, unlike `users.password_hash`, and the difference is not an oversight
  -- in one of the two places. BCrypt exists to make guessing a *human-chosen* secret
  -- expensive, and it buys that by being slow and per-row salted. Both properties are
  -- wrong here:
  --
  --   * Salted per row means a digest cannot be looked up. Finding the token a request
  --     presented would mean a BCrypt comparison against every row in this table, on
  --     every request — a table scan whose cost is measured in seconds, on the hot path
  --     of the API this ticket exists to open.
  --   * Slow buys nothing against 32 bytes of `SecureRandom`. There is no dictionary for
  --     it and no rainbow table over 2^256; the stretching is protecting a search space
  --     that is not being searched.
  --
  -- So the digest is fast and unique, and *that* is what makes the lookup a single
  -- index probe. What is preserved is the only property that matters: this column plus
  -- the rest of the row cannot produce a working token, so a stolen database dump is not
  -- a set of live credentials.
  hash         TEXT NOT NULL,

  -- `OAuthScopes`, in the database. Closed here as well as in Kotlin, like every other
  -- vocabulary in this schema, and the containment operator says it in one line rather
  -- than as a chain of ORs.
  scopes       TEXT[] NOT NULL,

  -- Null until the token is used for the first time, which is the honest reading of "has
  -- this ever been picked up" — a `created_at` copied in here would make an unused token
  -- indistinguishable from one used the second it was made, and telling those two apart
  -- is the main thing this column is looked at for.
  --
  -- Written on use, best-effort and coarsened; `ApiTokenFilter` carries the argument for
  -- both halves of that.
  last_used_at TIMESTAMPTZ,

  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- The lookup, and a collision guard in the same object. Two rows sharing a digest would
  -- mean either 32 bytes of `SecureRandom` repeating — which is not a thing that happens —
  -- or something writing rows it did not generate, and the second is worth refusing at
  -- the database rather than discovering as a `singleOrNull` returning two.
  CONSTRAINT api_tokens_hash_uniq UNIQUE (hash),

  CONSTRAINT api_tokens_name_chk CHECK (length(btrim(name)) BETWEEN 1 AND 80),

  -- Two conditions, because containment alone would accept the empty array — and an empty
  -- scope list is a credential that authenticates and permits nothing, which is not a
  -- narrower token but a broken one. The upper bound is the size of the vocabulary.
  --
  -- What is *not* asserted here is distinctness: `{kanso:read,kanso:read}` satisfies both
  -- conditions. A CHECK cannot express "no repeats" without a subquery, and the read side
  -- makes the slack unobservable — the column is read into a Kotlin `Set`, so a repeat
  -- collapses before anything can act on it. Said plainly rather than left for somebody
  -- to notice: the database closes the *vocabulary*, and the set-ness is Kotlin's.
  CONSTRAINT api_tokens_scopes_chk CHECK (
    scopes <@ ARRAY['kanso:read', 'kanso:write']::TEXT[]
    AND cardinality(scopes) BETWEEN 1 AND 2
  )
);

-- The settings screen's only read: one member's tokens, newest first, because the one
-- somebody wants to look at is almost always the one they just made.
CREATE INDEX api_tokens_owner_idx ON api_tokens (user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- There is no `revoked_at`, and that is a decision rather than an omission.
--
-- Revoking deletes the row. The alternative — a timestamp, and every read filtering on
-- `revoked_at IS NULL` — makes revocation a property that a query can *forget*, and the
-- query that forgets it is a filter that authenticates a token somebody already killed.
-- One `WHERE` clause missing from one lookup is the entire failure, it is silent, and no
-- test that does not specifically look for it would see it.
--
-- Deleting has none of that shape: the row the filter reads is the row revocation
-- removes, so "the token stops immediately" is structural instead of maintained. That is
-- the same argument `McpBearerFilter` records for OAuth grants — "the row it reads is the
-- row revocation deletes, which is what makes 'no window' true rather than aspirational"
-- — and the two doors should not disagree about what revoked means.
--
-- What is given up is a history of revoked tokens, and it is worth nothing: the row holds
-- a digest of a secret that no longer works, so a kept one answers no question anybody
-- asks. If "who revoked what, when" is ever wanted, `activity` is where an event belongs,
-- not a tombstone in the credential table.
-- ---------------------------------------------------------------------------
