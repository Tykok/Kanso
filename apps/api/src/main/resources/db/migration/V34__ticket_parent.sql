-- Sub-tickets: one self-reference on `tickets`, and a deliberate refusal to make it a
-- tree.
--
-- A big piece of work broken into the four things somebody will actually pick up is the
-- shape every ticket tracker grows, and Kanso has been expressing it with a naming
-- convention and a project. A project is the wrong container for it: a project is a
-- shipping unit with a status, a health note and a timeline of its own, and "these four
-- rows are the same job" needs none of that.

-- ---------------------------------------------------------------------------
-- `ON DELETE SET NULL`, and what it means out loud
-- ---------------------------------------------------------------------------
-- Deleting a parent **promotes its children rather than destroying work**. Each becomes
-- a top-level ticket, keeping its title, its status, its estimate, its assignees, its
-- comments and its place in whatever cycle it was in.
--
-- `CASCADE` is the reading to argue against, and `doc_folders.parent_id` is `CASCADE` —
-- so the difference is worth stating rather than assuming. A folder is a container and
-- nothing else: it has no status, nobody is assigned to it, and deleting one is a
-- statement about everything inside it. A parent ticket is a *ticket*. Somebody wrote it,
-- estimated it, and may have finished half of it, and its children are not its contents
-- — they are work with their own owners, their own estimates and their own place in a
-- cycle. Erasing four people's rows because somebody deleted the heading above them is
-- the kind of quiet data loss no undo in this product would reach: deletion here goes
-- through `trash`, and the trash restores the row it took, not the rows that vanished
-- underneath it.
--
-- The visible cost is that promoted children look, for a moment, like four unexplained
-- top-level tickets. That is the honest failure of the two — the work is all still there
-- and re-parenting is one gesture — and it is the failure a reader can see and fix.
ALTER TABLE tickets ADD COLUMN parent_id UUID REFERENCES tickets(id) ON DELETE SET NULL;

-- A ticket cannot be its own parent. The deeper refusal is in Kotlin, the same place
-- team and folder parenting put theirs: no CHECK can express reachability. What is
-- different here is that the Kotlin rule is not a reachability walk either — see below.
ALTER TABLE tickets ADD CONSTRAINT tickets_not_own_parent_chk
  CHECK (parent_id IS NULL OR parent_id <> id);

-- ---------------------------------------------------------------------------
-- One level, and why that is the feature rather than a shortcut
-- ---------------------------------------------------------------------------
-- Nesting is capped at a single level: a ticket with a parent cannot itself be a parent.
-- The column would hold an arbitrary tree — it is the same self-reference `teams` and
-- `doc_folders` use for one — and `TicketService` refuses to build one. Three reasons,
-- in increasing order of how much they cost:
--
-- 1. **Nothing can draw the third level.** The wiki's `Follow-ups` page records that the
--    sidebar caps indentation at two levels and a four-level nest renders levels 2, 3 and
--    4 at the same indent, leaving the rows visually indistinguishable. The main list is
--    virtualised over one flattened index, so it has exactly the same ceiling. Storing a
--    depth the product cannot render is how a nest becomes unreachable rather than deep.
--
-- 2. **Parent progress stops having one meaning.** "3 of 5 done" is unambiguous over
--    direct children. Over a tree it has to choose between counting direct children —
--    where a child holding four unfinished grandchildren counts as one unfinished thing
--    — and counting leaves, where a parent's progress moves because somebody split a
--    child in two. Both are defensible and neither is guessable from the number.
--
-- 3. **A cycle becomes structurally impossible instead of merely refused.** The rules
--    that produce the cap are "the proposed parent must not itself have a parent" and
--    "the ticket being re-parented must not have children". Under both, a loop cannot be
--    built: closing A -> B -> A needs B to have a parent when A asks for it, and it
--    needs A to have a child when B asks. Either rule alone refuses it. So there is no
--    recursive ancestor walk here to get wrong, and none of the "cycles are impossible,
--    so the walk terminates" reasoning that `TeamRepository.ancestorIds` has to carry.
--
-- The cap is a product decision and it lives in Kotlin, with a sentence a person can
-- read, rather than in a constraint. `TicketParentTest` proves each refusal.

-- Every read of this column asks the same question — "the children of this one" — so the
-- index is on the column alone. Partial, because the overwhelming majority of tickets
-- have no parent and an index entry per NULL would be most of the table describing the
-- absence of a relationship.
CREATE INDEX tickets_parent_idx ON tickets (parent_id) WHERE parent_id IS NOT NULL;
