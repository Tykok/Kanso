# A team's own words for its work

Kanso ships six statuses and every team gets the same six. A support team calls its
first column *Nouveau*, not *Backlog*, and has no use for *In review*; a hardware team
wants *Waiting on parts* between *In progress* and *Done*. Today both are told to
translate in their heads, and the translation is the kind that quietly stops happening.

`KAN-28` lets a team define its own list: add, rename, reorder, remove. Each status
declares one of the five categories, and **the category is what the rest of Kanso
reads** — which is the whole reason this ticket was blocked until `KAN-1` derived them
from the enum, and the whole reason it is now an afternoon rather than a rewrite.

The premise worth stating before anything else: **a status is a word, a category is a
fact.** A burndown may not care what a team calls the end of the line, and must never
guess which end it is.

## Staged, after the plan met the domain

`KAN-28` ships **the words and their order**. `KAN-90` ships **adding and removing**, and
the split is not a hedge — it is where the type system draws the line.

`Ticket.status` is typed `DefaultStatus` throughout the domain: ten types carry one, 47
sites name a constant, 29 read `.wire`, `.label` or `.category` off it. Renaming and
reordering leave every one of those valid, because the six *keys* do not move — the
catalogue supplies labels and positions and nothing else. A seventh status makes that
field unrepresentable as an enum, so it becomes a value resolved by joining the catalogue,
and about twenty of those sites turn into product decisions rather than mechanics: `KAN-18`'s
pull-request transition, the vocabulary four MCP tools advertise, the Notion select, the
seven `IN (...)` lists that filter by category in SQL and become joins, the public
roadmap's grouping, the cycle rollover.

So the sections below describe the whole design, and the parts belonging to `KAN-90` are
marked. Everything unmarked is this ticket. Three rules move wholesale to `KAN-90`, and
one of them stops existing until then: with the keys fixed, every team has the same six,
so **a ticket moving between teams never needs a rebase** — the composite foreign key is
satisfied by construction.

## What this is not

- **Free-form statuses.** Every status declares a category. Without one, nothing that
  reasons about finished work has an anchor: the burndown, cycle time, WIP, the cycle
  rollover, velocity, a project's progress bar, the public roadmap's ordering. `KAN-1`
  exists to end that guessing and this must not reopen it.
- **A workflow engine.** No allowed transitions, no gates, no required fields per
  status. A status is a name and a category. Anything that refuses a move belongs to
  whoever asks for it, with its own ticket.
- **Per-status colour.** A status is drawn in its category's hue. Five hues already
  exist and already mean the five things; a colour picker would let a team paint
  *Résolu* red and make every screen in Kanso lie a little.
- **Per-project or per-cycle statuses.** The team is the unit. A project spans teams by
  design (`KAN-9`), so a project-level list would have to arbitrate between two of them.
- **Renaming the categories.** The five are Kanso's vocabulary for its own reasoning,
  never printed as a bucket header inside a team's own scope, and shared by the MCP
  tools and the public roadmap. They are not a team's business.

---

## Read this first

Six facts in the repository decide most of what follows.

- **The category is already derived, never stored.** `TicketStatus.category` maps six
  values onto five categories in `domain/Model.kt`, and its docstring says why the
  mapping *is* the definition. With a catalogue, the mapping moves onto the row — one
  column, `category` — and that docstring's promise ("no second copy on disk that could
  disagree") has to be kept a different way: the column is the only place a category
  lives for a team-defined status, and the enum keeps its own for the defaults it seeds.
- **`tickets_status_chk` is a `CHECK` listing the six.** `V2__sync_engine.sql` wrote it;
  it has to go, and what replaces it is the interesting half of this spec.
- **`StatusOrder.WORKFLOW` is an order the server renders into SQL.** A grouped page is
  ordered by bucket before the view's sort, as a `CASE` producing a rank, so the page
  boundary is cut against that sequence. It is deliberately a second copy of the web's
  `WORKFLOW_ORDER`, pinned equal by `StatusOrderTest.kt` and `status-order.test.ts`.
  Per-team statuses make that order **data**, and the two constants survive only as the
  default a new team is seeded with — see "The order stops being a constant".
- **`TicketResponse.status` is read by things that are not this web app.** The custom
  fields docstring is explicit: the row is read by bearer-token scripts and by an MCP
  agent, and "a key that appears later is a key that breaks them". A shape change here
  is not a refactor, it is a break — which is why `status` stays the same string.
- **Saved views store literal statuses.** `V10__cycles_and_views.sql` keeps a view's
  filters as JSON, statuses included, and `SavedViewService` reads them back. A status
  key that changed when somebody renamed a label would silently empty a saved view.
- **A ticket can have no team.** `KAN-9` made drafts ordinary: `tickets.team_id` is
  nullable, and `tickets_drafts_idx` exists for them. Whatever constrains a status has
  to let a draft through without a team to ask.

---

## The catalogue

One table, one row per status per team.

```sql
CREATE TABLE team_statuses (
  team_id   uuid NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  key       text NOT NULL,
  label     text NOT NULL,
  category  text NOT NULL
    CHECK (category IN ('backlog', 'unstarted', 'started', 'completed', 'canceled')),
  position  int  NOT NULL,
  PRIMARY KEY (team_id, key)
);

CREATE UNIQUE INDEX team_statuses_label_uniq ON team_statuses (team_id, lower(label));
```

`category` is a `CHECK` and not a table, for the reason `activity_kind_chk` is one: the
vocabulary is closed, the enum mirrors it, and a new category would be a migration
somebody has to write on purpose rather than a row somebody can insert.

**The key is derived from the label, never typed.** Lowercased, accents folded,
everything that is not a letter or a digit collapsed to a single `_`, ends trimmed.
`In Progress`, `in progress` and `IN  PROGRESS` all produce `in_progress`, so the
primary key refuses the second one — two spellings of one word are the same row rather
than a rule a screen has to enforce. A label with no letter or digit in it (`…`, a bare
emoji) has no key and is refused with that sentence.

`team_statuses_label_uniq` is the second half of the same guard, and it exists for the
error message. Without it the refusal arrives as a conflict on a key nobody typed;
with it, the API can say *this team already has a status called "En cours"*. The
precedent is `users_email_lower_uniq`, which is in the schema for the same reason.

**The key is immutable.** Renaming *En cours* to *En chantier* writes `label` and leaves
`in_progress` alone, because saved views, the filter grammar and every bookmarked URL
hold keys. A rename that emptied a saved view would be a rename that loses work.

`position` carries **no** unique index, and that is deliberate rather than lax. A
reorder is a swap, and a unique index is checked per row as an `UPDATE` walks: swapping
two positions in one statement raises a violation halfway through unless the constraint
is deferred, which is a footgun to buy an invariant nothing needs. Readers sort by
`(position, key)` — the key breaking a tie no screen should be able to notice — so a gap
and a duplicate are both invisible, and a reorder is one `UPDATE ... FROM (VALUES ...)`
that cannot half-fail.

## The wire does not move

`tickets.status` stays `text`, holding the key. `TicketResponse.status` stays the same
string it is today, so MCP tools, bearer-token scripts, saved views and the web's filter
grammar keep working unchanged.

What replaces `tickets_status_chk`:

```sql
ALTER TABLE tickets DROP CONSTRAINT tickets_status_chk;
ALTER TABLE tickets ADD CONSTRAINT tickets_status_fk
  FOREIGN KEY (team_id, status) REFERENCES team_statuses (team_id, key);
```

A composite foreign key, and the draft case is handled by Postgres rather than by us:
under the default `MATCH SIMPLE`, a row with `NULL` in any referencing column satisfies
the constraint without a lookup. So a draft — `team_id IS NULL` — passes untouched,
while a ticket that belongs to a team is *unable* to hold a status that team has not
defined. The invariant is in the database, where a repository cannot forget it and a new
write path cannot bypass it.

A draft's own vocabulary is the instance default, the same six the seeder writes: the
composer offers those, and attaching the draft to a team rebases its status the way a
move between teams does.

## The order stops being a constant

`StatusOrder.WORKFLOW` is a written sequence today and a `CASE` in SQL at query time.
With a catalogue it becomes `team_statuses.position`, which changes three things:

- **Grouped pages scoped to one team** rank buckets by joining the catalogue rather than
  by a generated `CASE` over six literals. Same shape of query, the rank read from a
  column instead of compiled from a constant.
- **Cross-team scopes group by category**, decided in brainstorming and the reason the
  categories exist: the header of a scope spanning two vocabularies is the fact, not one
  team's word for it. The category order *is* still a constant on both sides — five
  values, closed, ordered `backlog, unstarted, started, completed, canceled` — so the
  two-sided literal pattern `StatusOrder` documents survives exactly where it is still
  true, and `StatusOrderTest` moves onto it.
- **The default order is what seeds a team.** `WORKFLOW` keeps its six names and its
  test asserting each appears once; it stops being "the order of every grouped view" and
  becomes "the six rows and their positions a new team starts with". The docstring has
  to say so, because the sentence it opens with is about to be wrong.

The web's `WORKFLOW_ORDER` follows: a scope of one team orders by the catalogue it has
already fetched, a wider scope orders by the category constant. `PROGRESS_ORDER` and
`LOAD_ORDER` are untouched — they order segments inside pictures drawn from counts, and
`StatusOrder`'s docstring already explains why they are not the server's business.

## Four moves, and a fifth that is not a move

- **Add** — `KAN-90`. A label and a category. The key is derived, the position appends. Refused on
  a duplicate key or label, with the sentence naming the existing one.
- **Rename.** `label` only. The key stays.
- **Reorder.** The whole list, one transaction. A partial order sent by a client that
  disagrees about how many statuses exist is refused rather than merged.
- **Remove** — `KAN-90`. Requires naming the status its tickets move to. This is `KAN-4`'s
  disposition shape rather than a refusal: deleting a team already asks what happens to
  what it holds, and the answer there is a plan the caller sends. Removing the last
  status of a category is allowed — a team with no `canceled` status simply cannot
  cancel — with one exception below.
- **Moving a ticket between teams** — `KAN-90`, and until then a non-question: the six
  keys are the same everywhere, so the foreign key is satisfied by construction. It is
  where the foreign key bites, and `KAN-9` made
  that move ordinary. The status rebases: the same key if the destination has it,
  otherwise the destination's first status of the same category, otherwise its first
  status — first by `position`, which is the only order a catalogue has. The rebase is recorded as a `status_changed` activity row, because the ticket
  did change status and a burndown that saw the number move with no line explaining it
  would be a burndown nobody trusts.

**The exception** (`KAN-90`, since nothing can be removed before it): a team must keep at
least one status, whatever its category. A team with an empty catalogue
could hold no tickets at all, and the failure would surface as a foreign key violation
on ticket creation rather than as the sentence *a team needs somewhere to put work*.

## What may read a literal status

The audit that matters, since 35 Kotlin files and 41 web files mention the vocabulary.
The rule is short:

- **Reasoning reads the category.** `CycleTimeService` (`IN_FLIGHT_STATUSES`),
  `ProgressService`, the cycle rollover, velocity, `PublicRoadmapService`,
  `TeamWorkloadTool`. Most already do — `CycleTimeService` says in its own comment that a
  second spelling of "in flight" would be free to disagree with `StatusCategory` — and
  the ones that fold over `TicketStatus.entries` to get a category's members become a
  query against the catalogue, per team.
- **Presentation reads the label.** Pills, group headers, board columns, the Notion
  mirror's select, the MCP tools' human-readable tables.
- **Addressing reads the key.** Saved views, the filter grammar, `PATCH` bodies, URLs.
- **Nothing reads the enum's six as "the statuses".** `TicketStatus.entries` as a
  stand-in for a team's list is the bug this ticket is about, and the compiler cannot
  catch it — so `TicketStatus` is renamed to `DefaultStatus` and its `entries` become
  reachable only through the seeder and the draft vocabulary. A rename is what turns 35
  files of silent assumption into 35 compile errors somebody has to answer one by one.

## The Notion mirror — `KAN-91`, and what it turned out to be

This section said the push should send the team's label and the inbound match should
resolve against that team's rows. Writing it was easy; the mirror's shape says otherwise.

There is **one** tickets database for the whole instance, and its `Status` select carries
the six labels. Per-team labels make those options a union that grows with every rename —
Notion never prunes a select option — and worse, the union is ambiguous on the way back:
team A renaming `todo` to *En cours* and team B renaming `in_progress` to the same words
means an inbound *En cours* names two different keys, and choosing wrong writes a status
nobody set. That is corruption rather than a cosmetic gap.

The way out was the first of the three: **resolve against the ticket's own team.** The
poller already looks the ticket up before it reads a property — that is how the echo guard
and `kansoWins` work — so the team is in hand, and its catalogue names exactly one status
for the word. The union is then never consulted, and the ambiguity that made it unusable
cannot arise.

The outbound half needed nothing but the same catalogue: `NotionProps.select` sends a
*name*, and Notion invents the option when it has never seen it. So the six labels the
schema is created with are a seed rather than a vocabulary, and a renamed status reaches a
database created before the rename.

Both halves are `mirroredWord` and `statusFromWord` in `domain/TeamStatus.kt` — pure, and
tested against the case that decides the design: `todo` renamed to *En cours* in one team
while another team's `in_progress` already reads that way.

## MCP

`McpErrors` turns an enum parse failure into "a status outside the vocabulary" today,
and that message has to name the *team's* vocabulary instead: an agent that guesses
`in_review` against a team that has no such status deserves the list, in the refusal,
rather than a second call to go and look. That is the whole MCP change — the surface
gains no tool. `TeamWorkloadTool` and `TicketLines` print labels and so read the
catalogue; `UpdateTicketTool` and `CreateTicketTool` write keys and so are validated by
the same service every other write path goes through.

`TeamResponse` is what carries the catalogue to the web: `statuses`, always present,
ordered. Always present and not optional, for the reason `customFields` is required on
`TicketResponse` — a key that appears later is a key that breaks a reader, and this one
appears now, for everybody, in the version that introduces it.

## The web

- `TICKET_STATUSES` stops being the vocabulary and becomes the default one; a screen
  reads its scope's catalogue.
- Keys `1`–`9` address the scope's statuses by position; beyond nine there is no key,
  and a team with four statuses leaves `5`–`9` inert. Scenario 5 seeds a default team
  and stays green, which is the point of it.
- A status pill is drawn in its category's hue.
- The settings screen for a team gains the list: add, rename, drag to reorder, remove
  with a destination. It is the first screen in Kanso that edits an ordered list, and
  the reorder is the drag the board already implements.

## Migration

`V41__team_statuses.sql`.

1. Create the table and its indexes.
2. Insert six rows per existing team, from the same six the enum holds.
3. **Seed every team created from then on, with a trigger.** This spec said
   `TeamService.create`; switching the constraint on turned 550 of 1356 tests red,
   because half of them build a team through `TeamRepository` and never reach the
   service — and so could an import, an MCP tool, or a write path added next year. A team
   with no statuses cannot hold a single ticket, so it is not a state anybody should be
   able to produce, and the insert is the only place that cannot be bypassed. `V38`'s
   `set_updated_at_on_edit` is the same argument about a different invariant.
4. Drop `tickets_status_chk`, add `tickets_status_fk`.

Steps 2 and 3 before step 4, or the foreign key has nothing to point at and no way to
acquire anything. Every existing ticket
is valid unchanged, which is the property that makes this a one-way migration nobody has
to schedule: a ticket holding `in_progress` in team KAN now points at KAN's own
`in_progress` row.

## Testing

- **Unit, server.** The key derivation (the case-folding table, the accent folding, the
  refusal with no letter), the two uniqueness refusals and their sentences, the rebase
  on a team move (same key, same category, first status), the last-status refusal, the
  grouped rank read from `position`.
- **Unit, web.** The catalogue-driven order, the category order for a cross-team scope,
  the keys `1`–`9` against lists of four and nine.
- **e2e.** One scenario: a team renames *Done* to *Livré*, adds *Attente client* in
  `started`, reorders, then removes a status naming its destination — and a ticket that
  held it is on the destination, with an activity line saying so. Then the cross-team
  screen, where the headers are the five categories.

## Deferred, deliberately

- **Adding and removing a status — `KAN-90`**, with the domain change it forces. See
  "Staged" above for the count.
- Per-status colour, and per-status description.
- Statuses on a project or a cycle.
- Transition rules of any kind.
- An instance-level default catalogue new teams inherit rather than copy. Brainstorming
  chose copy-on-create for the single read path; the cost is that changing the default
  later does not reach teams already created, and the day somebody wants that, this is
  the ticket to reopen.
