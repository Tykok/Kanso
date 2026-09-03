-- A place on a ticket for the things this schema was never going to guess.
--
-- The argument for doing it *now* is not that anybody asked for a `Severity` column. It is
-- that `V27` opened a door for something that is not a browser and `/api/mcp` put an agent
-- behind it, so `TicketResponse` and `kanso_get_ticket` have stopped being our shapes and
-- become somebody's integration. Every response that goes out without a slot for custom
-- fields is a shape a script will pin, and the day the slot appears the script is reading a
-- ticket it no longer recognises. A field nobody has defined yet costs one empty map on the
-- wire; a field added after the contract hardened costs every reader of it.
--
-- So this migration is deliberately ahead of demand, and the thing it is buying is that
-- `customFields: {}` is already in the answer before there is anything to put in it.
--
-- ---------------------------------------------------------------------------
-- Two tables, because a field is *defined* once and *valued* per ticket.
--
-- The alternative that keeps getting proposed is one jsonb blob on `tickets` — no second
-- table, no join, no definitions. It fails on the first question anybody asks of it: what
-- are the fields? With the blob, the answer is "whatever keys happen to appear across the
-- rows you looked at", so the settings screen cannot list them, a rename is a rewrite of
-- every ticket, a typo is a new field, and nothing can say a value is the wrong type
-- because nothing ever said what the right type was. The definition table is what makes a
-- custom field a field rather than a note.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Scoped to a team, and this is `labels` again on purpose.
--
-- `V8` put `labels.team_id` with a cascade and `LabelService` records the reason: a label
-- without a team is a word nobody agreed on. A field is the same kind of object and gets
-- the same answer, which also means it gets `TicketAccess.requireTeam` as its guard for
-- free instead of a new rule nobody else obeys.
--
-- *Project* scope was the other candidate and it is the one that quietly loses data. A
-- ticket's project is a column somebody edits — `TicketPatch.projectId` — so a
-- project-scoped field means dragging a ticket between two projects strands its values in
-- rows that no longer apply to it, and there is no good answer for what to do with them:
-- deleting silently discards work, keeping produces a ticket carrying values for a field
-- its project does not have. A ticket's *team* changes far more rarely and, when it does,
-- `TicketService.patch` is already the place where re-homing is argued about.
--
-- *Instance* scope was the third and it is simpler than this in every way except the one
-- that matters. Kanso has exactly one authority boundary — the team, as `TicketAccess`
-- enforces it — and an instance-wide field would be an object with no owner: anybody who
-- may write anywhere could add a column to everybody's board. Team scope makes "who may
-- define this" a question that has already been answered.
--
-- The consequence worth stating: a draft has no team (`V20`), so a draft has no fields
-- available and no values. That is the honest reading rather than a gap — a field is a
-- team's agreement about how it describes work, and a ticket nobody has filed is not yet
-- part of any such agreement. Its values start empty when a team claims it, which is the
-- same thing that happens to its identifier.
-- ---------------------------------------------------------------------------
CREATE TABLE custom_fields (
  id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  team_id  UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,

  -- What a person calls it, and the only handle a reader has. Unique per team below, so
  -- `Severity` means one thing inside a team and nothing across them.
  name     TEXT NOT NULL,

  -- The whole point of the definition table. Closed here and closed again in Kotlin as
  -- `CustomFieldType`, like every other vocabulary in this schema.
  type     TEXT NOT NULL,

  -- Enforced when a value is *written*, and deliberately **not** when a ticket is created.
  --
  -- This is the one constraint where the ticket's own argument turns back on itself.
  -- Refusing `POST /api/tickets` for a missing required field would mean that ticking a
  -- checkbox in a settings screen instantly breaks every script, every agent and every
  -- importer that creates tickets — which is precisely the failure this migration exists
  -- to prevent, arriving through the feature instead of through the schema. And it would
  -- break them retroactively: every ticket that already exists is missing the field too, so
  -- the flag would also make the backlog invalid the moment it was set.
  --
  -- So what `required` means here is narrower and honest: a value that exists may not be
  -- taken away. `CustomFieldService` refuses an explicit clear of a required field and
  -- names it; the composer marks it, because a client that knows the rule can ask the
  -- question at the right moment; and nothing anywhere refuses a ticket for not having one.
  required BOOLEAN NOT NULL DEFAULT FALSE,

  -- The choices, for `select` and for nothing else — an array of strings, jsonb because it
  -- is a list and a `TEXT[]` would need its own element-type argument to say the same thing.
  --
  -- What is asserted below is that it is an array, and that it is non-empty exactly when
  -- the type is `select`. What is *not* asserted is that every element is a string and that
  -- no element repeats, and the omission is the same one `V27` wrote down for
  -- `api_tokens.scopes`: a CHECK cannot walk an array without a subquery, and the read side
  -- makes the slack unobservable — the column is parsed into a Kotlin `List<String>` and a
  -- non-string fails there, at the one place that also refuses to write it. Said plainly
  -- rather than left to be discovered: the database closes the *shape*, and the element
  -- vocabulary is Kotlin's.
  options  JSONB NOT NULL DEFAULT '[]'::jsonb,

  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- Pre-checked in the service so a second `Severity` is a 409 rather than a 500 from the
  -- driver, exactly as `LabelService.create` does it. This is still the backstop.
  CONSTRAINT custom_fields_name_uniq UNIQUE (team_id, name),

  CONSTRAINT custom_fields_name_chk CHECK (length(btrim(name)) BETWEEN 1 AND 60),

  -- Four values, and four is the honest count of what renders today.
  --
  -- `text`, `number` and `boolean` are the three scalars a panel can draw with an input, a
  -- number input and a switch. `select` earns its place by being the one people actually
  -- ask for and by being the only one with a validation rule of its own — a value has to be
  -- one of the options — which is what keeps the write-side validator from being a type
  -- switch with nothing interesting in it.
  --
  -- Left out, with reasons, so the next person does not re-litigate them from scratch:
  --
  --   * `date`. Kanso already has an argument about dates that this would have to join —
  --     `KansoInstant` carries `hasTime` because "a day" and "an instant" render
  --     differently and a mirror has to know which — and a custom field cannot answer it
  --     without either picking one and being wrong half the time or growing a second
  --     column. It is a type with a design question attached, not a missing `WHEN`.
  --   * `multi_select`. The value stops being a scalar, so the `jsonb_typeof` guard below
  --     widens to arrays and every read that renders one value has to render a list. That
  --     is a different shape, not a fourth vocabulary entry.
  --   * `user`. A field whose value is a foreign key wants a real reference and a cascade
  --     when the account closes, which jsonb cannot give it. `assignees` is the answer to
  --     "who holds this" and the second answer would be free to disagree with it.
  --
  -- Adding one later is this CHECK re-stated, a branch in `CustomFieldType`, and a renderer.
  -- Note the register: the list is re-stated whole rather than amended, for the reason `V25`
  -- gives, and *this* migration is the authority on it from now on.
  CONSTRAINT custom_fields_type_chk CHECK (type IN ('text', 'number', 'boolean', 'select')),

  -- Both directions, because only one of them is the interesting failure. A `select` with
  -- no options is a control that renders as an empty dropdown nobody can satisfy — it makes
  -- the field unfillable and it does it silently. A `text` field carrying options is
  -- harmless and still wrong: it is a definition whose author believed it was a dropdown,
  -- and the equality here is what tells them otherwise at the moment they say it.
  CONSTRAINT custom_fields_options_chk CHECK (
    jsonb_typeof(options) = 'array'
    AND (type = 'select') = (jsonb_array_length(options) > 0)
  )
);

-- The settings screen's read: one team's fields. Ordered by name in the service rather than
-- here, so the index answers the `WHERE` and has no opinion about presentation.
CREATE INDEX custom_fields_team_idx ON custom_fields (team_id);

-- ---------------------------------------------------------------------------
-- The values.
--
-- **Why jsonb and not a column per type.** The alternative is `value_text`, `value_number`,
-- `value_bool`, and the invariant it needs is "exactly one of these is non-null, and it is
-- the one this field's type names". The second half of that sentence spans two tables, and a
-- CHECK cannot see another table — so the invariant that was the entire reason to prefer
-- typed columns is *not* enforceable by them, and lands in Kotlin regardless. What the
-- columns would still buy is the first half, which is a three-way `num_nonnulls(...) = 1`
-- guard against a shape nothing writes; what they cost is a migration that adds a column and
-- touches every read the day a fifth type lands, plus three nullable columns on the hottest
-- join in the app. One jsonb column gives the same real guarantees, and the type widening
-- becomes a widened CHECK.
--
-- jsonb also keeps the distinction natively: `jsonb_typeof` tells `"3"` from `3` from
-- `true`, which is what lets the guard below be a real assertion rather than a comment. A
-- `TEXT` column with the value stringified would have thrown that away and made every
-- boolean indistinguishable from the word "true".
--
-- **What it costs on read.** Two things, both accepted:
--
--   * No filtering or sorting *by* a custom field, today. Doing it would need an expression
--     index per field over `(value ->> 0)`, which means DDL at the moment somebody defines a
--     field — a migration written by a user, which this schema is not built for. So
--     `TicketFilterVocabulary` does not serve these, and a screen that wants `Severity =
--     high` is a later ticket that will have to answer the indexing question properly. The
--     column is deliberately not indexed on `value` to avoid implying otherwise.
--   * The value arrives as text and is parsed in Kotlin, so a malformed document is caught
--     at parse rather than by the column type. The guard below shrinks that to almost
--     nothing — three scalar shapes, no objects, no arrays, no JSON null — but "almost" is
--     the honest word and `FieldValueCodec` is where the rest of it lives.
--
-- What it does *not* cost is a query per ticket, which was the read-side worry worth having.
-- `TicketDetails.of` loads this the way it already loads assignees and docs: one statement
-- for the whole page, keyed by ticket id. A list of 200 tickets with three fields defined
-- costs one more query than it did before this migration, not 200 and not 600.
-- ---------------------------------------------------------------------------
CREATE TABLE ticket_field_values (
  ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,

  -- ON DELETE CASCADE, and the other two candidates were both worse.
  --
  -- *Refuse while values exist* makes a field that was named wrong on Tuesday undeletable
  -- until somebody clears it off four hundred tickets by hand. Nothing else in this schema
  -- asks for a preparatory sweep before a delete, and the screen that would have to offer
  -- one is a screen for a mistake.
  --
  -- *Orphan* — keep the value rows with a dangling `field_id` — is the one that sounds
  -- conservative and is actually the leak. A value's type lives in its definition, so a
  -- value whose definition is gone cannot be parsed, rendered, validated or exported; it is
  -- not data anybody can recover, it is bytes that accumulate and that every read has to
  -- learn to skip.
  --
  -- So deleting a definition deletes its values, the same shape and the same argument as
  -- `ticket_labels`. What makes it safe to offer is that the caller is told the size of it
  -- first: `CustomFieldService.list` derives a value count per field — computed on read,
  -- never stored — so the confirmation can say how many tickets are about to lose a value
  -- instead of asking for a signature on a blank cheque. That is the same courtesy
  -- `DispositionCounts` pays before a team is emptied.
  field_id  UUID NOT NULL REFERENCES custom_fields(id) ON DELETE CASCADE,

  -- One value, one row. `NOT NULL` and no `'null'::jsonb` accepted below, which together
  -- make "this ticket has no value for this field" *the absence of a row* and nothing else.
  --
  -- The alternative is a row holding JSON null, and it gives the same fact two spellings.
  -- Every read would then have to treat them alike, one of them would eventually forget,
  -- and the required-field rule in particular would have to ask "is this missing, or
  -- present-and-empty" — a question with no answer worth having. Clearing a value deletes
  -- the row, for the reason `V27` refuses a `revoked_at`: the row a reader reads is the row
  -- clearing removes, so "the value is gone" is structural rather than maintained.
  value     JSONB NOT NULL,

  -- The pair, which is also the uniqueness: a ticket holds at most one value per field. A
  -- surrogate id would have permitted two rows for one field and made "the value" a query
  -- that could return two, exactly as `ticket_labels` avoided.
  CONSTRAINT ticket_field_values_pkey PRIMARY KEY (ticket_id, field_id),

  -- The scalar shapes, closed — and this is the answer to "a jsonb column that takes
  -- anything is not a typed field, it is a bag".
  --
  -- It is genuinely a narrowing and not a gesture: an object, an array and a JSON null are
  -- all refused at the database, so the set of documents that can be in this column is three
  -- shapes wide. What it *cannot* do is check the value against its own definition — that
  -- comparison needs `custom_fields.type`, which is another table, which a CHECK cannot
  -- reach. There is no version of this constraint that closes that gap; a trigger could, and
  -- would put the product's validation rules in PL/pgSQL where no test reads them and where
  -- the error message cannot name the field the way `FieldValueCodec` does.
  --
  -- So the split is stated rather than implied: **the database closes the shape, and
  -- `FieldValueCodec` closes the agreement with the definition, on every write.** Neither
  -- half is redundant — the CHECK catches anything that reaches the table without passing
  -- the service, and the service is the only thing that can say "Severity expects a number".
  CONSTRAINT ticket_field_values_value_chk CHECK (jsonb_typeof(value) IN ('string', 'number', 'boolean'))
);

-- Two reads want this direction rather than the primary key's: the value count per field
-- that the delete confirmation prints, and the cascade above, which would otherwise sweep
-- the whole table to find one field's rows.
CREATE INDEX ticket_field_values_field_idx ON ticket_field_values (field_id);

-- ---------------------------------------------------------------------------
-- One more word for the feed.
--
-- Every other scalar of a ticket is narrated — `status_changed`, `priority_changed`,
-- `renamed`, `estimated` — and there is no generic `updated` on purpose, because a feed that
-- can only say "Tykok updated KAN-142" is a feed nobody reads. A custom field is a scalar of
-- a ticket like the rest of them, so leaving it out would make it the one edit that happens
-- silently, and "when did Severity become high, and who said so" is exactly the question a
-- history is kept for.
--
-- One kind for all four types rather than one per type: what changed is *which field*, and
-- the field's id and name travel in the payload — the way `labelled` carries the label's
-- name, so that a feed still reads correctly after the definition is renamed or deleted.
--
-- **The list restated here is `V23`'s, not `V8`'s.** That matters and it is the whole reason
-- this block is not three lines: Postgres has no `ALTER CONSTRAINT` for a CHECK's
-- expression, so widening one means re-stating the vocabulary whole, and re-stating it from
-- the wrong ancestor silently *revokes* whatever a later migration added. `V8` wrote eleven
-- words; `V17`, `V21` and `V23` each widened the list, and `V23` is the newest statement of
-- it — `carried_over`, `estimated` and `health_posted` are its, and a list copied from `V8`
-- would drop all three while Kotlin carried on accepting them. `V23` records the same trap
-- from the other side: the two migrations before it were written on branches that could not
-- see each other, and the union is the merge's job.
--
-- So: fourteen words from `V23`, plus `field_set`. From now on *this* migration is the
-- authority, and the next one to widen it should re-state this list rather than `V23`'s.
-- ---------------------------------------------------------------------------
ALTER TABLE activity DROP CONSTRAINT activity_kind_chk;

ALTER TABLE activity ADD CONSTRAINT activity_kind_chk
  CHECK (kind IN ('created', 'status_changed', 'priority_changed', 'assigned',
                  'unassigned', 'renamed', 'scheduled', 'archived', 'commented',
                  'labelled', 'mirror_pushed', 'carried_over', 'estimated',
                  'health_posted', 'field_set'));
