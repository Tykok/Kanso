-- Point provenance at the client id that actually reaches a service.
--
-- `V16` declared `activity.via_client_id REFERENCES oauth2_registered_client(id)` — the
-- library's *surrogate* key. `McpBearerFilter` deliberately puts the **public**
-- `client_id` on the principal instead, and argues correctly for it: the surrogate means
-- nothing outside its own table, while the public id is what the client presents, what
-- registration returned to it, and what a member is shown in Settings. Both decisions are
-- defensible; together they made the column unwritable. `withId(UUID.randomUUID())` and
-- `.clientId(UUID.randomUUID())` are two different UUIDs, so the only value a service
-- layer holds would have violated the foreign key on insert — a failure plan two would
-- have discovered at its first provenance write, and one `AgentRightsTest`'s tripwire
-- cannot catch, because that tripwire fires on the column having a value.
--
-- The column moves rather than the principal. Keeping the surrogate off the principal is
-- the right call for the reason the filter gives, and adding it *alongside* would be two
-- identifiers for one client travelling together so that one of them can be written down.
--
-- The unique index is what makes `client_id` a legal foreign-key target, and it is not an
-- improvement to a schema this codebase copied verbatim and promised not to touch. It is
-- that schema's own precondition: `JdbcRegisteredClientRepository.findByClientId` selects
-- on `client_id` and takes a single result, so two rows sharing one is already a runtime
-- failure inside the library. The index moves that failure to the insert, which is the
-- earlier and cheaper end. The library's `CREATE TABLE` is left exactly as it was.
--
-- A new migration rather than an edit to `V16`: `V16` has run — on every test container
-- and on any instance that has already brought this branch up — and changing its checksum
-- costs a repair on each of them for two statements' worth of history.

CREATE UNIQUE INDEX oauth2_registered_client_client_id_key
    ON oauth2_registered_client (client_id);

ALTER TABLE activity
    DROP CONSTRAINT activity_via_client_id_fkey;

-- ON DELETE SET NULL for the reason `V16` gave, unchanged: a revoked client must not take
-- the history of what it did with it. Nothing in Kanso deletes a client row today —
-- `GrantService.revoke` removes the consent and the tokens and leaves the registration —
-- so this is the shape of a promise rather than a path that runs.
ALTER TABLE activity
    ADD CONSTRAINT activity_via_client_id_fkey
    FOREIGN KEY (via_client_id) REFERENCES oauth2_registered_client (client_id)
    ON DELETE SET NULL;
