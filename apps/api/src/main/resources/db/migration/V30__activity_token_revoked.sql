-- A revocation leaves a trace, and an account's feed gets a word for it.
--
-- `V27` revokes an API token by deleting its row, which is the right call and is not what
-- changes here: the row the filter reads is the row revocation removes, so "the token stops
-- immediately" stays structural rather than maintained. What it cost was history — "who
-- revoked what, and when" had no answer — and `V27` already said where the answer belongs:
-- "If 'who revoked what, when' is ever wanted, `activity` is where an event belongs, not a
-- tombstone in the credential table." This is that event.
--
-- Bringing the row back would answer nothing. It held a digest of a secret that no longer
-- works, so a kept one is a row nobody can ask a question of.
--
-- ---------------------------------------------------------------------------
-- Two vocabularies widen, not one
-- ---------------------------------------------------------------------------
--
-- `activity` closes both of its describing columns, and an event about a credential is
-- outside both of them:
--
--   * `entity_type` gains `'user'`. The subject is an *account* — a token belongs to a
--     member, never to a team or a ticket — and every existing value names a unit of work.
--     Filing this against the owner's id rather than the token's is deliberate: the token's
--     row is deleted by the time the event is written, so an `entity_id` pointing at it
--     would name nothing that exists, and "show me this account's revocations" is the
--     question somebody actually asks.
--
--   * `kind` gains `'token_revoked'`. Its own word for the reason there is no generic
--     `updated` and the reason `V21` bought `'estimated'`: a feed that can only say
--     "Tykok changed something" is a feed nobody reads. `'archived'` was the nearest
--     existing word and it is the wrong one — nothing is archived, a credential is
--     destroyed, and the two differ in whether anything can be got back.
--
-- Dropped and re-added rather than widened in place, for the reasons `V17` and `V21`
-- record: Postgres has no `ALTER CONSTRAINT` for a CHECK's expression, and re-stating the
-- whole list keeps the constraint readable in `\d activity` as one line per deployment
-- rather than as a chain of amendments. The re-add revalidates every existing row, which
-- is what we want — the list is otherwise the only claim that no stray value ever got in.
--
-- **Re-stating the whole list means re-stating the whole *current* list, and the trap is
-- that the precedent file is not it.** `V21` is the migration this one imitates, and
-- copying its list is wrong: `V23` added `'health_posted'` afterwards. Doing exactly that
-- silently dropped the word, and the failure did not look like a migration bug — ten
-- `ProjectHealthTest` cases went red on a `DataIntegrityViolationException` from a
-- `CHECK` nobody had touched in that feature. Nothing in this file's own subject area
-- fails, which is what makes it worth writing down: the authority for the list is the
-- newest migration that set it, `grep`ed for, not the one whose shape is being copied.
--
-- ---------------------------------------------------------------------------
-- This file does not travel alone
-- ---------------------------------------------------------------------------
--
-- `ActivityController.list` takes `entityType` and `entityId` as free query parameters and
-- gates exactly one entity type — a ticket's, because only a ticket can be a draft. Every
-- other type is readable by any signed-in member, and that was sound while every id in the
-- vocabulary named something a reader could already list.
--
-- `'user'` breaks that assumption, so the guard ships in the same commit as the word. A
-- `token_revoked` payload carries the *name* somebody gave a credential — "prod deploy",
-- "laptop CLI" — which is a map of another person's integrations. Without the guard,
-- `?entityType=user&entityId=<anyone>` would hand it over with no new endpoint and nothing
-- looking wrong. Opening a vocabulary is a permissions change here, which is the sentence
-- worth carrying to the next person who adds an `entity_type`.
--
-- No new column and no new table: what the event says is `actor_id`, `entity_id`,
-- `created_at` and two strings of payload, all of which `activity` already holds.

ALTER TABLE activity DROP CONSTRAINT activity_entity_type_chk;

ALTER TABLE activity ADD CONSTRAINT activity_entity_type_chk
  CHECK (entity_type IN ('ticket', 'project', 'team', 'doc', 'user'));

ALTER TABLE activity DROP CONSTRAINT activity_kind_chk;

ALTER TABLE activity ADD CONSTRAINT activity_kind_chk
  CHECK (kind IN ('created', 'status_changed', 'priority_changed', 'assigned',
                  'unassigned', 'renamed', 'scheduled', 'archived', 'commented',
                  'labelled', 'mirror_pushed', 'carried_over', 'estimated',
                  'health_posted', 'token_revoked'));
