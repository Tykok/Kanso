-- Connecting Notion by consent rather than by paste.
--
-- Until now the only way in was an *internal* integration: create it in Notion,
-- copy its secret, hunt a page id out of a URL, then remember to share that page
-- from its ••• menu. The last step is silent when skipped — the token is valid and
-- nothing works — and it is the one people skip.
--
-- A *public* integration replaces three of those four steps with one screen:
-- Notion's own consent page is where the person picks which pages Kanso may see,
-- so the sharing happens there and the token arrives over the wire. What is left is
-- creating the integration once and pasting its client id and secret, which is the
-- same shape Google already has, and for the same reason: neither Notion nor Google
-- will issue a client to an instance whose hostname they have never heard of.
--
-- The paste-a-token path stays. An instance that already has an internal
-- integration should not have to re-do it to keep working, and an instance with no
-- outbound browser cannot complete a consent screen at all.
ALTER TABLE instance_settings
  ADD COLUMN notion_client_id         TEXT,
  ADD COLUMN notion_client_secret_enc BYTEA,
  -- What the consent screen told us, kept so the settings screen can say *which*
  -- workspace is connected rather than only that one is. A token by itself names
  -- nothing a person recognises.
  ADD COLUMN notion_workspace_id      TEXT,
  ADD COLUMN notion_workspace_name    TEXT,
  -- The integration user the token acts as. `HttpNotionClient.botUserId()` already
  -- asks Notion for this on every test; storing what the exchange handed us saves
  -- the round trip and, more usefully, lets echo suppression compare against a
  -- value that was true at connection time.
  ADD COLUMN notion_bot_id            TEXT;
