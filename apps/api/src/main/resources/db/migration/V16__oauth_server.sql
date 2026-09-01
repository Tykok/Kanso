-- The door an agent comes through.
--
-- Three of the four sections below are not ours. They are
-- `oauth2-registered-client-schema.sql`, `oauth2-authorization-schema.sql` and
-- `oauth2-authorization-consent-schema.sql`, copied from
-- spring-security-oauth2-authorization-server 7.1.0, and they must stay that way:
-- they are the library's contract with its own `Jdbc*` implementations, so a column
-- improved here is a runtime failure there. This is the one migration in Kanso that
-- is not argued from first principles, and that is the argument.
--
-- Two mechanical substitutions are applied, and they are not a departure from that
-- contract — they are the contract. Each file's own header instructs them for
-- PostgreSQL: every `timestamp` becomes `timestamptz`, so an instant is stored as the
-- instant it was, and every `blob` becomes `text`, which PostgreSQL has instead. The
-- library ships no PostgreSQL variant of these files; it ships one file plus that
-- header. Nothing else is touched, and nothing is hand-translated.
--
-- `V16` rather than `V15`: `V15__notion_import_origin.sql` is the import branch's, now
-- merged. The ordering constraint the plan carried is therefore already satisfied, and
-- Flyway has no gap to tolerate.


-- ---------------------------------------------------------------------------
-- oauth2-registered-client-schema.sql — the two substitutions applied
-- ---------------------------------------------------------------------------
/*
IMPORTANT:
    If using PostgreSQL:
        - update ALL columns defined with 'timestamptz' to 'timestamptz', to ensure that time instants are stored accurately.
    If using MySQL:
        - add 'preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' to JDBC connection URL
          to ensure that time instants are stored accurately. See https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html
*/
CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamptz DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

-- ---------------------------------------------------------------------------
-- oauth2-authorization-schema.sql — the two substitutions applied
-- ---------------------------------------------------------------------------
/*
IMPORTANT:
    If using PostgreSQL:
        - update ALL columns defined with 'text' to 'text', as PostgreSQL does not support the 'text' data type.
        - update ALL columns defined with 'timestamptz' to 'timestamptz', to ensure that time instants are stored accurately.
    If using MySQL:
        - add 'preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' to JDBC connection URL
          to ensure that time instants are stored accurately. See https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html
*/
CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamptz DEFAULT NULL,
    authorization_code_expires_at timestamptz DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamptz DEFAULT NULL,
    access_token_expires_at timestamptz DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamptz DEFAULT NULL,
    oidc_id_token_expires_at timestamptz DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamptz DEFAULT NULL,
    refresh_token_expires_at timestamptz DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamptz DEFAULT NULL,
    user_code_expires_at timestamptz DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamptz DEFAULT NULL,
    device_code_expires_at timestamptz DEFAULT NULL,
    device_code_metadata text DEFAULT NULL,
    PRIMARY KEY (id)
);

-- ---------------------------------------------------------------------------
-- oauth2-authorization-consent-schema.sql — unchanged — it has neither
-- ---------------------------------------------------------------------------
CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

-- ---------------------------------------------------------------------------
-- Ours: provenance.
--
-- The member owns what their agent did — the token acts as them, and the activity
-- feed still says their name. This says which application typed it, so the day a
-- plan turns out to be wrong nobody has to guess where it came from.
--
-- TEXT rather than UUID, against the house convention: the library's client id is a
-- varchar of its own choosing, and a foreign key that has to convert is a foreign
-- key that will not. ON DELETE SET NULL because a revoked client must not take the
-- history of what it did with it.
-- ---------------------------------------------------------------------------
ALTER TABLE activity
  ADD COLUMN via_client_id TEXT REFERENCES oauth2_registered_client(id) ON DELETE SET NULL;
