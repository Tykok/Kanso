-- A seat that reads and never writes.
--
-- Kanso's argument is that the people who do not work in the tracker read the same data
-- as the people who do — that is what the Notion mirror is for — and a free read-only
-- seat is the direct corollary. So this is a fourth value on a vocabulary that already
-- exists, and not a fourth axis: `instance_role` still says what you may do to the
-- instance, `team_members.role` still says what you belong to, and nothing here adds a
-- third column for anybody to keep in step with the other two.
--
-- `viewer` is placed *below* `member` in the list for a reader's benefit only; the
-- column is a TEXT and Postgres orders nothing by it. The ranking that matters lives in
-- `InstanceRole` in Kotlin, as two properties — `canConfigureInstance` (owner, admin)
-- and `mayWrite` (everyone but viewer) — and both are read from one place each.
--
-- Dropped and re-added rather than widened in place, for the reason `V17` gives: Postgres
-- has no `ALTER CONSTRAINT` for a CHECK's expression, and re-stating the whole list keeps
-- `\d users` readable as one line per deployment instead of a chain of amendments. The
-- re-add revalidates every existing row, which is the point — the constraint is the only
-- standing claim that no unknown role was ever written, and a widening that skipped the
-- check would quietly stop being that claim.
ALTER TABLE users DROP CONSTRAINT users_instance_role_chk;

ALTER TABLE users ADD CONSTRAINT users_instance_role_chk
  CHECK (instance_role IN ('owner', 'admin', 'member', 'viewer'));

-- Invitations carry a role, and its vocabulary is narrower than the users' one on
-- purpose: `V5` refused `owner` here because ownership is not something a link can hand
-- out — it is whoever completed the first-run wizard. `viewer` has no such objection.
-- Quite the opposite: inviting somebody as a reader is the *main* way a viewer seat comes
-- into existence, since the whole premise is people who are not going to be given a
-- working account. Leaving this constraint alone would have made the role reachable only
-- by creating a member first and demoting them, which is a worse door for the more common
-- case.
--
-- `users_single_owner` is untouched. It is a partial unique index on `instance_role =
-- 'owner'` and nothing about a fourth value reaches it; readers are unlimited by design,
-- which is the feature.
ALTER TABLE invitations DROP CONSTRAINT invitations_role_chk;

ALTER TABLE invitations ADD CONSTRAINT invitations_role_chk
  CHECK (instance_role IN ('admin', 'member', 'viewer'));
