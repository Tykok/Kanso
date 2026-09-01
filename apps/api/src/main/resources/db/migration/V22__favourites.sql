-- What one person pinned to the top of their own sidebar.
--
-- Four kinds of thing in one table, and the interesting decision is how the kind is
-- written down, because this codebase already holds two answers to that and neither of
-- them is right here.
--
-- `activity.entity_type`/`entity_id` and `trash_entries.entity_type`/`entity_id` both
-- name a kind with a string and an id with no foreign key behind it. That is correct for
-- both: a log row is *supposed* to outlive the thing it describes, and `V11` says in as
-- many words that an entry pointing at nothing is a row the read skips. A favourite
-- cannot pay that price. It is drawn, at the top of the column, on every screen; a row
-- pointing at a project somebody deleted last month is either a broken line in the
-- sidebar or a silent omission that accumulates forever behind it. What a favourite
-- needs is the thing the string-and-id shape structurally cannot have: a real foreign
-- key, so the database removes the pin when the thing is gone.
--
-- So the kind is **which column is filled**, and every one of those columns is a foreign
-- key with `ON DELETE CASCADE`. `num_nonnulls(...) = 1` is the closed vocabulary,
-- enforced by the database exactly as `user_preferences` and `activity.kind` enforce
-- theirs — stronger, in fact, since a fifth kind here is a column and not a string a
-- misconfigured writer could smuggle in. `FavouriteKind` in Kotlin is the same
-- vocabulary from the other side, and `FavouriteSource` is one bean per kind, which is
-- the half of the trash's shape that *is* right for something that has to be rendered:
-- resolving four ids into four labels is four small beans and no branch in the service.
--
-- Nothing derived is stored. There is no `entity_type` column beside these four — it
-- would be a second copy of a fact the four columns already state, free to disagree with
-- them the first time something writes a row by hand.
--
-- **Four kinds and not five.** Tickets are deliberately out. Every kind here is a place
-- somebody goes back to and has a name they chose; a ticket is work that finishes, and
-- pinning it either rots at the top of the sidebar forever or needs an unpin-on-done
-- rule that throws away something a person explicitly asked for. It also does not draw:
-- a ticket is recognised as `KAN-142 · <title>`, which is two facts in a 200px column
-- that can hold one. Kanso already has three faster ways to reach one — the palette, the
-- filter box and the inbox. Doc *folders*, which the trash does hold, are out for a
-- flatter reason: `/docs` draws the tree and a folder has no route of its own, so a
-- favourite pointing at one would have nowhere to go.
--
-- **Ordering is insertion order** — `created_at`, oldest first — and there is no
-- `position` column. Manual reordering is the more useful feature and it does not fall
-- out of anything here: it needs a column, a reorder endpoint, a drag affordance and a
-- keyboard equivalent, none of which this buys. Oldest-first rather than newest-first so
-- that pinning a fifth thing does not renumber the four already there; a list that
-- reshuffles under the cursor is worse than a list in a dull order.
CREATE TABLE favourites (
  id         UUID PRIMARY KEY,

  -- Per person, never per instance. Two sidebars differ, and the cascade is what makes
  -- deleting an account take its pins with it rather than leaving them addressed to
  -- nobody.
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  -- Exactly one of these is set, and which one it is *is* the kind.
  team_id    UUID REFERENCES teams(id)       ON DELETE CASCADE,
  project_id UUID REFERENCES projects(id)    ON DELETE CASCADE,
  view_id    UUID REFERENCES saved_views(id) ON DELETE CASCADE,
  doc_id     UUID REFERENCES doc_pages(id)   ON DELETE CASCADE,

  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT favourites_one_target_chk
    CHECK (num_nonnulls(team_id, project_id, view_id, doc_id) = 1)
);

-- Pinning the same thing twice is one pin, not two rows and not an error — the gesture
-- is a toggle and the second press of it arrives as a duplicate whenever two tabs are
-- open. Partial, one per kind, because a full unique index over four nullable columns
-- would let the same team be pinned twice by two rows that differ only in which nulls
-- they carry.
CREATE UNIQUE INDEX favourites_team_uniq    ON favourites (user_id, team_id)    WHERE team_id    IS NOT NULL;
CREATE UNIQUE INDEX favourites_project_uniq ON favourites (user_id, project_id) WHERE project_id IS NOT NULL;
CREATE UNIQUE INDEX favourites_view_uniq    ON favourites (user_id, view_id)    WHERE view_id    IS NOT NULL;
CREATE UNIQUE INDEX favourites_doc_uniq     ON favourites (user_id, doc_id)     WHERE doc_id     IS NOT NULL;

-- The only read there is: one person's pins, in the order they made them.
CREATE INDEX favourites_owner_idx ON favourites (user_id, created_at);
