# Notion Import Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import somebody else's Notion workspace — teams, projects and tasks living in
separate databases, wired by relations, with columns and options in their own words — into
Kanso, once, without ever writing back to it.

**Architecture:** The import keeps its existing split: `NotionDiscovery` reads, `ImportPlanner`
computes, `ImportWriter` writes, and a preview cannot write because it is never handed
anything that can. Three things are added inside that split — a target per entity kind
(`teams`, `projects`, `tickets`, `documents`) with a server-decided write order; a table of
origins keyed by Notion page id, which is what makes a second import skip rather than
duplicate; and an explicit column/value/person mapping that arrives on the request, so
`NotionPageReader`'s hardcoded English property names become a *suggestion* the reader can
change.

**Tech Stack:** Kotlin 2.x / Spring Boot / Exposed 1.x (`javaUUID`), Flyway, Postgres,
JUnit 5 + Testcontainers (`PostgresTest`), Next.js 15 App Router / React 19 / TanStack
Query / Tailwind, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-21-notion-import-mapping-design.md`

## Global Constraints

- **Postgres is the source of truth; Notion is a mirror.** The import reads Notion once and
  never writes to the workspace it read. No task may add an outbound call to an imported
  database.
- **A second import skips.** A page already in `notion_import_origin` is left alone and
  counted. No task may update a Kanso row from a Notion page.
- **Nothing is written before the last step.** `NotionImportService.preview` and everything
  it calls must remain unable to write: only `perform` is handed the writer.
- **Guessing is a default, never a rule.** Every column, option and person is pre-filled and
  every pre-fill is overridable from the request.
- **A closed vocabulary stays closed.** `TicketStatus`, `TicketPriority`, `ProjectStatus` are
  enforced in Kotlin *and* by a CHECK; an unknown Notion option takes the field default.
- **Tabs, not spaces, in Kotlin.** Two-space indentation in TypeScript. Match the file.
- **Migrations are the schema's only definition.** `Tables.kt` describes what Flyway created;
  it never generates DDL.
- **Comments say why, not what.** Match the density of the file being edited.

## Where this plan refines the spec

One deliberate change, recorded here rather than argued twice:

**The pre-fill is computed server-side and travels with the schema.** The spec put a
pre-fill in `import-columns.ts` and one in `ImportMapping.kt`. That is the same decision in
two languages, which is a drift waiting to happen — and the server needs it anyway, because
`GET /schema` is where the candidate columns per field come from. So `GET /schema` answers
the columns, the per-field candidates, *and* a suggested mapping; the screen renders the
suggestion and sends back whatever the reader made of it. `import-columns.ts` keeps only what
the screen itself derives (which fields are answered, what a default reads as in words).

## File Structure

**Created — API**

| File | Responsibility |
|---|---|
| `apps/api/src/main/resources/db/migration/V15__notion_import_origin.sql` | The table of origins, and the comment explaining why it is not `notion_page_id` |
| `apps/api/src/main/kotlin/dev/kanso/repo/ImportOriginRepository.kt` | Read and write origins; nothing else |
| `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportMapping.kt` | `ImportField`, `ColumnMapping`, `Fallback`, the default table, the suggestion. Pure |
| `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportSchema.kt` | A data source's schema as the wire shape, and the candidates per field |
| `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportLinks.kt` | Resolves relations, both directions, into page-id → page-id links. Pure |
| `apps/api/src/main/kotlin/dev/kanso/sync/importer/MappedPageReader.kt` | Reads one page through a `ColumnMapping` (replaces `NotionPageReader`) |
| `apps/api/src/main/kotlin/dev/kanso/sync/importer/TeamImport.kt` | Writes teams, then settles their parents |
| `apps/api/src/main/kotlin/dev/kanso/sync/importer/ProjectImport.kt` | Writes projects |
| `apps/api/src/main/kotlin/dev/kanso/sync/importer/TicketImport.kt` | Writes tickets and their dependencies |
| `apps/api/src/main/kotlin/dev/kanso/sync/importer/DocumentImport.kt` | Writes folders and document pages |
| `apps/api/src/main/kotlin/dev/kanso/sync/notion/NotionPeople.kt` | The workspace's members, and matching them to Kanso accounts |
| `apps/api/src/main/kotlin/dev/kanso/api/NotionPeopleController.kt` | `GET`/`PUT /api/notion/people` |

**Modified — API**

| File | Change |
|---|---|
| `sync/importer/ImportModel.kt` | Four targets; the wire types grow `mapping`, `fallback`, the new counts |
| `sync/importer/ImportPlanner.kt` | Preview across four targets, already-imported, links via `ImportLinks` |
| `sync/importer/ImportWriter.kt` | Orchestration and origin bookkeeping only; the four writers do the writing |
| `sync/importer/NotionImportService.kt` | The schema endpoint's read, the people it met, the write order |
| `sync/importer/NotionDiscovery.kt` | `schema(dataSourceId)` |
| `sync/notion/NotionClient.kt` + `HttpNotionClient` + `NoopNotionClient` + `ReloadableNotionClient` | `retrieveDataSource`, `listUsers` |
| `api/NotionImportController.kt` | The grown request shape, `GET /schema`, `GET /people-seen` |
| `db/Tables.kt` | `NotionImportOrigins` |
| `sync/importer/NotionPageReader.kt` | Deleted at Task 8, once `MappedPageReader` covers it |

**Created — Web**

| File | Responsibility |
|---|---|
| `apps/web/src/components/inbox/import-columns.ts` | What the columns screen derives: answered fields, defaults in words |
| `apps/web/src/components/inbox/import-columns.test.ts` | Vitest for the above |
| `apps/web/src/components/inbox/import-step-columns.tsx` | Step 3 — one section per kept base |
| `apps/web/src/components/inbox/import-step-people.tsx` | Step 4 — Notion person → Kanso member |
| `apps/web/src/components/settings/people-section.tsx` | The standing correspondence page |

**Modified — Web**

| File | Change |
|---|---|
| `components/inbox/import-map.ts` | Four targets, counts per kind |
| `components/inbox/import-targets.ts` | Labels and dots for four targets |
| `components/inbox/import-dialog.tsx` | Five steps, the mapping state |
| `components/inbox/import-step-two.tsx` | Four-way cycle, relation suggestions, the ignored-base warning |
| `components/inbox/import-step-three.tsx` | Already-imported and resolved-link counts |
| `lib/api/inbox.ts` | The grown wire types and the two new calls |
| `components/settings/panel.tsx` | The people section's place in settings |

---

# STAGE 1 — Four targets, origins, and the order they are written in

At the end of this stage a workspace of three databases imports correctly with today's
name-matched columns. No mapping screen yet.

### Task 1: Four targets on the wire

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportModel.kt:15-27`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportWriter.kt:57-78`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportPlanner.kt:29-36`
- Modify: `apps/web/src/components/inbox/import-map.ts`, `import-targets.ts`,
  `import-dialog.tsx:64-68`, `import-step-two.tsx`, `apps/web/src/lib/api/inbox.ts:149`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/importer/NotionImportTest.kt`,
  `apps/web/src/components/inbox/import-map.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class ImportTarget(wire): TEAMS("teams"), PROJECTS("projects"),
  TICKETS("tickets"), DOCUMENTS("documents")` with `val fields: Set<ImportField>` added in
  Task 6. TypeScript `ImportTarget = "teams" | "projects" | "tickets" | "documents" |
  "ignore"`.

`TICKETS` behaves exactly as `PROJECT` did — one project named after the base, a ticket per
page. `TEAMS` and `PROJECTS` are accepted and refused at the writer with a sentence until
Task 3; that refusal is a test, not a stub.

- [ ] **Step 1: Rename the target in the existing test and add the two refusals**

In `NotionImportTest.kt`, replace every `ImportTarget.PROJECT` with `ImportTarget.TICKETS`
and every `"project"` in a wire payload with `"tickets"`, then add:

```kotlin
@Test
fun `a teams base is refused until the writer can write one`() {
	val teams = FakeDatabase("Teams", listOf(fakePage("Platform")))
	val importer = importerFor(teams)

	val failure = assertThrows<BadRequestException> {
		importer.perform(admin, team.id, plan(teams to ImportTarget.TEAMS))
	}

	assertTrue(failure.message!!.contains("teams"), "the sentence names what it cannot write yet")
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.NotionImportTest'`
Expected: FAIL — `ImportTarget.TICKETS` and `ImportTarget.TEAMS` do not exist.

- [ ] **Step 3: Grow the enum and route the two new targets to a refusal**

In `ImportModel.kt`:

```kotlin
/**
 * What a Notion base becomes. There is no `IGNORE`: an ignored base is *absent* from the
 * plan rather than present with a target meaning "do nothing" — `import-map.ts` builds the
 * request that way on purpose, and an instruction listing things not to do is one more
 * thing this service would have to be trusted to read correctly.
 *
 * `PROJECT` used to mean "this base becomes *one* project whose pages are tickets", which
 * described the container rather than the base. `TICKETS` is that behaviour under a name
 * that says it, and `PROJECTS` is the other shape: a base whose pages *are* projects.
 */
enum class ImportTarget(override val wire: String) : Wire {
	TEAMS("teams"),
	PROJECTS("projects"),
	TICKETS("tickets"),
	DOCUMENTS("documents");

	companion object {
		fun from(raw: String): ImportTarget = parse(entries.toTypedArray(), raw)
	}
}
```

In `ImportWriter.write`, rename the `ImportTarget.PROJECT` branch to `ImportTarget.TICKETS`
and add:

```kotlin
ImportTarget.TEAMS, ImportTarget.PROJECTS ->
	throw BadRequestException(
		"Importing a base as ${base.target.wire} is not wired up yet."
	)
```

In `ImportPlanner.preview`, `filter { it.target == ImportTarget.TICKETS }` for `projects`,
and add `teams = bases.filter { it.target == ImportTarget.TEAMS }.map { … }` to
`ImportPreview` as an empty-for-now group list — declared here so Task 3 fills it rather
than changing the wire twice.

- [ ] **Step 4: Run the API tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.*'`
Expected: PASS.

- [ ] **Step 5: Follow the rename through the web, tests first**

In `import-map.test.ts`, replace `eng: "project"` with `eng: "tickets"` and add:

```ts
it("counts the four kinds separately, because the screen names them separately", () => {
  const counts = importCounts(SOURCES, {
    eng: "tickets",
    design: "documents",
    meetings: "projects",
    archive: "teams",
  });
  expect(counts).toEqual({
    kept: 1287,
    total: 1287,
    teams: 1,
    projects: 1,
    tickets: 1,
    folders: 1,
    ignored: 0,
  });
});
```

- [ ] **Step 6: Run it and watch it fail**

Run: `cd apps/web && pnpm vitest run src/components/inbox/import-map.test.ts`
Expected: FAIL — `teams` and `tickets` are not counted.

- [ ] **Step 7: Grow `ImportCounts`, the labels, and the cycle**

`import-map.ts`:

```ts
export type ImportTarget = "teams" | "projects" | "tickets" | "documents" | "ignore";

export type ImportCounts = {
  kept: number;
  total: number;
  teams: number;
  projects: number;
  tickets: number;
  folders: number;
  ignored: number;
};
```

`importCounts` gains a `case` per kind, each adding `source.pages` to `kept` and one to its
own counter. `import-targets.ts`:

```ts
export const TARGET_LABELS: Record<ImportTarget, string> = {
  teams: "Teams",
  projects: "Projects",
  tickets: "Tickets",
  documents: "Documents",
  ignore: "Ignore",
};

export const TARGET_DOTS: Record<ImportTarget, string> = {
  teams: "bg-status-backlog",
  projects: "bg-status-review",
  tickets: "bg-status-progress",
  documents: "bg-status-done",
  ignore: "bg-transparent",
};
```

`import-dialog.tsx`'s `cycle` order becomes
`["teams", "projects", "tickets", "documents", "ignore"]`, and `NotionImportPlanRow`'s
`target` in `lib/api/inbox.ts` becomes
`"teams" | "projects" | "tickets" | "documents"`.

- [ ] **Step 8: Run the web tests and the typecheck**

Run: `cd apps/web && pnpm vitest run src/components/inbox && pnpm tsc --noEmit`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add apps/api apps/web
git commit -m "refactor(import): name the four things a Notion base can become

PROJECT described the container the import created, not the base it read: one
project, its pages as tickets. TICKETS is that behaviour under a name that says
it, and PROJECTS is the other shape — a base whose pages are projects.

TEAMS and PROJECTS are refused at the writer with a sentence, tested as such,
until the writers that can write them exist."
```

### Task 2: The table of origins, and the second import that skips

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V15__notion_import_origin.sql`
- Create: `apps/api/src/main/kotlin/dev/kanso/repo/ImportOriginRepository.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/db/Tables.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportWriter.kt`,
  `ImportModel.kt`, `ImportPlanner.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/importer/ImportOriginTest.kt` (new)

**Interfaces:**
- Consumes: `ImportTarget` (Task 1).
- Produces:
  ```kotlin
  enum class OriginKind(override val wire: String) : Wire { TEAM("team"), PROJECT("project"), TICKET("ticket"), DOC("doc") }
  data class ImportOrigin(val notionPageId: String, val kind: OriginKind, val entityId: UUID, val dataSourceId: String)

  @Repository class ImportOriginRepository {
      fun byPageIds(pageIds: Collection<String>): Map<String, ImportOrigin>
      fun record(origin: ImportOrigin)
  }
  ```
  and `ImportPreview.alreadyImported: Int`, `ImportOutcome.alreadyImported: Int`.

- [ ] **Step 1: Write the failing test**

`ImportOriginTest.kt`:

```kotlin
package dev.kanso.sync.importer

import dev.kanso.repo.ImportOriginRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ImportOriginTest : ImportTestBase() {

	@Autowired private lateinit var origins: ImportOriginRepository

	@Test
	fun `a second import of the same base writes nothing and says so`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it"), fakePage("Then rest")))
		val importer = importerFor(tasks)
		val steps = plan(tasks to ImportTarget.TICKETS)

		val first = importer.perform(admin, team.id, steps)
		assertEquals(2, first.tickets)
		assertEquals(0, first.alreadyImported)

		val before = rowCounts()
		val second = importer.perform(admin, team.id, steps)

		assertEquals(0, second.tickets, "nothing is written the second time")
		assertEquals(2, second.alreadyImported, "and it is counted, not silent")
		assertEquals(before, rowCounts())
	}

	@Test
	fun `the preview counts what a second import would skip`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it")))
		val importer = importerFor(tasks)
		val steps = plan(tasks to ImportTarget.TICKETS)
		importer.perform(admin, team.id, steps)

		assertEquals(1, importer.preview(steps).alreadyImported)
	}

	@Test
	fun `an origin names the row it created`() {
		val page = fakePage("Ship it")
		val tasks = FakeDatabase("Tasks", listOf(page))
		importerFor(tasks).perform(admin, team.id, plan(tasks to ImportTarget.TICKETS))

		val origin = tx.execute { origins.byPageIds(listOf(page.id)) }!!.getValue(page.id)
		assertEquals(OriginKind.TICKET, origin.kind)
		assertEquals(tasks.dataSourceId, origin.dataSourceId)
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.ImportOriginTest'`
Expected: FAIL — `ImportOriginRepository` does not exist.

- [ ] **Step 3: Write the migration**

`V15__notion_import_origin.sql`:

```sql
-- Which Notion page a Kanso row was imported from.
--
-- Not `teams.notion_page_id` / `projects.notion_page_id` / `tickets.notion_page_id`:
-- those hold the page the *mirror* created inside `Kanso · Tickets`, which Kanso
-- overwrites on every push. Putting somebody's own page there would point that push at
-- the workspace they just imported and rewrite it — the one thing the import screen
-- promises never happens.
--
-- The primary key is the rule: one Notion page becomes at most one Kanso row, enforced
-- by Postgres rather than by remembering to check. A second import of the same base
-- therefore skips rather than duplicating, which is what makes the import safe to press
-- twice.
--
-- No foreign key: the reference is polymorphic, and the alternative is four nullable
-- columns and a CHECK that exactly one is set. A row whose entity was deleted resolves
-- to nothing, which the resolver already treats as "unlinked" and falls back on.
CREATE TABLE notion_import_origin (
  notion_page_id TEXT PRIMARY KEY,
  entity_type    TEXT NOT NULL CHECK (entity_type IN ('team', 'project', 'ticket', 'doc')),
  entity_id      UUID NOT NULL,
  data_source_id TEXT NOT NULL,
  imported_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (entity_type, entity_id)
);

-- "Has this base been imported before, and how much of it" — the question the preview
-- asks once per plan row.
CREATE INDEX notion_import_origin_source_idx ON notion_import_origin (data_source_id);
```

- [ ] **Step 4: Describe it to Exposed and write the repository**

`Tables.kt`:

```kotlin
/** Which Notion page each imported row came from. See `V15__notion_import_origin.sql`. */
object NotionImportOrigins : Table("notion_import_origin") {
	val notionPageId = text("notion_page_id")
	val entityType = text("entity_type")
	val entityId = javaUUID("entity_id")
	val dataSourceId = text("data_source_id")
	val importedAt = timestampWithTimeZone("imported_at")
	override val primaryKey = PrimaryKey(notionPageId)
}
```

`ImportOriginRepository.kt`:

```kotlin
package dev.kanso.repo

import dev.kanso.db.NotionImportOrigins
import dev.kanso.domain.Wire
import dev.kanso.domain.parse
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** The four kinds of row an import can produce. Closed, like every other wire vocabulary. */
enum class OriginKind(override val wire: String) : Wire {
	TEAM("team"), PROJECT("project"), TICKET("ticket"), DOC("doc");

	companion object {
		fun from(raw: String): OriginKind = parse(entries.toTypedArray(), raw)
	}
}

data class ImportOrigin(
	val notionPageId: String,
	val kind: OriginKind,
	val entityId: UUID,
	val dataSourceId: String,
)

@Repository
class ImportOriginRepository {

	/**
	 * Looked up in one query per import rather than one per page: a base of four hundred
	 * pages would otherwise cost four hundred round trips inside the transaction that holds
	 * the team's counter.
	 */
	fun byPageIds(pageIds: Collection<String>): Map<String, ImportOrigin> {
		if (pageIds.isEmpty()) return emptyMap()
		return NotionImportOrigins.selectAll()
			.where { NotionImportOrigins.notionPageId inList pageIds.toSet() }
			.associate { it[NotionImportOrigins.notionPageId] to it.toOrigin() }
	}

	fun countBySource(dataSourceId: String): Int =
		NotionImportOrigins.selectAll()
			.where { NotionImportOrigins.dataSourceId eq dataSourceId }
			.count().toInt()

	fun record(origin: ImportOrigin) {
		NotionImportOrigins.insert {
			it[notionPageId] = origin.notionPageId
			it[entityType] = origin.kind.wire
			it[entityId] = origin.entityId
			it[dataSourceId] = origin.dataSourceId
			it[importedAt] = OffsetDateTime.now()
		}
	}

	private fun ResultRow.toOrigin() = ImportOrigin(
		notionPageId = this[NotionImportOrigins.notionPageId],
		kind = OriginKind.from(this[NotionImportOrigins.entityType]),
		entityId = this[NotionImportOrigins.entityId],
		dataSourceId = this[NotionImportOrigins.dataSourceId],
	)
}
```

- [ ] **Step 5: Skip in the writer, count in the preview**

`ImportModel.kt` — `ImportPreview` gains `val alreadyImported: Int` and `ImportOutcome`
gains `val alreadyImported: Int`. `PlannedBase` gains the set of pages already imported:

```kotlin
class PlannedBase(
	val base: WorkspaceBase,
	val target: ImportTarget,
	val pages: List<NotionPage>,
	/** Pages this instance has already imported, by id. Skipped, and counted. */
	val alreadyImported: Set<String> = emptySet(),
) {
	/** The pages that can become rows, in the order Notion returned them. */
	val adoptable: List<NotionPage> by lazy {
		pages.filter { NotionPageReader.refusal(it) == null && it.id !in alreadyImported }
	}
}
```

`NotionImportService.read` fills it — one query for the whole plan:

```kotlin
val origins = tx.execute { originRows.byPageIds(pagesByBase.values.flatten().map { it.id }) }.orEmpty()
```

and `ImportWriter` calls `origins.record(...)` for every row it creates. `ImportPlanner.preview`
sums `bases.sumOf { it.alreadyImported.size }`.

- [ ] **Step 6: Run the tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/api
git commit -m "feat(import): remember which Notion page a row came from

The entities already carry notion_page_id and it holds the mirror's page — the
row Kanso overwrites on every push. Reusing it would aim that push at the
workspace somebody just imported.

So a table of its own, keyed by page id, which makes the key the rule: one page
becomes at most one row, and a second import skips and says how much it skipped."
```

### Task 3: Teams and projects, written in the server's order

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/sync/importer/TeamImport.kt`,
  `ProjectImport.kt`, `TicketImport.kt`, `DocumentImport.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportWriter.kt` (down to
  orchestration), `ImportModel.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/importer/ImportOrderTest.kt` (new)

**Interfaces:**
- Consumes: `ImportOriginRepository`, `OriginKind`, `PlannedBase`, `ImportTarget`.
- Produces:
  ```kotlin
  /** Page id → the row it became, accumulated across the four passes. */
  class ImportedRows {
      fun put(kind: OriginKind, pageId: String, id: UUID)
      fun team(pageId: String): UUID?
      fun project(pageId: String): UUID?
      fun ticket(pageId: String): UUID?
  }

  @Service class TeamImport { fun write(actor: User, base: PlannedBase, rows: ImportedRows): Int
                              fun settleParents(actor: User, base: PlannedBase, links: ImportLinks.Resolved, rows: ImportedRows) }
  @Service class ProjectImport { fun write(actor: User, base: PlannedBase, fallbackTeam: UUID, links: ImportLinks.Resolved, rows: ImportedRows): Int }
  @Service class TicketImport { fun write(actor: User, base: PlannedBase, fallbackTeam: UUID, links: ImportLinks.Resolved, rows: ImportedRows): TicketsWritten }
  @Service class DocumentImport { fun write(actor: User, base: PlannedBase, fallbackTeam: UUID): Int }
  ```
  and `ImportPreview.teams: List<PreviewGroup>` filled.

- [ ] **Step 1: Write the failing test**

`ImportOrderTest.kt`:

```kotlin
package dev.kanso.sync.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ImportOrderTest : ImportTestBase() {

	@Test
	fun `teams, projects and tickets are written in that order whatever order the plan names them`() {
		val platform = fakePage("Platform")
		val web = fakePage("Web", mapOf("Parent team" to notionRelation(platform.id)))
		val teams = FakeDatabase("Teams", listOf(web, platform))

		val roadmap = fakePage("Roadmap", mapOf("Team" to notionRelation(web.id)))
		val projects = FakeDatabase("Projects", listOf(roadmap))

		val ship = fakePage("Ship it", mapOf("Project" to notionRelation(roadmap.id)))
		val tasks = FakeDatabase("Tasks", listOf(ship))

		// Named tickets-first on purpose: the order is the server's, not the request's.
		val outcome = importerFor(teams, projects, tasks).perform(
			admin, team.id,
			plan(
				tasks to ImportTarget.TICKETS,
				projects to ImportTarget.PROJECTS,
				teams to ImportTarget.TEAMS,
			),
		)

		assertEquals(2, outcome.teams)
		assertEquals(1, outcome.projects)
		assertEquals(1, outcome.tickets)

		val webTeam = teamService.list(includeArchived = false).first { it.name == "Web" }
		val platformTeam = teamService.list(includeArchived = false).first { it.name == "Platform" }
		assertEquals(platformTeam.id, webTeam.parentTeamId, "the parent relation was read")

		val project = projectRows.search(teamIds = null, includeArchived = false).first { it.name == "Roadmap" }
		assertEquals(webTeam.id, project.teamId, "the project landed in the team its relation named")

		val ticket = ticketRows.search(includeArchived = false, limit = 50).first { it.title == "Ship it" }
		assertEquals(project.id, ticket.projectId, "and the ticket in the project its relation named")
		assertNotNull(ticket.number)
	}

	@Test
	fun `a ticket whose project relation resolves to nothing lands in the project named after its base`() {
		val ship = fakePage("Ship it", mapOf("Project" to notionRelation("page-nowhere")))
		val tasks = FakeDatabase("Tasks", listOf(ship))

		importerFor(tasks).perform(admin, team.id, plan(tasks to ImportTarget.TICKETS))

		val project = projectRows.search(teamIds = null, includeArchived = false).first()
		assertEquals("Tasks", project.name, "the base's own name, which is today's behaviour")
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.ImportOrderTest'`
Expected: FAIL — `TEAMS` is still refused by the writer.

- [ ] **Step 3: Split the writer, one file per target**

`TeamImport.kt`:

```kotlin
package dev.kanso.sync.importer

import dev.kanso.domain.User
import dev.kanso.repo.OriginKind
import dev.kanso.service.ConflictException
import dev.kanso.service.TeamService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Teams, then their parents.
 *
 * Two passes rather than one, because a parent can be listed after its child: the first
 * creates every team flat, the second moves them. `TeamService.update` is the only way to
 * set a parent and it refuses a cycle, which is exactly the behaviour wanted here — a
 * workspace whose relations happen to form a loop loses one arrow, not the import.
 */
@Service
class TeamImport(private val teams: TeamService) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun write(actor: User, base: PlannedBase, rows: ImportedRows): Int {
		var created = 0
		for (page in base.adoptable) {
			val name = requireNotNull(base.reader.title(page)) { "an unadoptable page reached the writer" }
			// The key is derived from the name by TeamService, exactly as for a team created
			// by hand: a Notion base has nothing that could serve as one.
			val team = teams.create(actor, name, null, null)
			rows.put(OriginKind.TEAM, page.id, team.id)
			created++
		}
		return created
	}

	fun settleParents(actor: User, base: PlannedBase, links: ImportLinks.Resolved, rows: ImportedRows) {
		for (page in base.adoptable) {
			val parentPage = links.parentOfTeam[page.id] ?: continue
			val childId = rows.team(page.id) ?: continue
			val parentId = rows.team(parentPage) ?: continue
			val child = teams.get(childId)
			try {
				teams.update(actor, childId, child.name, child.key, parentId)
			} catch (e: ConflictException) {
				log.info("Dropped an imported team parent: {}", e.message)
			}
		}
	}
}
```

`ProjectImport.kt`:

```kotlin
package dev.kanso.sync.importer

import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.User
import dev.kanso.repo.OriginKind
import dev.kanso.service.ProjectService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * A base whose pages are projects.
 *
 * `IN_PROGRESS` rather than `PLANNED` for the same reason the old single-project import
 * chose it: these are works somebody has already been doing somewhere else, and a project
 * full of half-finished tickets that calls itself planned is wrong on the one screen that
 * reads project status.
 */
@Service
class ProjectImport(private val projects: ProjectService) {

	fun write(
		actor: User,
		base: PlannedBase,
		fallbackTeam: UUID,
		links: ImportLinks.Resolved,
		rows: ImportedRows,
	): Int {
		var created = 0
		for (page in base.adoptable) {
			val teamId = links.teamOfProject[page.id]?.let(rows::team)
				?: base.fallback.teamId
				?: fallbackTeam
			val project = projects.create(
				name = requireNotNull(base.reader.title(page)) { "an unadoptable page reached the writer" },
				status = base.reader.projectStatus(page) ?: ProjectStatus.IN_PROGRESS,
				start = base.reader.start(page),
				end = base.reader.end(page),
				leadUserId = null,
				teamId = teamId,
				docIds = emptyList(),
			).project
			rows.put(OriginKind.PROJECT, page.id, project.id)
			created++
		}
		return created
	}
}
```

`TicketImport.kt` and `DocumentImport.kt` are the two branches lifted out of today's
`ImportWriter` — `createTicket`, `createDocument`, `describe`, `provenance`, `link` — with
one change each: the project comes from `links.projectOfTicket[page.id]?.let(rows::project)
?: base.fallback.projectId ?: defaultProject(base)`, and every created row calls
`rows.put(...)`. `defaultProject` is today's `createProject(base, teamId)`, created lazily
so a base whose every ticket resolves creates no project at all.

`ImportedRows`:

```kotlin
/**
 * Page id → the row it became, across the passes.
 *
 * Seeded from `notion_import_origin` before the first pass, so a relation pointing at a
 * page imported *last month* resolves exactly like one imported a second ago. That is the
 * whole reason the table exists.
 */
class ImportedRows(seed: Map<String, ImportOrigin> = emptyMap()) {

	private val byKind: Map<OriginKind, MutableMap<String, UUID>> =
		OriginKind.entries.associateWith { mutableMapOf() }

	init {
		seed.forEach { (pageId, origin) -> byKind.getValue(origin.kind)[pageId] = origin.entityId }
	}

	fun put(kind: OriginKind, pageId: String, id: UUID) {
		byKind.getValue(kind)[pageId] = id
	}

	fun team(pageId: String): UUID? = byKind.getValue(OriginKind.TEAM)[pageId]
	fun project(pageId: String): UUID? = byKind.getValue(OriginKind.PROJECT)[pageId]
	fun ticket(pageId: String): UUID? = byKind.getValue(OriginKind.TICKET)[pageId]
}
```

`ImportWriter` keeps only the order, the origins and the counting:

```kotlin
@Transactional
fun write(actor: User, fallbackTeam: UUID, bases: List<PlannedBase>): ImportOutcome {
	val rows = ImportedRows(origins.byPageIds(bases.flatMap { base -> base.pages.map { it.id } }))
	val links = ImportLinks.resolve(bases)

	val teamBases = bases.filter { it.target == ImportTarget.TEAMS }
	var teams = 0
	for (base in teamBases) teams += teamImport.write(actor, base, rows)
	for (base in teamBases) teamImport.settleParents(actor, base, links, rows)

	var projects = 0
	for (base in bases.filter { it.target == ImportTarget.PROJECTS }) {
		projects += projectImport.write(actor, base, fallbackTeam, links, rows)
	}
	// … tickets, then documents, then record every origin and add up the outcome
}
```

- [ ] **Step 4: Run the tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.*'`
Expected: PASS.

- [ ] **Step 5: Check the writer files are each readable in one sitting**

Run: `wc -l apps/api/src/main/kotlin/dev/kanso/sync/importer/*.kt`
Expected: no file over ~180 lines. If `TicketImport.kt` is, the dependency linking moves to
its own `TicketLinks.kt` — a file that does one thing is the point of the split.

- [ ] **Step 6: Commit**

```bash
git add apps/api
git commit -m "feat(import): write teams, then projects, then tickets

The order is the server's, not the request's: a relation resolves only once the
row it names exists, and asking the reader to sequence their own plan would be
asking them to know that.

ImportWriter keeps the order, the origins and the arithmetic; the four targets
each get a file, because 211 lines for two targets does not survive four."
```

### Task 4: Relations read from both sides

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportLinks.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportPlanner.kt`, `ImportModel.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/importer/ImportLinksTest.kt` (new)

**Interfaces:**
- Consumes: `PlannedBase`, `ImportTarget`.
- Produces:
  ```kotlin
  object ImportLinks {
      data class Resolved(
          val parentOfTeam: Map<String, String>,     // team page → its parent team page
          val teamOfProject: Map<String, String>,    // project page → its team page
          val projectOfTicket: Map<String, String>,  // ticket page → its project page
          val dependencies: List<PageDependency>,
          val droppedRelations: Int,
          val conflicts: Int,
      )
      fun resolve(bases: List<PlannedBase>): Resolved
  }
  ```
  and `ImportOutcome.linkConflicts: Int`, `ImportPreview.linkedByRelation: Int`,
  `ImportPreview.fellBack: Int`.

- [ ] **Step 1: Write the failing test**

`ImportLinksTest.kt` — pure, no Postgres, so it extends nothing:

```kotlin
package dev.kanso.sync.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImportLinksTest {

	private fun planned(base: FakeDatabase, target: ImportTarget, mapping: ColumnMapping) =
		PlannedBase(
			base = WorkspaceBase(base.dataSourceId, base.databaseId, base.name),
			target = target,
			pages = base.pages,
			mapping = mapping,
		)

	@Test
	fun `a link declared only on the parent's side is read backwards`() {
		val ship = fakePage("Ship it")
		val tasks = FakeDatabase("Tasks", listOf(ship))
		val roadmap = fakePage("Roadmap", mapOf("Tasks" to notionRelation(ship.id)))
		val projects = FakeDatabase("Projects", listOf(roadmap))

		val resolved = ImportLinks.resolve(
			listOf(
				planned(tasks, ImportTarget.TICKETS, ColumnMapping()),
				planned(projects, ImportTarget.PROJECTS, ColumnMapping(mapOf(ImportField.TICKETS to "Tasks"))),
			)
		)

		assertEquals(mapOf(ship.id to roadmap.id), resolved.projectOfTicket)
	}

	@Test
	fun `when both sides speak and disagree, the child wins and the disagreement is counted`() {
		val ship = fakePage("Ship it", mapOf("Project" to notionRelation("page-b")))
		val tasks = FakeDatabase("Tasks", listOf(ship))
		val projectA = fakePage("A", mapOf("Tasks" to notionRelation(ship.id)), id = "page-a")
		val projectB = fakePage("B", id = "page-b")
		val projects = FakeDatabase("Projects", listOf(projectA, projectB))

		val resolved = ImportLinks.resolve(
			listOf(
				planned(tasks, ImportTarget.TICKETS, ColumnMapping(mapOf(ImportField.PROJECT to "Project"))),
				planned(projects, ImportTarget.PROJECTS, ColumnMapping(mapOf(ImportField.TICKETS to "Tasks"))),
			)
		)

		assertEquals("page-b", resolved.projectOfTicket[ship.id], "the row that names its own parent wins")
		assertEquals(1, resolved.conflicts)
	}

	@Test
	fun `only the mapped dependency column becomes a dependency`() {
		val first = fakePage("First", id = "page-1")
		val second = fakePage("Second", id = "page-2", properties = mapOf(
			"Blocked by" to notionRelation("page-1"),
			"Related" to notionRelation("page-1"),
		))
		val tasks = FakeDatabase("Tasks", listOf(first, second))

		val resolved = ImportLinks.resolve(
			listOf(planned(tasks, ImportTarget.TICKETS, ColumnMapping(mapOf(ImportField.BLOCKED_BY to "Blocked by"))))
		)

		assertEquals(listOf(PageDependency("page-1", "page-2")), resolved.dependencies)
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.ImportLinksTest'`
Expected: FAIL — `ImportLinks` and `ColumnMapping` do not exist.

- [ ] **Step 3: Write `ColumnMapping` far enough for links, and `ImportLinks`**

A first cut of `ImportMapping.kt` — Task 6 completes it:

```kotlin
package dev.kanso.sync.importer

import dev.kanso.domain.Wire
import dev.kanso.domain.parse

/** A field of a Kanso row that a Notion column can fill. */
enum class ImportField(override val wire: String, val types: Set<String>) : Wire {
	STATUS("status", setOf("select", "status")),
	PRIORITY("priority", setOf("select", "status")),
	DESCRIPTION("description", setOf("rich_text")),
	START("start", setOf("date")),
	DUE("due", setOf("date")),
	END("end", setOf("date")),
	ASSIGNEES("assignees", setOf("people")),
	LEAD("lead", setOf("people")),
	PROJECT("project", setOf("relation")),
	TEAM("team", setOf("relation")),
	PARENT_TEAM("parentTeam", setOf("relation")),
	BLOCKED_BY("blockedBy", setOf("relation")),

	/**
	 * The inverse fields: the same link, declared on the parent.
	 *
	 * A `single_property` relation exists on one side only — `NotionSchema` relies on that
	 * for `Blocked by` — so a workspace can carry the project link on the tasks base or on
	 * the projects base, and neither is more correct. Reading only the child's side would
	 * leave half of them unlinked.
	 */
	TICKETS("tickets", setOf("relation")),
	PROJECTS("projects", setOf("relation")),
	SUB_TEAMS("subTeams", setOf("relation"));

	companion object {
		fun from(raw: String): ImportField = parse(entries.toTypedArray(), raw)
	}
}

/**
 * What each field of a target reads from, and what each mapped option means.
 *
 * `values` is keyed by field rather than by property name: the property is already in
 * `columns`, and two fields reading the same column would otherwise have to share one
 * option table.
 */
data class ColumnMapping(
	val columns: Map<ImportField, String> = emptyMap(),
	val values: Map<ImportField, Map<String, String>> = emptyMap(),
) {
	fun property(field: ImportField): String? = columns[field]
}

/** Where a row lands when no relation answers. All three optional; all three per base. */
data class Fallback(
	val teamId: UUID? = null,
	val parentTeamId: UUID? = null,
	val projectId: UUID? = null,
)
```

`ImportLinks.kt` reads the mapped relation columns from both sides, child first, counting a
disagreement rather than settling it. Its `dependencies` replaces
`ImportPlanner.dependencies`, which loses its "every relation is a dependency" behaviour —
that was the only reading available before a mapping existed.

- [ ] **Step 4: Run the tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api
git commit -m "feat(import): read an imported relation from whichever side declares it

A single_property relation exists on one side only, so the project link can live
on the tasks base or on the projects base. Reading only the child's side left the
second shape with every ticket in the fallback project.

Both sides are read, the child wins a disagreement, and the disagreement is
counted — and a dependency is now only what the reader mapped as one, instead of
every relation on the page."
```

### Task 5: Where a missing link lands

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/NotionImportController.kt`,
  `sync/importer/NotionImportService.kt`, `ImportModel.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/importer/ImportFallbackTest.kt` (new)

**Interfaces:**
- Consumes: `Fallback` (Task 4).
- Produces: `ImportRequest(teamId: UUID?, people: Map<String, UUID?>, plan: List<ImportPlanRow>)`
  and `ImportPlanRow(sourceId, target, columns, values, fallback)`; `perform(actor, teamId:
  UUID?, plan)`.

- [ ] **Step 1: Write the failing test**

```kotlin
class ImportFallbackTest : ImportTestBase() {

	@Test
	fun `a teams-only plan needs no destination team`() {
		val teams = FakeDatabase("Teams", listOf(fakePage("Platform")))
		val outcome = importerFor(teams).perform(admin, null, plan(teams to ImportTarget.TEAMS))
		assertEquals(1, outcome.teams)
	}

	@Test
	fun `anything else without a destination is refused before a page is read`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it")))
		val importer = importerFor(tasks)

		val failure = assertThrows<BadRequestException> {
			importer.perform(admin, null, plan(tasks to ImportTarget.TICKETS))
		}
		assertTrue(failure.message!!.contains("team"))
		assertEquals(RowCounts(0, 0, 0, 0), rowCounts())
	}

	@Test
	fun `a base's own fallback beats the request's destination`() {
		val other = teamService.create(admin, "Other", "OTH", null)
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it")))

		importerFor(tasks).perform(
			admin, team.id,
			listOf(ImportPlanEntry(tasks.dataSourceId, ImportTarget.TICKETS, fallback = Fallback(teamId = other.id))),
		)

		val ticket = ticketRows.search(includeArchived = false, limit = 50).first()
		assertEquals(other.id, ticket.teamId)
	}

	@Test
	fun `a fallback project is used instead of creating one named after the base`() {
		val existing = projectService.create(
			name = "Existing", status = ProjectStatus.IN_PROGRESS, start = null, end = null,
			leadUserId = null, teamId = team.id, docIds = emptyList(),
		).project
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it")))

		importerFor(tasks).perform(
			admin, team.id,
			listOf(ImportPlanEntry(tasks.dataSourceId, ImportTarget.TICKETS, fallback = Fallback(projectId = existing.id))),
		)

		assertEquals(1, projectRows.search(teamIds = null, includeArchived = false).size)
		assertEquals(existing.id, ticketRows.search(includeArchived = false, limit = 50).first().projectId)
	}
}
```

Add the one bean the test needs to `ImportTestBase`, beside the repositories already there:

```kotlin
@Autowired protected lateinit var projectService: ProjectService
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.ImportFallbackTest'`
Expected: FAIL — `perform` does not take a nullable team, `ImportPlanEntry` has no fallback.

- [ ] **Step 3: Make the destination conditional and the fallback per base**

`NotionImportService.perform`:

```kotlin
fun perform(actor: User, teamId: UUID?, plan: List<ImportPlanEntry>): ImportOutcome {
	// The team is checked before a single page is read: an import is a large write, and the
	// refusal belongs in front of the work rather than after four hundred inserts have to
	// be rolled back.
	val needsDestination = plan.any { it.target != ImportTarget.TEAMS && it.fallback.teamId == null }
	if (needsDestination && teamId == null) {
		throw BadRequestException(
			"Choose the team imported work lands in. Only an import of teams alone needs no destination."
		)
	}
	teamId?.let { access.requireTeam(actor, it) }
	plan.mapNotNull { it.fallback.teamId }.distinct().forEach { access.requireTeam(actor, it) }
	return writer.write(actor, teamId, read(plan))
}
```

`ImportPlanRow` on the controller grows `columns`, `values` and `fallback`, each defaulting
to empty, and `entry` maps them into `ImportPlanEntry`. `teamId` on `ImportRequest` becomes
`UUID?`.

- [ ] **Step 4: Run the tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api
git commit -m "feat(import): let a base say where its unlinked rows land

An import of teams alone has no destination to ask about, and a base whose team
cannot be resolved may want a different one from its neighbour. So the
destination is required only when something needs it, and a per-base fallback
beats the request's own.

Both are access-checked before a page is read: the refusal belongs in front of
the work, not after four hundred inserts have to roll back."
```

---

# STAGE 2 — Columns, and the values inside them

### Task 6: A data source's schema, and the mapping suggested from it

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/sync/importer/ImportSchema.kt`
- Modify: `sync/notion/NotionClient.kt`, `HttpNotionClient.kt`, `NoopNotionClient.kt`,
  `ReloadableNotionClient.kt`, `sync/importer/NotionDiscovery.kt`, `ImportMapping.kt`,
  `api/NotionImportController.kt`, `apps/api/src/test/kotlin/dev/kanso/sync/importer/FakeNotionWorkspace.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/importer/ImportSchemaTest.kt` (new)

**Interfaces:**
- Consumes: `ImportField`, `ImportTarget`, `ColumnMapping`.
- Produces:
  ```kotlin
  data class NotionDataSource(val id: String, val name: String, val properties: JsonNode?)
  suspend fun NotionClient.retrieveDataSource(dataSourceId: String): NotionDataSource?

  data class SchemaColumn(val name: String, val type: String, val options: List<String>, val relationTo: String?)
  data class FieldCandidates(val field: ImportField, val candidates: List<String>)
  data class ImportSchemaView(
      val sourceId: String,
      val target: ImportTarget,
      val columns: List<SchemaColumn>,
      val fields: List<FieldCandidates>,
      val suggestion: ColumnMapping,
      val defaults: Map<ImportField, String?>,
  )
  object ImportSchema { fun of(source: NotionDataSource, target: ImportTarget): ImportSchemaView }
  ```
  Endpoint `GET /api/notion/import/schema?sourceId=…&target=tickets` → `ImportSchemaView`.

- [ ] **Step 1: Write the failing test**

```kotlin
class ImportSchemaTest {

	private val json = ObjectMapper()

	private fun source(properties: Map<String, Any?>) = NotionDataSource(
		id = "ds-1", name = "Tasks", properties = json.valueToTree(properties),
	)

	@Test
	fun `a column is offered only for the fields its type can fill`() {
		val view = ImportSchema.of(
			source(
				mapOf(
					"Name" to mapOf("type" to "title"),
					"État" to mapOf("type" to "select", "select" to mapOf("options" to listOf(mapOf("name" to "En cours")))),
					"Échéance" to mapOf("type" to "date"),
					"Projet" to mapOf("type" to "relation", "relation" to mapOf("data_source_id" to "ds-2")),
				)
			),
			ImportTarget.TICKETS,
		)

		assertEquals(listOf("État"), view.fields.single { it.field == ImportField.STATUS }.candidates)
		assertEquals(listOf("Échéance"), view.fields.single { it.field == ImportField.DUE }.candidates)
		assertEquals(listOf("Projet"), view.fields.single { it.field == ImportField.PROJECT }.candidates)
		assertEquals("ds-2", view.columns.single { it.name == "Projet" }.relationTo)
	}

	@Test
	fun `the title property is never offered — it is found by type, not chosen`() {
		val view = ImportSchema.of(source(mapOf("Name" to mapOf("type" to "title"))), ImportTarget.TICKETS)
		assertTrue(view.columns.none { it.type == "title" })
	}

	@Test
	fun `today's English names are suggested, and nothing else is guessed`() {
		val view = ImportSchema.of(
			source(
				mapOf(
					"Name" to mapOf("type" to "title"),
					"Status" to mapOf("type" to "status", "status" to mapOf("options" to listOf(mapOf("name" to "Done")))),
					"Etat" to mapOf("type" to "select", "select" to mapOf("options" to listOf(mapOf("name" to "Fini")))),
				)
			),
			ImportTarget.TICKETS,
		)

		assertEquals("Status", view.suggestion.property(ImportField.STATUS))
		assertEquals(mapOf("Done" to "done"), view.suggestion.values[ImportField.STATUS])
	}

	@Test
	fun `an option outside the vocabulary is suggested as nothing, and the default is named`() {
		val view = ImportSchema.of(
			source(
				mapOf(
					"Name" to mapOf("type" to "title"),
					"Status" to mapOf("type" to "select", "select" to mapOf("options" to listOf(mapOf("name" to "Blocked")))),
				)
			),
			ImportTarget.TICKETS,
		)

		assertEquals(emptyMap<String, String>(), view.suggestion.values[ImportField.STATUS])
		assertEquals("todo", view.defaults[ImportField.STATUS])
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.ImportSchemaTest'`
Expected: FAIL — `ImportSchema` does not exist.

- [ ] **Step 3: Add the client call**

`NotionClient.kt`:

```kotlin
/**
 * A data source's own schema.
 *
 * The import needs this and the pages are not enough: a column empty on every page read is
 * invisible in the pages and present here, a select's options must be listed even when no
 * page uses them, and a relation's target data source exists nowhere else.
 */
suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource?
```

`HttpNotionClient`: `request("GET", "/data_sources/$dataSourceId", null)?.let { NotionDataSource(
it.path("id").asText(dataSourceId), plainTitle(it.path("title")), it.path("properties")) }`.
`NoopNotionClient` answers `null`; `ReloadableNotionClient` delegates.
`FakeNotionWorkspace` answers from a new `FakeDatabase.properties: Map<String, Any?>`
field, defaulting to a `title` property called `Name` so existing fakes keep working.

- [ ] **Step 4: Write `ImportSchema` and the target's fields**

`ImportTarget` gains:

```kotlin
val fields: Set<ImportField> get() = when (this) {
	TEAMS -> setOf(ImportField.PARENT_TEAM, ImportField.PROJECTS, ImportField.SUB_TEAMS)
	PROJECTS -> setOf(
		ImportField.STATUS, ImportField.START, ImportField.END, ImportField.LEAD,
		ImportField.TEAM, ImportField.TICKETS,
	)
	TICKETS -> setOf(
		ImportField.STATUS, ImportField.PRIORITY, ImportField.DESCRIPTION, ImportField.START,
		ImportField.DUE, ImportField.ASSIGNEES, ImportField.PROJECT, ImportField.BLOCKED_BY,
	)
	DOCUMENTS -> emptySet()
}
```

`ImportSchema.of` reads the properties into `SchemaColumn`s (skipping `title`), offers each
column to the fields whose `types` contain its type, and suggests: the property whose name
matches today's `NotionProps` constant for that field, case-insensitively, and for a
select-backed field the options whose label lands on a `fromLabel`. `defaults` is the table
from the spec, as wire strings, so the screen prints what will happen rather than inventing
a sentence.

- [ ] **Step 5: Expose it**

```kotlin
@GetMapping("/schema")
fun schema(@RequestParam sourceId: String, @RequestParam target: String): ImportSchemaView =
	imports.schema(sourceId, ImportTarget.from(target))
```

`NotionImportService.schema` calls `discovery.schema(sourceId)` — a `runBlocking` around
`client.retrieveDataSource`, refusing with the same sentences `sources()` uses when Notion
rate-limits or refuses.

- [ ] **Step 6: Run the tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.*' --tests 'dev.kanso.sync.notion.*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/api
git commit -m "feat(import): read a base's schema, and suggest a mapping from it

The pages are not enough: a column empty on every page is invisible in them, a
select's options must be listed even when unused, and a relation's target data
source exists only in the schema.

The suggestion is computed here rather than in the browser, so the pre-fill has
one definition instead of two that drift."
```

### Task 7: Reading a page through a mapping

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/sync/importer/MappedPageReader.kt`
- Delete: `apps/api/src/main/kotlin/dev/kanso/sync/importer/NotionPageReader.kt`
- Modify: every caller — `ImportPlanner.kt`, `ImportWriter.kt`, `TeamImport.kt`,
  `ProjectImport.kt`, `TicketImport.kt`, `DocumentImport.kt`, `ImportModel.kt`,
  `FakeNotionWorkspace.kt:row`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/importer/MappedPageReaderTest.kt` (new)

**Interfaces:**
- Consumes: `ColumnMapping`, `ImportField`.
- Produces:
  ```kotlin
  class MappedPageReader(private val mapping: ColumnMapping) {
      fun title(page: NotionPage): String?
      fun refusal(page: NotionPage): String?
      fun status(page: NotionPage): TicketStatus?
      fun priority(page: NotionPage): TicketPriority?
      fun projectStatus(page: NotionPage): ProjectStatus?
      fun description(page: NotionPage): String?
      fun start(page: NotionPage): KansoInstant?
      fun due(page: NotionPage): KansoInstant?
      fun end(page: NotionPage): KansoInstant?
      fun people(page: NotionPage, field: ImportField): List<NotionPerson>
      fun relations(page: NotionPage, field: ImportField): List<String>
      fun unmapped(page: NotionPage): List<Pair<String, String>>
  }
  data class NotionPerson(val id: String, val name: String?)
  ```
  and `PlannedBase.reader: MappedPageReader`.

- [ ] **Step 1: Write the failing test**

```kotlin
class MappedPageReaderTest {

	@Test
	fun `a mapped option outside the vocabulary takes the default rather than becoming a status`() {
		val reader = MappedPageReader(ColumnMapping(columns = mapOf(ImportField.STATUS to "Etat")))
		val page = fakePage("Ship it", mapOf("Etat" to notionSelect("Bloqué")))

		assertNull(reader.status(page), "the writer is what applies the default")
	}

	@Test
	fun `a mapped value is what decides, not the label`() {
		val reader = MappedPageReader(
			ColumnMapping(
				columns = mapOf(ImportField.STATUS to "Etat"),
				values = mapOf(ImportField.STATUS to mapOf("Bloqué" to "todo")),
			)
		)
		val page = fakePage("Ship it", mapOf("Etat" to notionSelect("Bloqué")))

		assertEquals(TicketStatus.TODO, reader.status(page))
	}

	@Test
	fun `an unmapped column is preserved verbatim, and a mapped one is not preserved twice`() {
		val reader = MappedPageReader(ColumnMapping(columns = mapOf(ImportField.STATUS to "Etat")))
		val page = fakePage(
			"Ship it",
			mapOf("Etat" to notionSelect("En cours"), "Sprint" to notionText("S12")),
		)

		assertEquals(listOf("Sprint" to "S12"), reader.unmapped(page))
	}

	@Test
	fun `the title is still found by type, so a French base is not four hundred Untitleds`() {
		val reader = MappedPageReader(ColumnMapping())
		assertEquals("Livrer", reader.title(fakePage("Livrer", titleProperty = "Nom")))
	}

	@Test
	fun `people are read as ids and names, which is what the person screen needs`() {
		val reader = MappedPageReader(ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")))
		val page = fakePage("Ship it", mapOf("Qui" to notionPeople("user-1" to "M. Rey")))

		assertEquals(listOf(NotionPerson("user-1", "M. Rey")), reader.people(page, ImportField.ASSIGNEES))
	}
}
```

Add the helper `FakeNotionWorkspace.kt` needs:

```kotlin
fun notionPeople(vararg people: Pair<String, String?>): Map<String, Any?> = mapOf(
	"type" to "people",
	"people" to people.map { (id, name) -> mapOf("object" to "user", "id" to id, "name" to name) },
)
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.MappedPageReaderTest'`
Expected: FAIL — `MappedPageReader` does not exist.

- [ ] **Step 3: Write it**

Lift every private JSON helper from `NotionPageReader` unchanged — `plainText`, `instant`,
`propertyText`, `dateText`, `formulaText`, `rollupText` — and replace the name lookups with
`mapping.property(field)`. `status` becomes:

```kotlin
fun status(page: NotionPage): TicketStatus? = option(page, ImportField.STATUS)?.let { chosen ->
	// A mapped answer beats a matching label: the reader was shown both and picked.
	mapping.values[ImportField.STATUS]?.get(chosen)?.let(TicketStatus::from)
		?: TicketStatus.fromLabel(chosen)
}
```

`unmapped` skips the properties named in `mapping.columns.values`, the `title`, and the
mirror's own bookkeeping names — the `ADOPTED` set shrinks to `KANSO_ID` and `IDENTIFIER`,
because a base that once *was* a Kanso mirror must not offer `Kanso ID` as content to
preserve, and everything else is now the mapping's business.

`PlannedBase` gains `val mapping: ColumnMapping = ColumnMapping()` and
`val reader by lazy { MappedPageReader(mapping) }`; `refusal` and `adoptable` go through it.
`FakeNotionWorkspace.row` uses `MappedPageReader(ColumnMapping()).title(page)`.

- [ ] **Step 4: Delete `NotionPageReader` and run everything**

Run: `cd apps/api && ./gradlew test`
Expected: PASS. Any remaining reference to `NotionPageReader` is a compile error, which is
the point of deleting it in the same commit.

- [ ] **Step 5: Commit**

```bash
git add apps/api
git commit -m "feat(import): read a page through the mapping it was given

NotionPageReader answered 'which property is the status' from a constant, which
imported a French workspace as four hundred tickets in Todo. The question now
comes with the request; the conversions, the lossiness and the 'Imported from
Notion' section are unchanged.

The title is still found by type. It is the only property Notion requires of
every database, and matching on \"Name\" is what broke."
```

### Task 8: The mapping on the wire, end to end

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/NotionImportController.kt`,
  `sync/importer/NotionImportService.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/importer/NotionImportMappingTest.kt` (new)

**Interfaces:**
- Consumes: everything from Tasks 4–7.
- Produces the request shape the web sends:
  ```
  { teamId?, people: {notionPersonId: userId?}, plan: [{ sourceId, target,
    columns: {field: property}, values: {field: {option: kansoWire}},
    fallback: {teamId?, parentTeamId?, projectId?} }] }
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
class NotionImportMappingTest : ImportTestBase() {

	@Test
	fun `a French base imports with its own columns and its own options`() {
		val tasks = FakeDatabase(
			"Tâches",
			listOf(
				fakePage(
					"Livrer",
					mapOf(
						"Etat" to notionSelect("En cours"),
						"Urgence" to notionSelect("Haute"),
						"Détail" to notionText("Ce qu'il reste"),
						"Échéance" to notionDate("2026-09-01"),
					),
					titleProperty = "Nom",
				)
			),
		)

		importerFor(tasks).perform(
			admin, team.id,
			listOf(
				ImportPlanEntry(
					sourceId = tasks.dataSourceId,
					target = ImportTarget.TICKETS,
					mapping = ColumnMapping(
						columns = mapOf(
							ImportField.STATUS to "Etat",
							ImportField.PRIORITY to "Urgence",
							ImportField.DESCRIPTION to "Détail",
							ImportField.DUE to "Échéance",
						),
						values = mapOf(
							ImportField.STATUS to mapOf("En cours" to "in_progress"),
							ImportField.PRIORITY to mapOf("Haute" to "high"),
						),
					),
				)
			),
		)

		val ticket = ticketRows.search(includeArchived = false, limit = 50).single()
		assertEquals("Livrer", ticket.title)
		assertEquals(TicketStatus.IN_PROGRESS, ticket.status)
		assertEquals(TicketPriority.HIGH, ticket.priority)
		assertEquals("Ce qu'il reste", ticket.description?.lines()?.first())
		assertEquals(LocalDate.parse("2026-09-01"), ticket.due?.value?.toLocalDate())
	}

	@Test
	fun `the preview lists the properties nothing claimed`() {
		val tasks = FakeDatabase("Tâches", listOf(fakePage("Livrer", mapOf("Sprint" to notionText("S12")))))
		val preview = importerFor(tasks).preview(plan(tasks to ImportTarget.TICKETS))

		assertEquals(listOf("Sprint"), preview.unmappedProperties)
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.NotionImportMappingTest'`
Expected: FAIL — `ImportPlanEntry` has no `mapping`.

- [ ] **Step 3: Carry the mapping from the controller to the planner**

```kotlin
data class ImportPlanRow(
	val sourceId: String,
	val target: String,
	val columns: Map<String, String> = emptyMap(),
	val values: Map<String, Map<String, String>> = emptyMap(),
	val fallback: ImportFallbackRow = ImportFallbackRow(),
)

data class ImportFallbackRow(val teamId: UUID? = null, val parentTeamId: UUID? = null, val projectId: UUID? = null)

/** An unknown field or target is a 400 through `ApiExceptionHandler`, like an unknown status. */
private fun entry(row: ImportPlanRow) = ImportPlanEntry(
	sourceId = row.sourceId,
	target = ImportTarget.from(row.target),
	mapping = ColumnMapping(
		columns = row.columns.mapKeys { (field, _) -> ImportField.from(field) },
		values = row.values.mapKeys { (field, _) -> ImportField.from(field) },
	),
	fallback = Fallback(row.fallback.teamId, row.fallback.parentTeamId, row.fallback.projectId),
)
```

`NotionImportService.read` passes `entry.mapping` into each `PlannedBase`.

- [ ] **Step 4: Run the tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api
git commit -m "feat(import): carry the column and value mapping on the request

An unknown field name is a 400 through the usual handler, like an unknown
status: the vocabulary is closed on both sides of the wire or it is not closed."
```

### Task 9: The columns screen

**Files:**
- Create: `apps/web/src/components/inbox/import-columns.ts`, `import-columns.test.ts`,
  `import-step-columns.tsx`
- Modify: `apps/web/src/lib/api/inbox.ts`, `components/inbox/import-dialog.tsx`,
  `import-step-two.tsx`, `import-step-three.tsx`
- Test: `apps/web/src/components/inbox/import-columns.test.ts`

**Interfaces:**
- Consumes: `GET /api/notion/import/schema` (Task 6), the request shape (Task 8).
- Produces:
  ```ts
  export type NotionSchemaColumn = { name: string; type: string; options: string[]; relationTo?: string };
  export type NotionImportSchema = {
    sourceId: string; target: ImportTarget;
    columns: NotionSchemaColumn[];
    fields: { field: string; candidates: string[] }[];
    suggestion: { columns: Record<string, string>; values: Record<string, Record<string, string>> };
    defaults: Record<string, string | null>;
  };
  export type BaseMapping = { columns: Record<string, string>; values: Record<string, Record<string, string>> };
  export function answeredFields(schema: NotionImportSchema, mapping: BaseMapping): number;
  export function unmappedOptions(schema: NotionImportSchema, mapping: BaseMapping, field: string): string[];
  export function suggestionsFrom(schema: NotionImportSchema, kept: Record<string, ImportTarget>): { sourceId: string; target: ImportTarget }[];
  ```

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from "vitest";
import { answeredFields, suggestionsFrom, unmappedOptions, type NotionImportSchema } from "./import-columns";

const SCHEMA: NotionImportSchema = {
  sourceId: "tasks",
  target: "tickets",
  columns: [
    { name: "Etat", type: "select", options: ["En cours", "Bloqué"] },
    { name: "Projet", type: "relation", options: [], relationTo: "projects" },
  ],
  fields: [
    { field: "status", candidates: ["Etat"] },
    { field: "project", candidates: ["Projet"] },
  ],
  suggestion: { columns: {}, values: {} },
  defaults: { status: "todo", project: null },
};

describe("what the columns screen derives", () => {
  it("counts the fields that have an answer, which is the header's number", () => {
    expect(answeredFields(SCHEMA, { columns: { status: "Etat" }, values: {} })).toBe(1);
  });

  it("names the options still falling on the default, which is what the warning lists", () => {
    const mapping = { columns: { status: "Etat" }, values: { status: { "En cours": "in_progress" } } };
    expect(unmappedOptions(SCHEMA, mapping, "status")).toEqual(["Bloqué"]);
  });

  it("suggests importing the base a mapped relation points at, and only while it is ignored", () => {
    const mapping = { columns: { project: "Projet" }, values: {} };
    expect(suggestionsFrom({ ...SCHEMA, suggestion: mapping }, { tasks: "tickets" })).toEqual([
      { sourceId: "projects", target: "projects" },
    ]);
    expect(suggestionsFrom({ ...SCHEMA, suggestion: mapping }, { tasks: "tickets", projects: "projects" })).toEqual([]);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/web && pnpm vitest run src/components/inbox/import-columns.test.ts`
Expected: FAIL — the module does not exist.

- [ ] **Step 3: Write `import-columns.ts`**

Three small functions over the schema, no fetching and no state — the screen holds those.
`suggestionsFrom` maps a mapped relation field onto the target implied by the field
(`project → projects`, `team → teams`, `tickets → tickets`, `subTeams`/`parentTeam` →
`teams`) and drops the bases already kept.

- [ ] **Step 4: Run it and watch it pass**

Run: `cd apps/web && pnpm vitest run src/components/inbox/import-columns.test.ts`
Expected: PASS.

- [ ] **Step 5: Draw the screen**

`import-step-columns.tsx` — one `<section>` per kept base, headed by the base's name, its
target, and `answeredFields`; one row per `schema.fields` entry with a `<select>` of
`candidates` plus `— none —`; under a select-backed mapped field, one row per option with a
`<select>` of that field's Kanso values; a line naming the options still on the default via
`unmappedOptions`; and, only when a link is unresolvable for that base, the three fallback
selects fed by `useTeams()` and the project list. A people column mapped anywhere shows the
one-line invitation to step 4.

Each base's schema comes from its own `useQuery(["notion-import-schema", sourceId, target])`,
so a base whose schema fails does not blank the screen. The suggestion seeds that base's
mapping on first arrival, keyed by `sourceId`, and the reader's edits win from then on.

- [ ] **Step 6: Wire the dialog to five steps**

`import-dialog.tsx` holds `mappings: Record<string, BaseMapping>`, `fallbacks:
Record<string, Fallback>` and `people: Record<string, string | null>`; `step` becomes
`1 | 2 | 3 | 4 | 5`; the header reads `step {step} of 5` and the progress bar takes five
marks. Step 4 is skipped in both directions when no people column is mapped — one
`hasPeople` boolean, computed from the mappings, decides `onNext` and `onBack` so the skip
cannot disagree with itself.

- [ ] **Step 7: Typecheck, test, and look at it**

Run: `cd apps/web && pnpm tsc --noEmit && pnpm vitest run src/components/inbox`
Expected: PASS.

Then `docker compose up`, open the dialog from `⌘K → Import from Notion…`, and confirm the
five steps walk forwards and backwards with an empty workspace (step 1 prints its sentence).

- [ ] **Step 8: Commit**

```bash
git add apps/web
git commit -m "feat(web): map Notion columns and their options, per base

One section per kept base, because three targets have three sets of fields. Every
select is pre-filled from the server's suggestion and every one of them is
overridable — the pre-fill is a default, not a rule.

The options still falling on a Kanso default are named rather than counted: a
number tells you something was guessed, a list tells you what."
```

---

# STAGE 3 — People, and the page that outlives the import

### Task 10: The workspace's members

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/sync/notion/NotionPeople.kt`,
  `apps/api/src/main/kotlin/dev/kanso/api/NotionPeopleController.kt`
- Modify: `sync/notion/NotionClient.kt`, `HttpNotionClient.kt`, `NoopNotionClient.kt`,
  `ReloadableNotionClient.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/notion/NotionPeopleTest.kt` (new)

**Interfaces:**
- Produces:
  ```kotlin
  data class NotionMember(val id: String, val name: String?, val email: String?)
  suspend fun NotionClient.listUsers(): List<NotionMember>   // throws NotionApiException on 403

  data class PeopleMatch(val notion: NotionMember, val userId: UUID?, val suggestedUserId: UUID?)
  data class PeopleView(val available: Boolean, val reason: String?, val people: List<PeopleMatch>)

  @Service class NotionPeople {
      fun view(): PeopleView
      fun link(assignments: Map<String, UUID?>)
  }
  ```
  Endpoints `GET /api/notion/people` → `PeopleView`, `PUT /api/notion/people` with
  `{ "notion-user-id": "kanso-uuid-or-null" }`.

- [ ] **Step 1: Write the failing test**

```kotlin
class NotionPeopleTest : PostgresTest() {

	@Test
	fun `a workspace that refuses the members list becomes a sentence, not an empty page`() {
		// `NoopNotionClient` is final and answers no members; the refusal is the thing under
		// test, so the fake is one `NotionClient` method and `TODO()` for the rest — see
		// `clientReturning` below, which both tests share.
		val people = NotionPeople(client = clientRefusing("API token does not have access to user information"), users = users)

		val view = people.view()
		assertFalse(view.available)
		assertTrue(view.reason!!.contains("read user information"), "the sentence says which box to tick")
		assertTrue(view.people.isEmpty())
	}

	@Test
	fun `an email that matches a Kanso account is suggested but not applied`() {
		val kanso = users.createLocalUser("m.rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val people = NotionPeople(client = clientReturning(NotionMember("u-1", "M. Rey", "m.rey@kanso.test")), users = users)

		val match = people.view().people.single()
		assertEquals(kanso.id, match.suggestedUserId)
		assertNull(match.userId, "suggested is not linked: an homonym must not silently own the work")
	}

	@Test
	fun `linking writes notion_person_id, and unlinking clears it`() {
		val kanso = users.createLocalUser("m.rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val people = NotionPeople(client = clientReturning(NotionMember("u-1", "M. Rey", null)), users = users)

		people.link(mapOf("u-1" to kanso.id))
		assertEquals("u-1", users.findById(kanso.id)!!.notionPersonId)

		people.link(mapOf("u-1" to null))
		assertNull(users.findById(kanso.id)!!.notionPersonId)
	}
}
```

The two fakes those tests share, at the bottom of the same file. `NoopNotionClient` is
final and `FakeNotionWorkspace` is about databases, so this is one method and a refusal for
everything else — a client that answered calls `NotionPeople` must never make would be
testing the fake:

```kotlin
private fun clientReturning(vararg members: NotionMember): NotionClient =
	object : NotionClient {
		override val enabled = true
		override suspend fun listUsers(): List<NotionMember> = members.toList()
		override suspend fun botUserId(): String? = throw UnsupportedOperationException()
		// … every other member of NotionClient throws UnsupportedOperationException()
	}

private fun clientRefusing(message: String): NotionClient =
	object : NotionClient {
		override val enabled = true
		override suspend fun listUsers(): List<NotionMember> = throw NotionApiException(message)
		override suspend fun botUserId(): String? = throw UnsupportedOperationException()
		// … as above
	}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.notion.NotionPeopleTest'`
Expected: FAIL — `NotionPeople` does not exist.

- [ ] **Step 3: Add `listUsers` and the service**

`HttpNotionClient` walks `GET /users` by cursor, keeping `type == "person"` entries and
reading `person.email` where it is there. A 403 arrives as `NotionApiException`, which
`NotionPeople.view` turns into:

> "This integration cannot read the workspace's members. Tick **read user information** on
> its capabilities in Notion, then reload — Kanso needs it only to match Notion people to
> Kanso accounts."

The suggestion is by email first, then by exact display name, and is never applied on its
own: `userId` is what `users.notion_person_id` already says. `link` writes through
`UserRepository.setNotionPersonId`, clearing any other account holding the same id first —
the column is unique per person in practice and two accounts claiming one Notion person is
how the mirror writes the wrong `people` value.

- [ ] **Step 4: Expose it, admin only**

```kotlin
@RestController
@RequestMapping("/api/notion/people")
class NotionPeopleController(private val people: NotionPeople, private val currentUser: CurrentUser) {

	@GetMapping fun view(): PeopleView = people.view()

	/** Configuring the instance's identities is a configurator's job, like the connection itself. */
	@PutMapping
	fun link(@RequestBody assignments: Map<String, UUID?>): PeopleView {
		people.requireConfigurator(currentUser.require())
		people.link(assignments)
		return people.view()
	}
}
```

- [ ] **Step 5: Run the tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.*Notion*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api
git commit -m "feat(notion): match workspace members to Kanso accounts

GET /users needs the integration's 'read user information' capability, so a 403
is the expected answer rather than a bug — it becomes the sentence naming the box
to tick instead of an empty list nobody can explain.

An email match is a suggestion, never applied: a homonym silently owning
somebody's work is the failure this screen exists to prevent."
```

### Task 11: Assignees on import

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/importer/TicketImport.kt`,
  `ProjectImport.kt`, `NotionImportService.kt`, `ImportModel.kt`,
  `api/NotionImportController.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/sync/importer/ImportPeopleTest.kt` (new)

**Interfaces:**
- Consumes: `NotionPerson` (Task 7), `NotionPeople.link` (Task 10).
- Produces: `GET /api/notion/import/people-seen` (POST-shaped plan in the body) →
  `List<NotionPerson>`; `ImportOutcome.assigned: Int`.

- [ ] **Step 1: Write the failing test**

```kotlin
class ImportPeopleTest : ImportTestBase() {

	@Test
	fun `a mapped person becomes the assignee, and the link is remembered`() {
		val rey = users.createLocalUser("rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		teamService.addMember(admin, team.id, rey.id, MemberRole.MEMBER)
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-1" to "M. Rey")))))

		importerFor(tasks).perform(
			admin, team.id,
			people = mapOf("u-1" to rey.id),
			plan = listOf(
				ImportPlanEntry(
					tasks.dataSourceId, ImportTarget.TICKETS,
					mapping = ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")),
				)
			),
		)

		val ticket = ticketRows.search(includeArchived = false, limit = 50).single()
		assertEquals(listOf(rey.id), ticketRows.assigneeIds(ticket.id))
		assertEquals("u-1", users.findById(rey.id)!!.notionPersonId)
	}

	@Test
	fun `a person nobody mapped leaves the ticket unassigned and the column preserved`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-9" to "Someone")))))

		importerFor(tasks).perform(
			admin, team.id,
			plan = listOf(
				ImportPlanEntry(
					tasks.dataSourceId, ImportTarget.TICKETS,
					mapping = ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")),
				)
			),
		)

		val ticket = ticketRows.search(includeArchived = false, limit = 50).single()
		assertTrue(ticketRows.assigneeIds(ticket.id).isEmpty())
	}

	@Test
	fun `the people a plan would meet are listed without writing anything`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-1" to "M. Rey")))))
		val importer = importerFor(tasks)
		val before = rowCounts()

		val seen = importer.peopleSeen(
			listOf(
				ImportPlanEntry(
					tasks.dataSourceId, ImportTarget.TICKETS,
					mapping = ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")),
				)
			)
		)

		assertEquals(listOf(NotionPerson("u-1", "M. Rey")), seen)
		assertEquals(before, rowCounts())
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.ImportPeopleTest'`
Expected: FAIL — `perform` takes no people map.

- [ ] **Step 3: Thread the people map through**

`perform(actor, teamId, people: Map<String, UUID?>, plan)`; the map is applied through
`NotionPeople.link` before the writer runs, so the correspondence outlives the import
whether or not the assignment succeeds. `TicketImport` resolves an assignee as
`people[person.id]`, dropping a user who is not a member of the destination team and
counting it — `TicketService.create` refuses a non-member, and one such page must not fail
an import of four hundred. `ProjectImport` reads `LEAD` the same way, taking the first
resolved person.

`peopleSeen` reads the plan through `read(plan)` and collects
`base.reader.people(page, field)` for every mapped people field, distinct by id — the same
path the preview takes, and equally unable to write.

- [ ] **Step 4: Run the tests**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.sync.importer.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api
git commit -m "feat(import): assign imported work to the people who own it

The correspondence is written before the assignment, so it holds even for a
ticket whose assignee turns out not to be a member of the destination team —
that page is counted and left unassigned rather than failing the import.

Which is also what lets the mirror fill the people property afterwards: the
mapping the import needed is the mapping the outbound push was missing."
```

### Task 12: The two screens that use it

**Files:**
- Create: `apps/web/src/components/inbox/import-step-people.tsx`,
  `apps/web/src/components/settings/people-section.tsx`
- Modify: `apps/web/src/components/settings/panel.tsx`, `apps/web/src/lib/api/inbox.ts`,
  `components/inbox/import-dialog.tsx`
- Test: covered by Task 13's Playwright pass; the pure parts are Task 9's

**Interfaces:**
- Consumes: `GET/PUT /api/notion/people`, `POST /api/notion/import/people-seen`.
- Produces: `notionPeopleApi.view()`, `notionPeopleApi.link(assignments)`.

- [ ] **Step 1: Write the API layer**

In `lib/api/inbox.ts`, beside `notionImportApi`:

```ts
/**
 * `available: false` is a first-class answer here too: an integration without the
 * "read user information" capability cannot list members, and `reason` is the sentence
 * saying where to tick it.
 */
export type NotionPeopleView = {
  available: boolean;
  reason?: string;
  people: { notion: { id: string; name?: string; email?: string }; userId?: string; suggestedUserId?: string }[];
};

export const notionPeopleApi = {
  view: () => request<NotionPeopleView>("/api/notion/people"),
  link: (assignments: Record<string, string | null>) =>
    request<NotionPeopleView>("/api/notion/people", { method: "PUT", body: JSON.stringify(assignments) }),
};
```

- [ ] **Step 2: Draw step 4**

`import-step-people.tsx` — one row per person the plan met, each with a `<select>` of Kanso
members, pre-selected to `userId ?? suggestedUserId ?? ""`. A suggested-but-unlinked row
carries the word "suggested" so an accepted guess is a decision rather than a default. The
sentence above says it is filled once and holds for every later import, and links to the
settings page for the rest of the workspace.

- [ ] **Step 3: Draw the settings page**

`people-section.tsx` — the same rows, fed by `notionPeopleApi.view()`, saved by
`link()`, plus the 403 sentence when `available` is false. A new file rather than a section
inside `connections-section.tsx`, which is already 375 lines and about a different subject.
Registered in `panel.tsx` under the connections entry.

- [ ] **Step 4: Typecheck and look at both**

Run: `cd apps/web && pnpm tsc --noEmit && pnpm vitest run src/components`
Expected: PASS. Then `docker compose up`, open settings (`,`) → the people section, and
confirm the sentence appears with no Notion configured.

- [ ] **Step 5: Commit**

```bash
git add apps/web
git commit -m "feat(web): the person correspondence, in the import and on its own page

Step 4 asks only about the people the plan met. The settings page asks about the
whole workspace, because the mapping is worth more than the import that needed
it — it is what lets the mirror write the people column back.

A suggested row says 'suggested' until it is confirmed: an accepted guess should
be a decision, not a default nobody noticed."
```

### Task 13: The pass through all five screens, and what this cost

**Files:**
- Modify: `e2e/import.spec.ts` (new), `e2e/shots.spec.ts`
- Modify: `docs/architecture.md`, `docs/architecture.fr.md`, `docs/follow-ups.md`,
  `README.md`
- Test: `e2e/import.spec.ts`

- [ ] **Step 1: Write the Playwright pass**

Against the compose stack with `NOTION_TOKEN` unset, the dialog's step 1 prints its
sentence — so the pass that walks five steps needs a workspace. Seed it the way the API
tests do, through a test-only profile: `KANSO_NOTION_FAKE=workspace` binds
`FakeNotionWorkspace` as the `NotionClient` bean with three databases (teams, projects,
tasks, related). The e2e spec then walks: open from `⌘K`, keep the three bases with the
right targets, accept the relation suggestion, map `Etat → status` and `En cours → In
progress`, map one person, confirm, and assert the resulting ticket's status, project and
assignee on screen 01.

- [ ] **Step 2: Run it**

Run: `docker compose up -d && cd e2e && pnpm test:e2e --grep import`
Expected: PASS.

- [ ] **Step 3: Write down what it does and what it does not**

`docs/architecture.md` — the "Where the Notion mapping is lossy" table gains the import's
direction, and a short section on `notion_import_origin` and why it is not
`notion_page_id`. Mirror it into `architecture.fr.md`. `README.md`'s "Connect Notion" bullet
gains the sentence that an existing workspace is imported with its own columns and its own
words.

`docs/follow-ups.md` gains, under *Worth a decision*: origin rows whose entity was deleted
are never cleaned up; a base larger than `maxPagesPerDatabase` imports a bounded prefix and
says so only as a `+` on a count; and the relation suggestion reads one schema per kept base,
which is one Notion call per base per screen visit.

- [ ] **Step 4: Run everything**

Run: `cd apps/api && ./gradlew test && cd ../web && pnpm vitest run && pnpm tsc --noEmit && cd ../../e2e && pnpm test:e2e`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add e2e docs README.md apps
git commit -m "test(e2e): walk the five screens of the import, and say what it cost

The pass needs a workspace, so a test-only profile binds the fake client the API
tests already use — an e2e that can only assert 'no token, nothing to import'
would assert the one path nobody takes.

follow-ups.md keeps the three things left deliberately: origin rows outliving
their entity, a bounded read of a very large base, and one schema call per kept
base per visit."
```

---

## Self-Review

**Spec coverage.** Four targets → Task 1. Origins and the skipping second import → Task 2.
Server-decided write order and the four writers → Task 3. Relations from both sides, the
child winning, dependencies only where mapped → Task 4. Fallback destinations and the
conditional destination team → Task 5. Schema reading, candidates and the suggestion →
Task 6. Reading a page through a mapping → Task 7. The mapping on the wire → Task 8. The
columns and values screen, and the five-step dialog → Task 9. `listUsers`, the 403 sentence
and the correspondence service → Task 10. Assignees, leads and `people-seen` → Task 11.
Step 4 and the settings page → Task 12. E2E and the docs → Task 13. The `documents` target
is deliberately untouched beyond its rename, as the spec says.

**One gap found and closed:** the spec's `ImportPreview.linkedSources` was about bases, and
Task 4 replaces it with `linkedByRelation` and `fellBack`, which are about rows. Task 4's
interface block names both, and Task 9's step 6 has step 5 of the dialog print them.

**Types.** `ImportTarget` wire strings are `teams|projects|tickets|documents` in Kotlin
(Task 1) and TypeScript (Task 1, step 7), and `ignore` exists only in the browser.
`ImportField.wire` is camelCase (`parentTeam`, `blockedBy`, `subTeams`) in Kotlin (Task 4)
and read back by `ImportField.from` (Task 8), and the browser sends the same strings
(Task 9). `ColumnMapping.values` is keyed by field in both. `OriginKind.wire` matches the
migration's CHECK. `MappedPageReader` is constructed only through `PlannedBase.reader`.
