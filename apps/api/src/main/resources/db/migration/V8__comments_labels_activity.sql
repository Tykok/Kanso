-- A comment belongs to one thing. A nullable pair with no constraint is how a third
-- case gets written by accident, so the check is the schema's, not the service's.
CREATE TABLE comments (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  ticket_id  UUID REFERENCES tickets(id) ON DELETE CASCADE,
  doc_id     UUID REFERENCES notion_docs(id) ON DELETE CASCADE,
  author_id  UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  body       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT comments_one_parent_chk CHECK (num_nonnulls(ticket_id, doc_id) = 1)
);

CREATE INDEX comments_ticket_idx ON comments (ticket_id, created_at);
CREATE INDEX comments_doc_idx    ON comments (doc_id, created_at);

CREATE TRIGGER comments_set_updated_at BEFORE UPDATE ON comments
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Resolved at write time, not re-parsed on read: renaming a user must not silently
-- drop a mention that was already delivered.
CREATE TABLE comment_mentions (
  comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (comment_id, user_id)
);

-- Team-scoped: two teams calling different things `sync` is normal, and one global
-- namespace would make them fight over the word.
CREATE TABLE labels (
  id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  name    TEXT NOT NULL,
  colour  TEXT NOT NULL DEFAULT 'indigo',
  UNIQUE (team_id, name),
  CONSTRAINT labels_colour_chk
    CHECK (colour IN ('indigo', 'blue', 'green', 'amber', 'rose', 'violet'))
);

CREATE TABLE ticket_labels (
  ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  label_id  UUID NOT NULL REFERENCES labels(id) ON DELETE CASCADE,
  PRIMARY KEY (ticket_id, label_id)
);

CREATE INDEX ticket_labels_label_idx ON ticket_labels (label_id);

-- Written in the same transaction as the change it records. A listener on pg_notify
-- would make the log lossy in exactly the case it exists to explain.
CREATE TABLE activity (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  entity_type TEXT NOT NULL,
  entity_id   UUID NOT NULL,
  actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,
  kind        TEXT NOT NULL,
  payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT activity_entity_type_chk
    CHECK (entity_type IN ('ticket', 'project', 'team', 'doc')),
  CONSTRAINT activity_kind_chk
    CHECK (kind IN ('created', 'status_changed', 'priority_changed', 'assigned',
                    'unassigned', 'renamed', 'scheduled', 'archived', 'commented',
                    'labelled', 'mirror_pushed'))
);

CREATE INDEX activity_entity_idx ON activity (entity_type, entity_id, created_at DESC);
CREATE INDEX activity_actor_idx  ON activity (actor_id, created_at DESC);

-- Screen 02 says the setting lives in the preferences. It did not.
ALTER TABLE user_preferences
  ADD COLUMN open_ticket TEXT NOT NULL DEFAULT 'panel';

ALTER TABLE user_preferences ADD CONSTRAINT user_preferences_open_ticket_chk
  CHECK (open_ticket IN ('panel', 'page'));
