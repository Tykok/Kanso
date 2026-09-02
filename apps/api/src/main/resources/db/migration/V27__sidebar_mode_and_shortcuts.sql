-- Three sidebar modes where there were two, a place to keep remapped keys, and the
-- switch for the top bar's three view controls. One table, one round trip, because all
-- three are read by the same first paint.
--
-- ---------------------------------------------------------------------------
-- `sidebar_mode` replaces `sidebar_visible`, and replaces it rather than joining it.
--
-- A boolean could carry two of the three states and no arrangement of two booleans
-- carries three without also spelling a fourth that means nothing — `visible = false,
-- hover = true` would have to be read as "hidden but revealed", which is not a mode
-- anybody chose. So the boolean goes, and the two rows it could describe are carried
-- over: `false` was "no column at all", which is `hidden`; `true` was the 248px column,
-- which is `pinned` and is also the default, so it needs no UPDATE of its own.
--
-- `hover` is new and therefore nobody's stored preference. That is correct: it is a mode
-- somebody has to choose, and inferring it for accounts that never asked would move the
-- sidebar of every existing reader on deploy.
--
-- The CHECK is the two-sided guard the other three enums on this table already have
-- (`V5`, and the same argument `tickets.status` makes): a Kotlin enum so a typo in a
-- service is a compile error, and a constraint so a row written by psql, a fixture or a
-- future migration is refused by the database rather than read back as a mode the
-- interface has no rendering for.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- `shortcuts` holds **overrides only** — never a full copy of the defaults.
--
-- A stored copy would freeze today's key set into every account that exists on the day
-- this ships, and a default improved later would reach nobody: the row would keep
-- answering with the old key forever, and there would be no way to tell "I chose this"
-- from "this is what the app happened to bind in September". `{}` is the honest
-- representation of "I never changed anything", and it is the default for that reason.
--
-- **Validation is split, and the split is deliberate.** The API validates *shape*: an
-- object of string keys to arrays of short strings, capped on entries and on chord
-- length. It cannot validate *meaning*, because the action registry is a front-end
-- module — the server has no way to know whether `ticket.rename` is an action, and a
-- server that pretended to would need redeploying every time the web bundle renamed one.
-- The client validates meaning, and its `mergeBindings` ignores ids it does not
-- recognise, so an action deleted in a later version does not make a stored preference
-- unreadable.
--
-- No CHECK here, for that reason: jsonb can be constrained to "is an object", which the
-- application already guarantees, and cannot be constrained to "is a set of bindings the
-- interface understands" without teaching Postgres the registry. The two-sided guard
-- above works because the vocabulary is three words that change once a year; this
-- vocabulary is the whole keyboard and it changes with the front end.
-- ---------------------------------------------------------------------------
--
-- `show_view_controls` rides along because it is the same table and the same round trip.
-- Filter, Group and Order are the three chords drawn as three buttons, for the reader who
-- would rather click; the bell, the breadcrumb and the `×` are not optional, and these
-- are. Default TRUE: a control you have to discover a setting to be shown is not a
-- control, and the reader who does not want them is the one who has an opinion.
ALTER TABLE user_preferences
  ADD COLUMN sidebar_mode        TEXT    NOT NULL DEFAULT 'pinned',
  ADD COLUMN shortcuts           JSONB   NOT NULL DEFAULT '{}',
  ADD COLUMN show_view_controls  BOOLEAN NOT NULL DEFAULT TRUE;

-- Before the constraint and before the drop: the value has to be legal by the time the
-- CHECK is validated, and readable until it has been carried over.
UPDATE user_preferences SET sidebar_mode = 'hidden' WHERE sidebar_visible = FALSE;

ALTER TABLE user_preferences ADD CONSTRAINT user_preferences_sidebar_mode_chk
  CHECK (sidebar_mode IN ('pinned', 'hover', 'hidden'));

ALTER TABLE user_preferences DROP COLUMN sidebar_visible;
