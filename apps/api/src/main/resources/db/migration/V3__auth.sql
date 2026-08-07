-- OIDC login. Users are provisioned on first successful login: matched on
-- lower(email) if the address is already known (e.g. seeded as an assignee),
-- otherwise inserted.

ALTER TABLE users
  ADD COLUMN oidc_provider TEXT,
  ADD COLUMN oidc_subject  TEXT,
  ADD COLUMN avatar_url    TEXT,
  ADD COLUMN last_login_at TIMESTAMPTZ,
  ADD COLUMN active        BOOLEAN NOT NULL DEFAULT TRUE;

-- A subject is only unique within its issuer.
CREATE UNIQUE INDEX users_oidc_uniq
  ON users (oidc_provider, oidc_subject)
  WHERE oidc_subject IS NOT NULL;

-- Providers disagree about the case of an email; treat it case-insensitively.
CREATE UNIQUE INDEX users_email_lower_uniq ON users (lower(email));
