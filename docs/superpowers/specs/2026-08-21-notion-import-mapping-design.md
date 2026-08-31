# Importing somebody else's workspace

Screen 24 exists and it works, for one shape of workspace: a Notion database becomes
*a* project whose pages become tickets, or a folder of documents. Every column is
matched by exact name — `Status`, `Priority`, `Description`, `Start`, `Due` — and
everything else is preserved as text under an "Imported from Notion" heading.

That is not the workspace people arrive with. They arrive with three databases that
already know about each other: teams, projects, tasks, wired together by relation
columns, with columns called `État` and options called `En cours`. Today that
workspace imports as one project full of tickets in `Todo`.

This spec closes that gap. It is one branch, and it is the last thing standing between
the README's promise — "an existing workspace is imported once" — and what the running
app does.

Nothing here changes the direction of the sync. **Postgres stays the source of truth
and Notion stays a mirror.** An import is a one-shot read: it happens because somebody
is present to answer questions a poller could never answer, and afterwards Kanso
publishes to *its own* four databases exactly as before. The workspace that was
imported is never written to, and never read again.

## What already stands

Read before designing anything, because most of the machinery is here:

- **OAuth is done.** `NotionOAuth` exchanges a consent screen for an instance-wide
  token; `V14__notion_oauth.sql` stores the client id, the secret, and the workspace
  the grant names. `notion-connect.tsx` draws the button, and the paste-a-token path
  survives as a folded-away fallback. The one manual step left is creating a public
  integration and pasting its two values — neither Notion nor Google issues a client
  to a host it has never heard of.
- **Discovery is done.** `NotionDiscovery` searches the workspace, excludes Kanso's own
  four databases by both ids, counts a base's pages by walking it, and reports the
  count as inexact when it stopped at the bound.
- **The preview promise is structural.** `NotionImportService.preview` cannot write
  because it is never handed anything that can; the arithmetic is `ImportPlanner`'s and
  the reading is `NotionDiscovery`'s, and neither has ever seen a repository.
- **The writer goes through the services.** `ImportWriter` calls `ProjectService`,
  `TicketService`, `DocService`, so an imported ticket gets its number from its team's
  counter, its activity row, and its place in the outbox — like one created by pressing
  `c`.
- **The dialog is reachable more than once**: the command palette, the settings
  connections section, and the empty inbox all open it.

What is missing is everything about *meaning*: which column is which, what a relation
says, which Notion person is which Kanso member, and whether a page has been imported
before.

## Four decisions, taken

**Relations carry meaning, declared once.** A relation column is not translated, it is
interpreted: "this column is the project", "this one is the parent team", "this one is
a dependency". Notion's schema for a relation names the data source it points at, so
one declaration teaches the import which other base matters and with which target —
the reader never has to say it twice.

**A resolved link is silent.** When a relation lands on a page that became a team, in
this plan or in an earlier import, the row is attached and nothing is asked. Questions
are for what cannot be resolved.

**A second import skips.** A page already imported is left alone and counted as such.
Re-reading Notion into an existing row would make Notion an inbound source, which
`architecture.md` refuses in as many words — "Notion is read-only in practice" — and
would overwrite whatever has been done in Kanso since.

**Guessing is offered, never imposed.** Every column, every select option, every person
is pre-filled with the best guess available and every one of them is a control the
reader can change. The current strict name match becomes the pre-fill rather than the
rule.

## What a base becomes

Four targets. `project` — the base *is* one project — disappears, absorbed into
`tickets`: a task database with no project column falls back to a project named after
the base, which is precisely today's behaviour with one concept fewer. Nothing is
published and no plan is persisted, so there is no compatibility to keep.

| Target | A page becomes | Relations read |
|---|---|---|
| `teams` | a team | to the same base → parent team |
| `projects` | a project | to a `teams` base → its team |
| `tickets` | a ticket | to a `projects` base → its project; inside the base → a dependency |
| `documents` | a document page inside a folder | none (unchanged) |

A base of projects on its own, linked to tasks living in a second base, is the shape
this is for: two bases, `projects` and `tickets`, and the relation between them says
which ticket belongs to which project.

### Relations are read in both directions

A Notion relation created `single_property` exists on **one side only** — `NotionSchema`
relies on that for `Blocked by` and for the teams' parent relation. So a workspace can
carry the project link on the tasks base as a `Projet` column, or on the projects base
as a `Tâches` column, and neither is more correct than the other. Reading only the
child's side would leave the second workspace with every ticket in the fallback
project.

So each target also has *inverse* fields, mapped on the parent's own screen section:

| Target | Inverse field | What it says |
|---|---|---|
| `projects` | `tickets` | the task pages belonging to this project |
| `teams` | `projects` | the project pages belonging to this team |
| `teams` | `subTeams` | the team pages under this one |

**The child's side wins.** A `two_property` relation is declared on both sides and can
disagree — a ticket naming project B while project A claims to hold it. The row being
written is the one that names its own parent, so the ticket goes to B, and the
disagreement is counted in the outcome rather than resolved silently. An inverse field
is a source of last resort, consulted only where the child said nothing.

Each target is importable on its own. Teams first, then projects, then tickets is the
path the screen suggests, because it is the order in which links resolve without a
fallback — but it is a suggestion. Somebody importing only a task database gets the
same wizard with fewer questions.

The write order is the server's, not the reader's: teams, projects, tickets, documents,
in one transaction. A plan that names all three in any order writes them in that one.

## The table of origins

`teams.notion_page_id`, `projects.notion_page_id` and `tickets.notion_page_id` already
exist, and they hold **the mirror's page** — the row Kanso created in `Kanso · Tickets`.
Reusing that column for the page an import came from would point Kanso's "Kanso wins"
push at somebody's own database and overwrite the workspace they just imported. Screen
24 promises nothing in Notion changes; that promise dies the moment those two ideas
share a column.

So a table of its own, `V15__notion_import_origin.sql`:

```sql
CREATE TABLE notion_import_origin (
  notion_page_id TEXT PRIMARY KEY,
  entity_type    TEXT NOT NULL,
  entity_id      UUID NOT NULL,
  data_source_id TEXT NOT NULL,
  imported_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (entity_type, entity_id)
);
```

`notion_page_id` is the primary key because one Notion page becomes one Kanso row: the
constraint *is* the "import once" rule, enforced by Postgres rather than by remembering
to check. `entity_type` is `team | project | ticket | doc`, the same closed vocabulary
the wire uses. `data_source_id` is what lets a later import say "this base was already
brought over, 396 of its 400 pages are here".

Two uses, and only two: resolving a relation onto a row imported in an earlier session,
and recognising a page so it can be skipped.

Deliberately not a foreign key onto four different tables — that is a polymorphic
reference, and the alternative is four nullable columns and a CHECK that only one is
set. A deleted entity leaves a stale row whose `entity_id` resolves to nothing; the
resolver treats that as unresolved and falls back, which is the same path as a relation
pointing at an ignored base. Cleanup is a `follow-ups.md` line, not a trigger.

## The five screens

The dialog goes from three steps to five. Two of them disappear when they have nothing
to ask.

**1 — What the workspace holds.** Unchanged. The bases, their page counts, and the
sentence that says why there is nothing when there is nothing.

**2 — What becomes what.** The existing table, with four targets instead of three on the
cycling button. Two additions:

- *Suggestions from relations.* Once a base is kept, its schema is read (see below), and
  a relation pointing at another base surfaces as a row hint: "Tasks points at
  Projects — import it as projects?" Accepting it maps that base. This is what the
  first decision buys: the reader answers "what is this relation" once, on the base
  they were already looking at.
- *A warning, never a block.* A kept base whose relation points at an ignored base says
  so, next to the count of what that costs. The import proceeds; the relation falls
  back or is dropped and counted.

The "Into team" select stays, and becomes the explicit fallback destination. It is
required only when the plan holds something other than `teams` rows — an import of
teams alone has no destination to ask about.

**3 — Columns, and the values inside them.** One section per kept base — a plan holding
three bases asks three times, because three targets have three sets of fields — and
inside a section, one row per Kanso field of that target, each with a select of the
Notion columns whose type can carry it, plus
"— none —". The title is not on this screen: it is found by type, because `title` is
the only property Notion requires of every database and matching on `"Name"` would
import a French workspace as pages called "Untitled".

Under a column mapped to status or priority, its options unfold: `En cours → In
progress`, `Blocked → Todo`. Pre-filled where the label already lands on a Kanso value,
and every option that does not shows the default it will take, in words.

Under a column mapped to a people field, one line: the person mapping page, with the
sentence that says it is filled once and then holds for every future import — and that
it is what lets the mirror write the `people` column back into Kanso's own databases.

Also on this screen, and only when it is needed: **where a missing link lands**. A
project whose team cannot be resolved goes into a team that already exists in Kanso; a
team with no parent relation can be placed under an existing parent; tickets whose
project cannot be resolved go into an existing project. Per base, because that is the
unit the reader is deciding about, and hidden entirely when every link resolves.

**4 — People.** The Notion people met on the mapped columns, each pointing at a Kanso
member or at "nobody". Pre-filled from `users.notion_person_id` where it is already
known, then by exact name. An accepted match *writes* `notion_person_id` — that is what
makes the screen worth its existence rather than throwaway: the mirror can then fill
the `people` property that `NotionSchema.people` already knows how to write.

Skipped when no people column is mapped.

**5 — Preview.** Today's counts, plus what the new machinery knows: how many pages are
already imported and will be skipped, how many rows found their team or project by
relation, how many fall back and to what. Then the confirm button, and the outcome the
writer reports.

## Reading a schema, and reading a page

Two reads, and they answer different questions.

`NotionClient.retrieveDataSource(dataSourceId)` is new, one call per kept base, and it
is what screen 3 is built from. The pages are not enough: a column empty on every page
read is invisible in the pages and present in the schema, a select's options must be
listed even when no page uses them, and a relation's target data source exists only in
the schema. Its result becomes `ImportSchema` — a column list of name, type, select
options, and for relations the data source pointed at.

`MappedPageReader` replaces `NotionPageReader` and reads a page *through* a mapping
rather than by name. Same conversions, same lossiness, same "Imported from Notion"
section for what nothing claimed — but the question "which property is the status" is
answered by the request instead of by a constant. `NotionProps` stays exactly as it is:
it describes the databases Kanso *writes*, which is a different subject.

`NotionClient.listUsers()` is the other new call, for the person page: Notion's
workspace members. It needs the integration's "read user information" capability, and
when that is missing Notion answers 403 — which the page prints as the sentence saying
where to tick it, rather than as an empty list. Building the list from people met during
imports instead was rejected: it needs a table, it is empty before the first import, and
a colleague who appears on no imported page is unreachable.

## What a missing column becomes

Every default is visible on screen 3 before anything is written.

| Field left unmapped | Default | Why |
|---|---|---|
| ticket · status | `Todo` | the vocabulary is closed in Kotlin *and* by a CHECK |
| ticket · priority | `None` | same |
| ticket · start, due | empty | an invented date moves a bar on the timeline |
| ticket · assignee | nobody | guessing from a display name puts work on the wrong person |
| ticket · project | one project per base, named after it, `In progress` | today's `project` target, kept |
| project · status | `In progress` | imported work is work already started elsewhere |
| project · lead, start, end | empty | |
| project · team | the fallback team | |
| team · key | derived from the name by `resolveKey` | as for a team created by hand |
| team · parent | none | |

A select option outside Kanso's vocabulary takes the field's default rather than
becoming a seventh status nothing else understands. Rollups and formulas stay text in
the "Imported from Notion" section: they render as a displayed value, and a displayed
value is worth more there than a faithful copy of Notion's internals in a column that
would then have to be kept in sync.

## The wire

Per plan row, and a request-level map for people because a person is a person across
bases:

```
POST /api/notion/import/preview
POST /api/notion/import
{
  teamId: uuid?,                       // the fallback team; absent for a teams-only plan
  people: { notionPersonId: userId? }, // request-level
  plan: [{
    sourceId: string,
    target: "teams" | "projects" | "tickets" | "documents",
    columns: { field: notionProperty },              // field ∈ the target's fields
    values:  { notionProperty: { option: kansoValue } },
    fallback: { teamId?: uuid, parentTeamId?: uuid, projectId?: uuid }
  }]
}

GET /api/notion/import/schema?sourceId=…   → ImportSchema
GET /api/notion/people                     → workspace members, or the 403 sentence
PUT /api/notion/people                     → { notionPersonId: userId? }
```

A relation's meaning needs no shape of its own: `team`, `parentTeam`, `project` and
`blockedBy` are fields of their target like `status` is, so declaring what a relation
means is one entry in `columns`. The inverse fields — `tickets` on a `projects` base,
`projects` and `subTeams` on a `teams` base — are entries in the same map. That is what
makes the interpretation and the pre-fill one mechanism rather than two.

An ignored base stays absent from `plan` rather than present with a target meaning "do
nothing", exactly as today: the request is the instruction, and an instruction listing
things not to do is one more thing the server has to be trusted to read correctly.

## Files

`ImportWriter` is 211 lines for two targets. At four targets plus origin bookkeeping it
stops being a file anyone re-reads in one sitting, so it is split by target and keeps
only the orchestration.

**API** — `ImportMapping.kt` (the mapping types, the pre-fill, the defaults; pure),
`ImportSchema.kt` (a data source's schema as the wire shape), `MappedPageReader.kt`
(replaces `NotionPageReader`), `ImportWriter.kt` (orchestration and origins),
`TeamImport.kt`, `ProjectImport.kt`, `TicketImport.kt`, `DocumentImport.kt`,
`ImportOriginRepository.kt`, `NotionPeopleService.kt`, `V15__notion_import_origin.sql`,
and two methods on `NotionClient` — `retrieveDataSource`, `listUsers` — with their
`NoopNotionClient` and `ReloadableNotionClient` counterparts.

**Web** — `import-columns.ts` (compatible types, pre-fill, defaults; pure and tested),
`import-step-columns.tsx`, `import-step-people.tsx`, growth in `import-map.ts`,
`import-step-two.tsx`, `import-step-three.tsx`, and `settings/people-section.tsx` as a
new file — `connections-section.tsx` is already at 375 lines and gains nothing here.

## Tests

TDD, purest first, because the pure parts are where the decisions live:

- `ImportMapping` — pre-fill from a schema, the default table above, the fallback-team
  requirement rule.
- `MappedPageReader` — one case per Notion type it claims to read, plus a mapped column
  that is absent from the page, plus an option outside the vocabulary.
- `ImportPlanner` — counts across four targets, already-imported pages, relations
  resolved and dropped, a link found only on the parent's side, and a `two_property`
  relation whose two sides disagree.
- `import-columns.ts` and `import-map.ts` — vitest, the same arithmetic the screens show.

Then the integrations: one plan holding teams, projects and tickets written in the
server's order; a second import of the same plan writing nothing and reporting it; a
project whose team resolves by relation versus one that falls back; a person mapping
that writes `notion_person_id`; and the 403 from `listUsers` reaching the page as a
sentence.

Then one Playwright pass through the five screens against the existing fake client.

To rewrite rather than extend, because the target names change: `NotionImportTest`,
`NotionImportSourcesTest`, `NotionImportPreviewTest`, `import-map.test.ts`.

`architecture.md` and `architecture.fr.md` gain the origin table and the distinction
from the mirror's `notion_page_id`; the lossy-mapping table gains the import direction.

## Not in this branch

- **No update on re-import.** Decided, and it is the decision that keeps Notion out of
  the inbound path.
- **No inviting users from Notion.** The person page matches Notion people to accounts
  that exist; creating accounts is the invitation flow's job.
- **No continuous inbound read.** Unchanged from `architecture.md`.
- **No cleanup of origin rows whose entity was deleted.** A stale row resolves to
  nothing and falls back; a line in `follow-ups.md` says so.
- **Importing documentation properly is its own subject, deliberately left out.** The
  `documents` target keeps exactly today's behaviour: a folder per base, a page per row,
  and the unmapped properties in a callout. What a real documentation import would be —
  a page's *blocks* rather than its title, nested pages rather than a flat folder, and
  the many-to-many references to projects and tickets that `notion_docs` was built for —
  is a design of its own, and it gets its own spec. Nothing here forecloses it: the
  origin table already keys documents by `entity_type = 'doc'`.
