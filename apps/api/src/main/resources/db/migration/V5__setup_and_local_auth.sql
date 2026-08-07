-- First-run setup: instance configuration, local accounts, per-user preferences.

-- ---------------------------------------------------------------------------
-- Instance configuration. One row, enforced by the primary key: this is the
-- settings of *this* Kanso, not a collection of anything.
--
-- Secrets are stored encrypted (AES-GCM) with a key held outside the database —
-- a key stored next to the ciphertext would protect nothing.
-- ---------------------------------------------------------------------------
CREATE TABLE instance_settings (
  id                        BOOLEAN PRIMARY KEY DEFAULT TRUE,
  setup_completed_at        TIMESTAMPTZ,
  notion_parent_page_id     TEXT,
  notion_token_enc          BYTEA,
  google_client_id          TEXT,
  google_client_secret_enc  BYTEA,
  created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT instance_settings_single_row CHECK (id)
);

INSERT INTO instance_settings (id) VALUES (TRUE);

CREATE TRIGGER instance_settings_set_updated_at BEFORE UPDATE ON instance_settings
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- Local accounts. `instance_role` is about the instance (who may configure it);
-- `team_members.role` is about a team. Two different questions, two columns.
-- ---------------------------------------------------------------------------
ALTER TABLE users
  ADD COLUMN password_hash TEXT,
  ADD COLUMN instance_role TEXT NOT NULL DEFAULT 'member';

ALTER TABLE users ADD CONSTRAINT users_instance_role_chk
  CHECK (instance_role IN ('owner', 'admin', 'member'));

-- The owner can only be claimed once: two simultaneous setup requests cannot both
-- win, whatever the application layer believes.
CREATE UNIQUE INDEX users_single_owner ON users ((instance_role))
  WHERE instance_role = 'owner';

-- ---------------------------------------------------------------------------
-- Invitations. The token is stored hashed: a database dump must not hand out
-- working invitation links.
-- ---------------------------------------------------------------------------
CREATE TABLE invitations (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  token_hash    TEXT UNIQUE NOT NULL,
  email         TEXT,
  instance_role TEXT NOT NULL DEFAULT 'member',
  created_by    UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ NOT NULL,
  accepted_at   TIMESTAMPTZ,
  accepted_by   UUID REFERENCES users(id) ON DELETE SET NULL,
  CONSTRAINT invitations_role_chk CHECK (instance_role IN ('admin', 'member'))
);

CREATE INDEX invitations_pending ON invitations (expires_at) WHERE accepted_at IS NULL;

-- ---------------------------------------------------------------------------
-- Login attempts, for rate limiting.
--
-- In Postgres rather than in memory so the limit survives a restart and holds
-- across instances — an attacker should not be able to reset it by waiting for a
-- deploy.
-- ---------------------------------------------------------------------------
CREATE TABLE login_attempts (
  id        BIGSERIAL PRIMARY KEY,
  email     TEXT NOT NULL,
  ip        TEXT,
  at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  succeeded BOOLEAN NOT NULL
);

CREATE INDEX login_attempts_by_email ON login_attempts (lower(email), at DESC);
CREATE INDEX login_attempts_by_ip ON login_attempts (ip, at DESC) WHERE ip IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Per-user preferences. Closed vocabularies, same reasoning as ticket status:
-- the database is where an unknown value gets refused.
-- ---------------------------------------------------------------------------
CREATE TABLE user_preferences (
  user_id          UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  theme            TEXT NOT NULL DEFAULT 'system',
  accent           TEXT NOT NULL DEFAULT 'indigo',
  density          TEXT NOT NULL DEFAULT 'comfortable',
  sidebar_visible  BOOLEAN NOT NULL DEFAULT TRUE,
  show_sync_badges BOOLEAN NOT NULL DEFAULT TRUE,
  show_status_bar  BOOLEAN NOT NULL DEFAULT TRUE,
  default_team_id  UUID REFERENCES teams(id) ON DELETE SET NULL,
  onboarded_at     TIMESTAMPTZ,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT user_preferences_theme_chk   CHECK (theme IN ('system', 'light', 'dark')),
  CONSTRAINT user_preferences_accent_chk  CHECK (accent IN ('indigo', 'blue', 'green', 'amber', 'rose', 'violet')),
  CONSTRAINT user_preferences_density_chk CHECK (density IN ('comfortable', 'compact'))
);

CREATE TRIGGER user_preferences_set_updated_at BEFORE UPDATE ON user_preferences
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
