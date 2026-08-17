-- Who needs to know.
--
-- The sibling of the activity log, not a copy of it: an activity row says what
-- happened to a thing, one row per change, readable by anyone looking at that
-- thing. A notification row says a *person* has to be told, so it is per
-- recipient and carries a read mark. One status change produces one activity row
-- and as many notifications as there are people who were not the one who made it.
--
-- `entity_id` has no foreign key: it points at whichever of three tables
-- `entity_type` names, and Postgres cannot express that. A row whose entity has
-- since been deleted resolves to a null subject on read and is still shown — being
-- told a ticket was assigned to you is true whether or not the ticket survived, and
-- silently dropping the row would make the inbox's count disagree with its list.
CREATE TABLE notifications (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id   UUID NOT NULL,

  -- Nullable, and not only because the actor's account may go: a conflict with the
  -- Notion mirror has no Kanso actor at all, and the inbound poller has no user to
  -- name. Whoever edited the page in Notion goes in the payload, as text, because
  -- they may well have no `users` row here.
  actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,

  -- Beyond the columns the spec names, and for the same reason `activity` carries
  -- one: every sentence the inbox draws has a number or a name in it that no join
  -- can recover. "The project slipped by 3 days" is not derivable from the project,
  -- and a conflict is two values of one field — there is nowhere else for them.
  payload     JSONB NOT NULL DEFAULT '{}'::jsonb,

  read_at     TIMESTAMPTZ,

  -- `clock_timestamp()`, not `now()`. `now()` is the transaction's start time, so a
  -- change that notifies four people — or a cascade that notifies four tickets'
  -- assignees — would stamp every row identically and leave "newest first" deciding
  -- by random uuid. The question this column answers is a wall-clock one.
  -- `SyncJobRepository.reclaimStuck` makes the same distinction for the same reason.
  created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

  -- Closed in the database, following `user_preferences`. `sync_failed` is in the
  -- vocabulary although nothing writes it: the failures tab reads `sync_jobs`
  -- directly (the queue is where that fact already lives, and a stored copy would
  -- keep claiming a push failed after it was retried and succeeded). The kind
  -- exists so a future push notification has a name to be written under.
  CONSTRAINT notifications_kind_chk CHECK (
    kind IN ('assigned', 'mentioned', 'sync_failed', 'status_moved',
             'comment_replied', 'project_slipped', 'conflict')
  ),
  CONSTRAINT notifications_entity_type_chk CHECK (
    entity_type IN ('ticket', 'project', 'doc')
  )
);

-- The inbox's own query: one person's rows, newest first.
CREATE INDEX notifications_inbox_idx ON notifications (user_id, created_at DESC);

-- The sidebar badge asks only "how many unread", on every render. Partial, so the
-- index holds the unread ones alone and stays small as the read ones accumulate.
CREATE INDEX notifications_unread_idx ON notifications (user_id) WHERE read_at IS NULL;
