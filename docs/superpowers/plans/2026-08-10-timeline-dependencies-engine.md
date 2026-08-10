# Timeline dependencies — engine and API implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Kanso a scheduling engine — dates as instants, finish-to-start ticket
dependencies, a cascade that respects slack, and a critical path — plus the API the
timeline view will read, without building the view itself.

**Architecture:** The two rules worth testing (the cascade and the critical path) live
in `dev.kanso.schedule` as pure Kotlin objects with no Spring and no database, unit
tested in milliseconds. `ScheduleService` is the thin Spring bean that loads the graph
from Postgres, calls those functions, and writes the result inside the caller's
transaction. Everything else — repositories, DTOs, controllers — follows the patterns
already in the codebase.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.1, Exposed 1.4 (CRUD) with raw
`JdbcClient` SQL where Exposed cannot express the statement, Flyway (owns the schema),
Postgres 16, Testcontainers + kotlin-test-junit5. Web side: Next.js, TypeScript,
Vitest.

## Global Constraints

- **Postgres is the source of truth; Notion is an asynchronous mirror.** Never invert.
- **Flyway owns the schema.** No DDL generation from Exposed, ever.
- **Every raw SQL statement needs a written justification** — `architecture.md` lists
  the four that exist; this plan adds two, and both must be documented in the code.
- **Dependencies are finish-to-start, with no lag and no other link type.** No
  `lag_days` column, no type column.
- **The cascade never moves a ticket backwards in time**, under any circumstance.
- **A `done` ticket is never moved.** The edge is marked violated instead.
- **A date without a time is floating**: stored as an instant, never converted for
  display.
- **Only explicit project bounds are pushed to Notion.** Derived ones are never sent.
- **Tabs, not spaces**, in Kotlin files — the existing sources use tabs.
- Run API tests with `apps/api/gradlew -p apps/api test`.
- Run web checks with `pnpm --dir apps/web typecheck` and `pnpm --dir apps/web test`.

## File Structure

**Created**

| Path | Responsibility |
|---|---|
| `apps/api/src/main/resources/db/migration/V6__timeline_dates.sql` | dates become instants; `completed_at`; `preferences.timezone` |
| `apps/api/src/main/resources/db/migration/V7__ticket_dependencies.sql` | the dependency table |
| `apps/api/src/main/kotlin/dev/kanso/schedule/Graph.kt` | `Node`, `Edge`, and the shared graph helpers |
| `apps/api/src/main/kotlin/dev/kanso/schedule/Cascade.kt` | pure forward cascade |
| `apps/api/src/main/kotlin/dev/kanso/schedule/CriticalPath.kt` | pure backward pass |
| `apps/api/src/main/kotlin/dev/kanso/repo/DependencyRepository.kt` | edges, reachability, component closure |
| `apps/api/src/main/kotlin/dev/kanso/service/ScheduleService.kt` | loads the graph, applies the cascade, writes |
| `apps/api/src/main/kotlin/dev/kanso/service/TimelineService.kt` | assembles the timeline read model |
| `apps/api/src/main/kotlin/dev/kanso/api/TimelineController.kt` | `GET /api/timeline` |
| `apps/api/src/test/kotlin/dev/kanso/schedule/CascadeTest.kt` | pure, no Spring |
| `apps/api/src/test/kotlin/dev/kanso/schedule/CriticalPathTest.kt` | pure, no Spring |
| `apps/api/src/test/kotlin/dev/kanso/service/DependencyTest.kt` | cycles, endpoints, events |
| `apps/api/src/test/kotlin/dev/kanso/service/ScheduleServiceTest.kt` | cascade against the real schema |
| `apps/api/src/test/kotlin/dev/kanso/service/TimelineServiceTest.kt` | derived bounds, component closure |

**Modified**

| Path | Change |
|---|---|
| `apps/api/.../domain/Model.kt` | `KansoInstant`; `Ticket` / `Project` date fields |
| `apps/api/.../db/Tables.kt` | new columns |
| `apps/api/.../db/Mappers.kt` | read the new columns |
| `apps/api/.../repo/TicketRepository.kt` | instants, `completedAt`, timeline reads |
| `apps/api/.../repo/ProjectRepository.kt` | instants |
| `apps/api/.../service/TicketService.kt` | instants, `completed_at`, cascade hook |
| `apps/api/.../service/ProjectService.kt` | instants |
| `apps/api/.../api/Dtos.kt` | `InstantDto`, request/response changes, timeline DTOs |
| `apps/api/.../api/TicketController.kt` | dependency endpoints |
| `apps/api/.../api/ProjectController.kt` | instants |
| `apps/api/.../sync/notion/NotionSchema.kt` | `include_time`; `Blocked by` / `Blocks` |
| `apps/api/.../sync/outbound/NotionMapper.kt` | instants; explicit bounds only; dependencies |
| `apps/api/.../sync/inbound/NotionPoller.kt` | route date edits through `ScheduleService` |
| `apps/api/.../settings/PreferencesRepository.kt`, `.../domain/Model.kt` | `timezone` |
| `apps/web/src/lib/api.ts` | `KansoInstant`, ticket/project shapes |
| `apps/web/src/components/overlays.tsx`, `tickets.tsx`, `dialogs/project-dialog.tsx` | read the new shapes |

---

### Task 1: Dates become instants

One task because Kotlin does not compile halfway through a type change. The rename is
mechanical; the only judgement is in the migration.

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V6__timeline_dates.sql`
- Modify: `apps/api/src/main/kotlin/dev/kanso/domain/Model.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/db/Tables.kt:115-152`
- Modify: `apps/api/src/main/kotlin/dev/kanso/db/Mappers.kt:50-51,73-74`
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt`, `ProjectRepository.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt`, `ProjectService.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt`, `TicketController.kt`, `ProjectController.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/outbound/NotionMapper.kt:63-64,84-85`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/inbound/NotionPoller.kt:135-146,166-175,269-270`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TicketWorkflowTest.kt` and every
  other test naming `startDate` / `dueDate` / `endDate`

**Interfaces:**
- Produces: `dev.kanso.domain.KansoInstant(at: OffsetDateTime, hasTime: Boolean)`;
  `Ticket.start: KansoInstant?`, `Ticket.due: KansoInstant?`;
  `Project.start: KansoInstant?`, `Project.end: KansoInstant?`;
  wire shape `{"at": "2026-08-12T00:00:00Z", "hasTime": false}`.

- [ ] **Step 1: Write the migration**

Create `apps/api/src/main/resources/db/migration/V6__timeline_dates.sql`:

```sql
-- Dates become instants with an explicit granularity.
--
-- A value with has_time = false is *floating*: stored as an instant so the
-- scheduler can do arithmetic on it, but never converted for display. "Ends on
-- the 12th" has to read as the 12th for a reader five hours behind, on data they
-- did not touch. A value with has_time = true names a moment and is converted.

ALTER TABLE tickets
  ADD COLUMN start_at       TIMESTAMPTZ,
  ADD COLUMN start_has_time BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN due_at         TIMESTAMPTZ,
  ADD COLUMN due_has_time   BOOLEAN NOT NULL DEFAULT FALSE,
  -- When the ticket entered `done`. `updated_at` cannot answer this: it moves on
  -- every edit, so renaming a ticket would change the start date of its project.
  ADD COLUMN completed_at   TIMESTAMPTZ;

UPDATE tickets SET
  start_at = start_date::timestamptz,
  due_at   = due_date::timestamptz;

ALTER TABLE tickets DROP COLUMN start_date, DROP COLUMN due_date;

ALTER TABLE projects
  ADD COLUMN start_at       TIMESTAMPTZ,
  ADD COLUMN start_has_time BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN end_at         TIMESTAMPTZ,
  ADD COLUMN end_has_time   BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE projects SET
  start_at = start_date::timestamptz,
  end_at   = end_date::timestamptz;

ALTER TABLE projects DROP COLUMN start_date, DROP COLUMN end_date;

-- The reader's timezone, not the instance's: every instant is stored in UTC and
-- rendered per person. Seeded from the browser on first load rather than detected
-- per render, which would make every bar jump when Kanso is opened abroad.
ALTER TABLE user_preferences
  ADD COLUMN timezone TEXT NOT NULL DEFAULT 'UTC';
```

- [ ] **Step 2: Run the existing suite to watch it fail**

Run: `apps/api/gradlew -p apps/api test`
Expected: FAIL — Flyway applies V6, then every mapper reading `start_date` errors with
`column "start_date" does not exist`.

- [ ] **Step 3: Add `KansoInstant` and change the domain**

In `apps/api/src/main/kotlin/dev/kanso/domain/Model.kt`, add above `Team`:

```kotlin
/**
 * An instant with an explicit granularity.
 *
 * [hasTime] false means the value names a *day*, not a moment: it is stored as an
 * instant so the scheduler can subtract it, and rendered without conversion so it
 * reads as the same day for every reader. True means it names a moment and is
 * converted to the reader's timezone.
 */
data class KansoInstant(val at: OffsetDateTime, val hasTime: Boolean)
```

Replace the date fields:

```kotlin
data class Project(
	val id: UUID,
	val name: String,
	val status: ProjectStatus,
	val start: KansoInstant?,
	val end: KansoInstant?,
	val leadUserId: UUID?,
	val teamId: UUID?,
	val archived: Boolean,
	val mirror: MirrorInfo,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)

data class Ticket(
	val id: UUID,
	val number: Int,
	val teamId: UUID,
	val title: String,
	val description: String?,
	val status: TicketStatus,
	val priority: TicketPriority,
	val start: KansoInstant?,
	val due: KansoInstant?,
	val completedAt: OffsetDateTime?,
	val projectId: UUID?,
	val archived: Boolean,
	val mirror: MirrorInfo,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)
```

Delete the now-unused `import java.time.LocalDate` if nothing else in the file uses it.

- [ ] **Step 4: Change the Exposed tables**

In `apps/api/src/main/kotlin/dev/kanso/db/Tables.kt`, in `Projects` replace
`startDate` / `endDate`:

```kotlin
	val startAt = timestampWithTimeZone("start_at").nullable()
	val startHasTime = bool("start_has_time")
	val endAt = timestampWithTimeZone("end_at").nullable()
	val endHasTime = bool("end_has_time")
```

In `Tickets` replace `startDate` / `dueDate`:

```kotlin
	val startAt = timestampWithTimeZone("start_at").nullable()
	val startHasTime = bool("start_has_time")
	val dueAt = timestampWithTimeZone("due_at").nullable()
	val dueHasTime = bool("due_has_time")
	val completedAt = timestampWithTimeZone("completed_at").nullable()
```

In `UserPreferences` add `val timezone = text("timezone")`.

Remove the now-unused `import org.jetbrains.exposed.v1.javatime.date` if no table
still calls `date(...)`.

- [ ] **Step 5: Change the mappers**

In `apps/api/src/main/kotlin/dev/kanso/db/Mappers.kt`, add a private helper and use it.
Place the helper at the top of the file, after the imports:

```kotlin
/** Null unless the instant itself is present — a granularity flag alone means nothing. */
private fun instant(at: OffsetDateTime?, hasTime: Boolean): KansoInstant? =
	at?.let { KansoInstant(it, hasTime) }
```

In `toProject`, replace the two date lines with:

```kotlin
	start = instant(this[Projects.startAt], this[Projects.startHasTime]),
	end = instant(this[Projects.endAt], this[Projects.endHasTime]),
```

In `toTicket`, replace the two date lines with:

```kotlin
	start = instant(this[Tickets.startAt], this[Tickets.startHasTime]),
	due = instant(this[Tickets.dueAt], this[Tickets.dueHasTime]),
	completedAt = this[Tickets.completedAt],
```

Add `import dev.kanso.domain.KansoInstant` and `import java.time.OffsetDateTime` if
absent; drop `java.time.LocalDate` if now unused.

- [ ] **Step 6: Change the repositories**

In `TicketRepository.insert` and `TicketRepository.update`, replace the
`startDate: LocalDate?, dueDate: LocalDate?` parameters with
`start: KansoInstant?, due: KansoInstant?` and the column writes with:

```kotlin
			it[Tickets.startAt] = start?.at
			it[Tickets.startHasTime] = start?.hasTime ?: false
			it[Tickets.dueAt] = due?.at
			it[Tickets.dueHasTime] = due?.hasTime ?: false
```

`insert` additionally writes `it[Tickets.completedAt] = null`. Drop
`import java.time.LocalDate`, add `import dev.kanso.domain.KansoInstant`.

Apply the same change to `ProjectRepository.insert` / `update`, with
`start: KansoInstant?, end: KansoInstant?` and `Projects.startAt` / `endAt`.

- [ ] **Step 7: Change the services**

In `TicketService`, rename the `TicketPatch` fields and the `create` parameters:

```kotlin
data class TicketPatch(
	val title: String? = null,
	val description: String? = null,
	val status: TicketStatus? = null,
	val priority: TicketPriority? = null,
	val start: KansoInstant? = null,
	val due: KansoInstant? = null,
	val projectId: UUID? = null,
	val teamId: UUID? = null,
	val archived: Boolean? = null,
	val assigneeIds: List<UUID>? = null,
	val docIds: List<UUID>? = null,
	val unset: Set<String> = emptySet(),
)
```

In `patch`, replace the two resolution lines and the validation call:

```kotlin
		val start = if ("start" in patch.unset) null else patch.start ?: current.start
		val due = if ("due" in patch.unset) null else patch.due ?: current.due
		validateDates(start, due)
```

Replace `validateDates`:

```kotlin
	private fun validateDates(start: KansoInstant?, due: KansoInstant?) {
		if (start != null && due != null && due.at.isBefore(start.at)) {
			throw BadRequestException("due ${due.at} is before start ${start.at}")
		}
	}
```

Apply the equivalent rename in `ProjectService` (`start` / `end`).

- [ ] **Step 8: Change the DTOs and controllers**

In `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt`, add near `MirrorDto`:

```kotlin
/**
 * A date on the wire. `hasTime` false means the value names a day: the client renders
 * it without timezone conversion, so it reads identically everywhere.
 */
data class InstantDto(val at: OffsetDateTime, val hasTime: Boolean = false) {
	fun toDomain() = KansoInstant(at, hasTime)

	companion object {
		fun of(instant: KansoInstant?) = instant?.let { InstantDto(it.at, it.hasTime) }
	}
}
```

Add `import dev.kanso.domain.KansoInstant`. In `ProjectRequest` / `ProjectResponse`
replace `startDate` / `endDate: LocalDate?` with `start` / `end: InstantDto?`, using
`InstantDto.of(p.start)` in `of`. In `TicketCreateRequest`, `TicketPatchRequest` and
`TicketResponse` replace `startDate` / `dueDate` with `start` / `due: InstantDto?`.
Update `TicketPatchRequest.CLEARABLE`:

```kotlin
		val CLEARABLE = setOf("description", "start", "due", "projectId")
```

Drop `import java.time.LocalDate` if now unused. In `TicketController` and
`ProjectController`, pass `request.start?.toDomain()` and `request.due?.toDomain()`
(respectively `request.end?.toDomain()`) where the date arguments were.

- [ ] **Step 9: Change the Notion mapper and poller mechanically**

In `NotionSchema.NotionProps`, replace `date`:

```kotlin
	/**
	 * Notion's date value. A day-granularity instant is written as a bare date so the
	 * mirror shows a day rather than a midnight, matching how Kanso renders it.
	 */
	fun date(instant: KansoInstant?): Map<String, Any?> = mapOf(
		"date" to instant?.let {
			mapOf("start" to if (it.hasTime) it.at.toString() else it.at.toLocalDate().toString())
		}
	)
```

Replace `import java.time.LocalDate` with `import dev.kanso.domain.KansoInstant`.

In `NotionMapper`, change the four `put` calls to pass `project.start`, `project.end`,
`ticket.start`, `ticket.due`.

In `NotionPoller`, replace the `date(...)` helper:

```kotlin
	/**
	 * Notion writes `2026-08-12` for a day and a full ISO instant for a moment; the
	 * length of the string is what tells them apart, and it decides `hasTime`.
	 */
	private fun instant(props: JsonNode?, name: String): KansoInstant? {
		val raw = props?.path(name)?.path("date")?.path("start")?.asText(null) ?: return null
		return if (raw.length <= 10) KansoInstant(LocalDate.parse(raw).atStartOfDay().atOffset(ZoneOffset.UTC), false)
		else KansoInstant(OffsetDateTime.parse(raw), true)
	}
```

Update the four call sites to `instant(props, NotionProps.START) ?: ticket.start` and
so on. Add `import java.time.ZoneOffset` and `import dev.kanso.domain.KansoInstant`.

- [ ] **Step 10: Update the existing tests**

In `TicketWorkflowTest`, `DispositionCountsTest`, `TicketProjectCoherenceTest`,
`ProjectDispositionTest`, `TeamArchiveTest`, `TeamDeleteTest`: replace every
`startDate = null` / `dueDate = null` with `start = null` / `due = null`, and every
literal such as `LocalDate.of(2026, 9, 1)` with:

```kotlin
KansoInstant(LocalDate.of(2026, 9, 1).atStartOfDay().atOffset(ZoneOffset.UTC), false)
```

In `TicketWorkflowTest`, `a due date before the start date is refused` asserts on the
message; change the expected substring from `"before startDate"` to `"before start"`.

Find them all with:

```bash
grep -rln "startDate\|dueDate\|endDate" apps/api/src/test
```

- [ ] **Step 11: Run the suite**

Run: `apps/api/gradlew -p apps/api test`
Expected: PASS — every existing test green against the new schema.

- [ ] **Step 12: Commit**

```bash
git add apps/api
git commit -m "feat(api): dates become instants carrying their own granularity"
```

---

### Task 2: The web reads the new shapes

**Files:**
- Modify: `apps/web/src/lib/api.ts`
- Modify: `apps/web/src/components/overlays.tsx`
- Modify: `apps/web/src/components/tickets.tsx`
- Modify: `apps/web/src/components/dialogs/project-dialog.tsx`

**Interfaces:**
- Consumes: the wire shape from Task 1.
- Produces: `KansoInstant` and `dayValue` / `fromDayValue` in `apps/web/src/lib/api.ts`.

- [ ] **Step 1: Find every reader**

```bash
grep -rn "startDate\|dueDate\|endDate" apps/web/src apps/e2e 2>/dev/null || \
grep -rn "startDate\|dueDate\|endDate" apps/web/src e2e
```

Note every hit — the following steps must leave none behind.

- [ ] **Step 2: Change the types**

In `apps/web/src/lib/api.ts`, add:

```ts
/**
 * A date from the API. `hasTime: false` means the value names a day: render it
 * without timezone conversion, or a reader west of UTC sees the previous day.
 */
export type KansoInstant = { at: string; hasTime: boolean };

/** The `YYYY-MM-DD` an `<input type="date">` wants, taken without converting. */
export const dayValue = (instant: KansoInstant | null | undefined): string =>
  instant ? instant.at.slice(0, 10) : "";

/** The inverse: a date input's value as a floating instant. */
export const fromDayValue = (value: string): KansoInstant | null =>
  value ? { at: `${value}T00:00:00Z`, hasTime: false } : null;
```

Replace `startDate: string | null` / `dueDate: string | null` on `Ticket` with
`start: KansoInstant | null` and `due: KansoInstant | null`, and the `Project`
equivalents with `start` / `end`.

- [ ] **Step 3: Change the three components**

Every read of `ticket.dueDate` becomes `dayValue(ticket.due)`; every write of a date
input becomes `fromDayValue(event.target.value)`. In `project-dialog.tsx` the same for
`start` / `end`. Any `unset: ["dueDate"]` becomes `unset: ["due"]`.

- [ ] **Step 4: Typecheck and test**

Run: `pnpm --dir apps/web typecheck && pnpm --dir apps/web test`
Expected: PASS, no `startDate` / `dueDate` / `endDate` left in `apps/web/src`.

- [ ] **Step 5: Commit**

```bash
git add apps/web
git commit -m "refactor(web): dates arrive as instants, and days are never converted"
```

---

### Task 3: `completed_at` follows the status

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TicketWorkflowTest.kt`

**Interfaces:**
- Produces: `TicketRepository.update(..., completedAt: OffsetDateTime?)` — the caller
  decides the value; the repository only writes it.

- [ ] **Step 1: Write the failing test**

Append to `TicketWorkflowTest`:

```kotlin
	@Test
	fun `completedAt is stamped on the way into done and cleared on the way out`() {
		val team = newTeam()
		val created = tickets.create(
			teamId = team.id,
			title = "Finish me",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)
		assertNull(created.ticket.completedAt, "a new ticket has not been completed")

		val done = tickets.patch(created.ticket.id, TicketPatch(status = TicketStatus.DONE))
		assertNotNull(done.ticket.completedAt, "entering done records when it happened")

		val renamed = tickets.patch(created.ticket.id, TicketPatch(title = "Still done"))
		assertEquals(
			done.ticket.completedAt,
			renamed.ticket.completedAt,
			"an unrelated edit must not move the completion date — that is why updatedAt cannot serve",
		)

		val reopened = tickets.patch(created.ticket.id, TicketPatch(status = TicketStatus.IN_PROGRESS))
		assertNull(reopened.ticket.completedAt, "leaving done clears it")
	}
```

Add `import kotlin.test.assertNotNull`.

- [ ] **Step 2: Run it and watch it fail**

Run: `apps/api/gradlew -p apps/api test --tests '*TicketWorkflowTest*'`
Expected: FAIL — `completedAt` stays null after the status change.

- [ ] **Step 3: Write it**

In `TicketRepository.update`, add a `completedAt: OffsetDateTime?` parameter after
`due` and write `it[Tickets.completedAt] = completedAt`.

In `TicketService.patch`, before the `tickets.update(...)` call:

```kotlin
		// Written here rather than in a trigger: the rule belongs next to the status
		// logic that owns it, and a trigger would be the only part of the transition
		// invisible from this file.
		val status = patch.status ?: current.status
		val completedAt = when {
			status == TicketStatus.DONE && current.status != TicketStatus.DONE -> OffsetDateTime.now()
			status != TicketStatus.DONE -> null
			else -> current.completedAt
		}
```

Pass `status = status` and `completedAt = completedAt` to `tickets.update`, replacing
the inline `patch.status ?: current.status`. Add `import java.time.OffsetDateTime`.

- [ ] **Step 4: Run it**

Run: `apps/api/gradlew -p apps/api test --tests '*TicketWorkflowTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add apps/api
git commit -m "feat(api): a ticket records when it entered done"
```

---

### Task 4: The dependency table and its cycle guard

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V7__ticket_dependencies.sql`
- Create: `apps/api/src/main/kotlin/dev/kanso/repo/DependencyRepository.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/db/Tables.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/service/DependencyTest.kt`

**Interfaces:**
- Produces: `dev.kanso.schedule.Edge(predecessorId: UUID, successorId: UUID)` and
  `DependencyRepository` with `insert`, `delete`, `wouldCreateCycle`,
  `edgesTouching(ticketIds)`, `componentIds(ticketIds)`, `pathBetween`.

- [ ] **Step 1: Write the migration**

Create `apps/api/src/main/resources/db/migration/V7__ticket_dependencies.sql`:

```sql
-- Finish-to-start dependencies between tickets. Two columns and nothing else:
-- no lag, no link type. Both are cheap, non-destructive additions the day one is
-- actually asked for, and neither has been.
--
-- Dependencies deliberately cross teams. The neighbouring rule is the opposite —
-- a ticket's project must belong to its team — because a project is a container
-- and a dependency is not.
CREATE TABLE ticket_dependencies (
  predecessor_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  successor_id   UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (predecessor_id, successor_id),
  CHECK (predecessor_id <> successor_id)
);

-- The primary key already indexes (predecessor_id, successor_id); walking the
-- graph backwards needs the other direction.
CREATE INDEX ticket_dependencies_successor_idx ON ticket_dependencies (successor_id);
```

- [ ] **Step 2: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/service/DependencyTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Transactional
class DependencyTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var deps: DependencyRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "deps-${UUID.randomUUID()}@kanso.test",
			displayName = "Deps admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Deps", "D${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(title: String): UUID = tickets.create(
		teamId = team.id,
		title = title,
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	@Test
	fun `a chain that would close on itself is refused`() {
		val a = ticket("A")
		val b = ticket("B")
		val c = ticket("C")
		deps.insert(a, b)
		deps.insert(b, c)

		assertTrue(deps.wouldCreateCycle(c, a), "C -> A closes A -> B -> C")
		assertFalse(deps.wouldCreateCycle(a, c), "A -> C is a shortcut, not a cycle")
	}

	@Test
	fun `the offending chain is named, not merely detected`() {
		val a = ticket("A")
		val b = ticket("B")
		val c = ticket("C")
		deps.insert(a, b)
		deps.insert(b, c)

		assertEquals(
			listOf(a, b, c),
			deps.pathBetween(a, c),
			"a bare 'cycle detected' on a forty-ticket graph is unusable",
		)
	}

	@Test
	fun `the component closure crosses the direction of the arrows`() {
		val a = ticket("A")
		val b = ticket("B")
		val c = ticket("C")
		val loner = ticket("Loner")
		deps.insert(a, b)
		deps.insert(c, b)

		assertEquals(
			setOf(a, b, c),
			deps.componentIds(listOf(a)).toSet(),
			"reaching C from A means walking one arrow backwards",
		)
		assertEquals(setOf(loner), deps.componentIds(listOf(loner)).toSet())
	}
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `apps/api/gradlew -p apps/api test --tests '*DependencyTest*'`
Expected: FAIL — `DependencyRepository` does not exist.

- [ ] **Step 4: Declare the table to Exposed**

In `apps/api/src/main/kotlin/dev/kanso/db/Tables.kt`, after `TicketAssignees`:

```kotlin
object TicketDependencies : Table("ticket_dependencies") {
	val predecessorId = javaUUID("predecessor_id")
	val successorId = javaUUID("successor_id")
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(predecessorId, successorId)
}
```

- [ ] **Step 5: Write the repository**

Create `apps/api/src/main/kotlin/dev/kanso/repo/DependencyRepository.kt`:

```kotlin
package dev.kanso.repo

import dev.kanso.db.TicketDependencies
import dev.kanso.schedule.Edge
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class DependencyRepository(private val jdbc: JdbcClient) {

	fun insert(predecessorId: UUID, successorId: UUID) {
		TicketDependencies.insert {
			it[TicketDependencies.predecessorId] = predecessorId
			it[TicketDependencies.successorId] = successorId
			it[createdAt] = OffsetDateTime.now()
		}
	}

	fun delete(predecessorId: UUID, successorId: UUID): Boolean =
		TicketDependencies.deleteWhere {
			(TicketDependencies.predecessorId eq predecessorId) and
				(TicketDependencies.successorId eq successorId)
		} > 0

	fun exists(predecessorId: UUID, successorId: UUID): Boolean =
		TicketDependencies.selectAll().where {
			(TicketDependencies.predecessorId eq predecessorId) and
				(TicketDependencies.successorId eq successorId)
		}.limit(1).any()

	/** Every edge with at least one end inside [ticketIds]. */
	fun edgesTouching(ticketIds: Collection<UUID>): List<Edge> {
		if (ticketIds.isEmpty()) return emptyList()
		return TicketDependencies.selectAll().where {
			(TicketDependencies.predecessorId inList ticketIds) or
				(TicketDependencies.successorId inList ticketIds)
		}.map { Edge(it[TicketDependencies.predecessorId], it[TicketDependencies.successorId]) }
	}

	/**
	 * The weakly connected components containing [ticketIds] — the walk ignores the
	 * direction of the arrows, which is why the recursive term flips the edge.
	 *
	 * Raw SQL for the reason the other recursive walks are: Exposed has no
	 * `WITH RECURSIVE`. `UNION` rather than `UNION ALL` is load-bearing — an
	 * undirected walk revisits every node from both ends and would not terminate.
	 *
	 * The critical path is computed over this set rather than over what the caller
	 * asked to see: anchoring on the visible scope would repaint the screen when the
	 * filter changes, on identical data.
	 */
	fun componentIds(ticketIds: Collection<UUID>): List<UUID> {
		if (ticketIds.isEmpty()) return emptyList()
		return jdbc.sql(
			"""
			WITH RECURSIVE component AS (
			    SELECT id FROM tickets WHERE id IN (:seed)
			  UNION
			    SELECT CASE WHEN d.predecessor_id = c.id THEN d.successor_id ELSE d.predecessor_id END
			      FROM ticket_dependencies d
			      JOIN component c ON d.predecessor_id = c.id OR d.successor_id = c.id
			)
			SELECT id FROM component
			""".trimIndent()
		).param("seed", ticketIds.toList()).query(UUID::class.java).list().filterNotNull()
	}

	/**
	 * True when `[predecessorId] -> [successorId]` would close a loop, i.e. when
	 * [predecessorId] is already reachable by following arrows forward from
	 * [successorId].
	 */
	fun wouldCreateCycle(predecessorId: UUID, successorId: UUID): Boolean =
		predecessorId == successorId || pathBetween(successorId, predecessorId) != null

	/**
	 * The first path found from [fromId] to [toId], both ends included, or null when
	 * there is none. Used to name the chain in the 409 a refused dependency produces:
	 * "cycle detected" on its own is not something anyone can act on.
	 */
	fun pathBetween(fromId: UUID, toId: UUID): List<UUID>? {
		val path = jdbc.sql(
			"""
			WITH RECURSIVE walk AS (
			    SELECT :from::uuid AS id, ARRAY[:from::uuid] AS path
			  UNION ALL
			    SELECT d.successor_id, w.path || d.successor_id
			      FROM ticket_dependencies d
			      JOIN walk w ON d.predecessor_id = w.id
			     WHERE NOT d.successor_id = ANY(w.path)
			)
			SELECT path FROM walk WHERE id = :to::uuid LIMIT 1
			""".trimIndent()
		).param("from", fromId).param("to", toId)
			.query(java.sql.Array::class.java).optional().orElse(null) ?: return null
		@Suppress("UNCHECKED_CAST")
		return (path.array as Array<UUID>).toList()
	}
}
```

- [ ] **Step 6: Run the test**

Run: `apps/api/gradlew -p apps/api test --tests '*DependencyTest*'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add apps/api
git commit -m "feat(api): ticket dependencies, acyclic and named when refused"
```

---

### Task 5: The cascade, pure

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/schedule/Graph.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/schedule/Cascade.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/schedule/CascadeTest.kt`

**Interfaces:**
- Produces: `Node(id, start, end, done)`, `Edge(predecessorId, successorId)`,
  `Placement(id, start, end)`, `CascadeResult(moved: List<Placement>, violated: Set<Edge>)`,
  `Cascade.apply(nodes, edges, changedId): CascadeResult`.

No Spring, no database: the two rules worth testing are arithmetic, and they should run
in milliseconds.

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/schedule/CascadeTest.kt`:

```kotlin
package dev.kanso.schedule

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CascadeTest {

	private fun day(d: Int): OffsetDateTime =
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC)

	private val a = UUID.randomUUID()
	private val b = UUID.randomUUID()
	private val c = UUID.randomUUID()

	@Test
	fun `slack absorbs the move and nothing shifts`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, day(15), day(20), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		assertEquals(emptyList(), result.moved, "B keeps five days of slack, so B keeps its dates")
	}

	@Test
	fun `an overlap pushes the successor by exactly the overlap and keeps its duration`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, day(15), day(20), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		val moved = result.moved.single()
		assertEquals(b, moved.id)
		assertEquals(day(18), moved.start, "B starts when A ends, not a day later")
		assertEquals(day(23), moved.end, "five days long before, five days long after")
	}

	@Test
	fun `the push carries down the chain and stops where slack absorbs it`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, day(15), day(20), done = false),
			Node(c, day(28), day(30), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b), Edge(b, c)), changedId = a)

		assertEquals(listOf(b), result.moved.map { it.id }, "B ends on the 23rd, C starts on the 28th")
	}

	@Test
	fun `a done successor is never moved and its edge is reported violated`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, day(15), day(20), done = true),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		assertEquals(emptyList(), result.moved, "finished work does not get rewritten")
		assertEquals(setOf(Edge(a, b)), result.violated, "and the plan must say so rather than pretend")
	}

	@Test
	fun `nothing is ever pulled backwards`() {
		val nodes = listOf(
			Node(a, day(1), day(3), done = false),
			Node(b, day(20), day(25), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		assertEquals(emptyList(), result.moved, "freeing slack is not a reason to drag work into the past")
	}

	@Test
	fun `an unscheduled successor stops the descent`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, null, null, done = false),
			Node(c, day(2), day(4), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b), Edge(b, c)), changedId = a)

		assertEquals(emptyList(), result.moved, "an undated ticket has nothing to move, and blocks nothing")
	}

	@Test
	fun `a ticket with one bound is a milestone and keeps its zero duration`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, null, day(15), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		val moved = result.moved.single()
		assertEquals(day(18), moved.start)
		assertEquals(day(18), moved.end, "a milestone has no length to preserve")
	}

	@Test
	fun `a node with two predecessors settles once, against the later one`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, day(1), day(22), done = false),
			Node(c, day(5), day(6), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, c), Edge(b, c)), changedId = a)

		val moved = result.moved.single { it.id == c }
		assertEquals(day(22), moved.start, "the binding constraint is the later predecessor")
		assertTrue(result.moved.count { it.id == c } == 1, "and C is written once, not twice")
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps/api/gradlew -p apps/api test --tests '*CascadeTest*'`
Expected: FAIL — `Node`, `Edge` and `Cascade` do not exist.

- [ ] **Step 3: Write the graph types**

Create `apps/api/src/main/kotlin/dev/kanso/schedule/Graph.kt`:

```kotlin
package dev.kanso.schedule

import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** One finish-to-start dependency: [successorId] cannot start before [predecessorId] ends. */
data class Edge(val predecessorId: UUID, val successorId: UUID)

/**
 * One ticket as the scheduler sees it.
 *
 * A node with both bounds null is unscheduled: it takes part in the graph but has
 * nothing to move and constrains nothing. A node with one bound is a milestone of zero
 * duration sitting on the bound it has — including the shape a deadline naturally
 * takes, a due date with no start.
 */
data class Node(
	val id: UUID,
	val start: OffsetDateTime?,
	val end: OffsetDateTime?,
	val done: Boolean,
) {
	val from: OffsetDateTime? get() = start ?: end
	val to: OffsetDateTime? get() = end ?: start
	val scheduled: Boolean get() = from != null

	val duration: Duration
		get() = if (from == null || to == null) Duration.ZERO else Duration.between(from, to)
}

/** Where a node ended up. */
data class Placement(val id: UUID, val start: OffsetDateTime, val end: OffsetDateTime)

/**
 * Successors first, in an order where every node appears after all of its predecessors
 * that are themselves in [ids]. Cycles are impossible — `DependencyRepository`
 * refuses them at insert — so this always consumes the whole set.
 */
internal fun topologicalOrder(ids: Set<UUID>, edges: Collection<Edge>): List<UUID> {
	val inner = edges.filter { it.predecessorId in ids && it.successorId in ids }
	val remaining = inner.groupingBy { it.successorId }.eachCount().toMutableMap()
	val bySource = inner.groupBy { it.predecessorId }
	val ready = ArrayDeque(ids.filter { remaining.getOrDefault(it, 0) == 0 })
	val order = mutableListOf<UUID>()
	while (ready.isNotEmpty()) {
		val id = ready.removeFirst()
		order += id
		for (edge in bySource[id].orEmpty()) {
			val left = remaining.getValue(edge.successorId) - 1
			remaining[edge.successorId] = left
			if (left == 0) ready += edge.successorId
		}
	}
	check(order.size == ids.size) { "the dependency graph has a cycle, which the insert guard should have refused" }
	return order
}

/** Everything reachable from [rootId] by following arrows forward, [rootId] included. */
internal fun reachableFrom(rootId: UUID, edges: Collection<Edge>): Set<UUID> {
	val bySource = edges.groupBy { it.predecessorId }
	val seen = mutableSetOf(rootId)
	val queue = ArrayDeque(listOf(rootId))
	while (queue.isNotEmpty()) {
		for (edge in bySource[queue.removeFirst()].orEmpty()) {
			if (seen.add(edge.successorId)) queue += edge.successorId
		}
	}
	return seen
}
```

- [ ] **Step 4: Write the cascade**

Create `apps/api/src/main/kotlin/dev/kanso/schedule/Cascade.kt`:

```kotlin
package dev.kanso.schedule

import java.util.UUID

data class CascadeResult(val moved: List<Placement>, val violated: Set<Edge>)

/**
 * The forward pass: a change to one ticket pushes the successors it now overlaps.
 *
 * Three rules, and each one is a product decision rather than an optimisation:
 *
 * - **Slack is respected.** A successor that still starts after its predecessor ends
 *   does not move, and the descent stops there — a node that did not move cannot have
 *   pushed anything behind it. Without this, every micro-adjustment would creep the
 *   whole graph forward and no ticket would ever have slack, which would make the
 *   critical path meaningless.
 * - **Nothing is ever pulled backwards.** Freeing slack does not drag work into the
 *   past. Nobody expects it and nobody could undo it.
 * - **A done ticket never moves.** Its edge is reported violated instead: a plan that
 *   claims to hold when it does not is the worst outcome available here.
 */
object Cascade {

	fun apply(nodes: Collection<Node>, edges: Collection<Edge>, changedId: UUID): CascadeResult {
		val current = nodes.associateBy { it.id }.toMutableMap()
		if (changedId !in current) return CascadeResult(emptyList(), emptySet())

		// Only what sits downstream of the change is settled. Repairing a violation
		// elsewhere in the component would move tickets the person did not touch, in a
		// request they did not make.
		val downstream = reachableFrom(changedId, edges) - changedId
		val byTarget = edges.groupBy { it.successorId }
		val moved = LinkedHashMap<UUID, Placement>()
		val violated = mutableSetOf<Edge>()

		// Topological order over the whole graph, so a node is settled only once every
		// predecessor of it has been. A diamond — two chains meeting at one ticket — is
		// exactly the case that breaks if the order is the one the edges were stored in.
		for (id in topologicalOrder(current.keys, edges)) {
			if (id !in downstream) continue
			val node = current.getValue(id)
			val startsAt = node.from ?: continue

			// Every predecessor, not just the ones the change reached: the binding
			// constraint may come from a chain that did not move at all.
			val binding = byTarget[id].orEmpty()
				.mapNotNull { edge -> current[edge.predecessorId]?.to?.let { edge to it } }
				.maxByOrNull { (_, end) -> end }
				?: continue
			val (edge, requiredStart) = binding
			if (!startsAt.isBefore(requiredStart)) continue

			if (node.done) {
				violated += edge
				continue
			}

			val shifted = node.copy(start = requiredStart, end = requiredStart.plus(node.duration))
			current[id] = shifted
			moved[id] = Placement(id, requiredStart, requiredStart.plus(node.duration))
		}

		return CascadeResult(moved.values.toList(), violated)
	}
}
```

- [ ] **Step 5: Run the test**

Run: `apps/api/gradlew -p apps/api test --tests '*CascadeTest*'`
Expected: PASS — all eight cases.

- [ ] **Step 6: Commit**

```bash
git add apps/api
git commit -m "feat(api): the forward cascade, as arithmetic with no database under it"
```

---

### Task 6: The critical path, pure

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/schedule/CriticalPath.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/schedule/CriticalPathTest.kt`

**Interfaces:**
- Consumes: `Node`, `Edge`, `topologicalOrder` from Task 5.
- Produces: `CriticalPath.slack(nodes, edges, deadlines: Map<UUID, OffsetDateTime>): Map<UUID, Duration>`
  — absent from the map means "no slack is defined for this ticket".

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/schedule/CriticalPathTest.kt`:

```kotlin
package dev.kanso.schedule

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CriticalPathTest {

	private fun day(d: Int): OffsetDateTime =
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC)

	private val a = UUID.randomUUID()
	private val b = UUID.randomUUID()
	private val c = UUID.randomUUID()
	private val loner = UUID.randomUUID()

	@Test
	fun `the longest chain has zero slack and the shorter branch has some`() {
		// A -> C and B -> C. A ends on the 10th, B on the 4th, C runs 10th to 12th.
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, day(1), day(4), done = false),
			Node(c, day(10), day(12), done = false),
		)

		val slack = CriticalPath.slack(nodes, listOf(Edge(a, c), Edge(b, c)), emptyMap())

		assertEquals(Duration.ZERO, slack[a], "A binds C, so A is on the critical path")
		assertEquals(Duration.ZERO, slack[c], "and C ends the chain")
		assertEquals(Duration.ofDays(6), slack[b], "B could run six days later without moving C")
	}

	@Test
	fun `a ticket with no dependencies has no slack at all`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(loner, day(1), day(30), done = false),
		)

		val slack = CriticalPath.slack(nodes, listOf(Edge(a, loner)).drop(1), emptyMap())

		assertFalse(loner in slack, "painting a ticket red says something about dependencies it does not have")
	}

	@Test
	fun `an explicit project deadline tightens the anchor and can make slack negative`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, day(10), day(20), done = false),
		)

		val onTime = CriticalPath.slack(nodes, listOf(Edge(a, b)), emptyMap())
		assertEquals(Duration.ZERO, onTime[b], "with no deadline the anchor is the chain's own end")

		val late = CriticalPath.slack(nodes, listOf(Edge(a, b)), mapOf(b to day(15)))
		assertTrue(late.getValue(b).isNegative, "the chain overruns the deadline by five days")
		assertEquals(Duration.ofDays(-5), late.getValue(b))
	}

	@Test
	fun `the tightest deadline in the component wins, even across projects`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, day(10), day(20), done = false),
		)

		val slack = CriticalPath.slack(nodes, listOf(Edge(a, b)), mapOf(a to day(12), b to day(30)))

		assertEquals(
			Duration.ofDays(-8),
			slack.getValue(b),
			"a chain crossing two projects takes the tighter of the two ends",
		)
	}

	@Test
	fun `unscheduled tickets take no part in the computation`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, null, null, done = false),
		)

		val slack = CriticalPath.slack(nodes, listOf(Edge(a, b)), emptyMap())

		assertFalse(b in slack, "a ticket with no dates has no slack to report")
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps/api/gradlew -p apps/api test --tests '*CriticalPathTest*'`
Expected: FAIL — `CriticalPath` does not exist.

- [ ] **Step 3: Write it**

Create `apps/api/src/main/kotlin/dev/kanso/schedule/CriticalPath.kt`:

```kotlin
package dev.kanso.schedule

import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The backward pass. Slack is how long a ticket could be delayed without pushing the
 * end of its chain; zero slack is the critical path, negative slack is a chain that
 * overruns a deadline someone posed.
 *
 * The unit is the **weakly connected component**, not the project and not the visible
 * scope. A chain can span three projects: anchoring per project would cut it into
 * three unrelated critical paths, and anchoring on what happens to be on screen would
 * repaint identical data when the filter changes.
 *
 * A component of one ticket is excluded. So is an unscheduled one. Both would be
 * saying something about dependencies they do not have.
 */
object CriticalPath {

	/**
	 * @param deadlines the explicit end of a ticket's project, per ticket id. Absent
	 *        means the project has no posed end, or the ticket has no project — a
	 *        derived bound is not a deadline, it is a consequence.
	 */
	fun slack(
		nodes: Collection<Node>,
		edges: Collection<Edge>,
		deadlines: Map<UUID, OffsetDateTime>,
	): Map<UUID, Duration> {
		val byId = nodes.associateBy { it.id }
		val usable = edges.filter { byId[it.predecessorId]?.scheduled == true && byId[it.successorId]?.scheduled == true }
		if (usable.isEmpty()) return emptyMap()

		val result = mutableMapOf<UUID, Duration>()
		for (component in components(usable)) {
			val members = component.mapNotNull { byId[it] }.filter { it.scheduled }
			if (members.size < 2) continue

			val chainEnd = members.mapNotNull { it.to }.max()
			val deadline = component.mapNotNull { deadlines[it] }.minOrNull()
			val anchor = if (deadline != null && deadline.isBefore(chainEnd)) deadline else chainEnd

			val inner = usable.filter { it.predecessorId in component && it.successorId in component }
			val bySource = inner.groupBy { it.predecessorId }
			val lateFinish = mutableMapOf<UUID, OffsetDateTime>()

			// Reverse topological order: a node's late finish is bounded by its
			// successors, so every successor must already be settled.
			for (id in topologicalOrder(component, inner).asReversed()) {
				val successors = bySource[id].orEmpty()
				lateFinish[id] = successors
					.mapNotNull { edge -> byId[edge.successorId]?.let { lateFinish.getValue(it.id).minus(it.duration) } }
					.minOrNull() ?: anchor
			}

			for (node in members) {
				val end = node.to ?: continue
				result[node.id] = Duration.between(end, lateFinish.getValue(node.id))
			}
		}
		return result
	}

	/** Weakly connected components: the walk ignores the direction of the arrows. */
	private fun components(edges: Collection<Edge>): List<Set<UUID>> {
		val neighbours = mutableMapOf<UUID, MutableSet<UUID>>()
		for (edge in edges) {
			neighbours.getOrPut(edge.predecessorId) { mutableSetOf() } += edge.successorId
			neighbours.getOrPut(edge.successorId) { mutableSetOf() } += edge.predecessorId
		}

		val seen = mutableSetOf<UUID>()
		return neighbours.keys.mapNotNull { root ->
			if (!seen.add(root)) return@mapNotNull null
			val component = mutableSetOf(root)
			val queue = ArrayDeque(listOf(root))
			while (queue.isNotEmpty()) {
				for (next in neighbours[queue.removeFirst()].orEmpty()) {
					if (seen.add(next)) {
						component += next
						queue += next
					}
				}
			}
			component
		}
	}
}
```

- [ ] **Step 4: Run the test**

Run: `apps/api/gradlew -p apps/api test --tests '*CriticalPathTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add apps/api
git commit -m "feat(api): slack and the critical path, per component rather than per project"
```

---

### Task 7: `ScheduleService` writes what the cascade decides

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/service/ScheduleService.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/service/ScheduleServiceTest.kt`

**Interfaces:**
- Consumes: `Cascade.apply` (Task 5), `DependencyRepository.edgesTouching` /
  `componentIds` (Task 4).
- Produces: `ScheduleService.cascadeFrom(changedId: UUID): List<UUID>` — the ids it
  moved, in the order it wrote them; and `TicketRepository.reschedule(id, start, end)`.

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/service/ScheduleServiceTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@Transactional
class ScheduleServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var repo: TicketRepository
	@Autowired lateinit var deps: DependencyRepository
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "sched-${UUID.randomUUID()}@kanso.test",
			displayName = "Sched admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Sched", "S${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun day(d: Int) = KansoInstant(
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC),
		false,
	)

	private fun ticket(title: String, start: Int?, due: Int?): UUID = tickets.create(
		teamId = team.id,
		title = title,
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = start?.let(::day),
		due = due?.let(::day),
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	@Test
	fun `lengthening a predecessor slides its successor and persists the move`() {
		val a = ticket("A", 1, 10)
		val b = ticket("B", 10, 15)
		deps.insert(a, b)

		tickets.patch(a, TicketPatch(due = day(12)))

		val moved = repo.findById(b)!!
		assertEquals(day(12).at, moved.start!!.at, "B now starts when A ends")
		assertEquals(day(17).at, moved.due!!.at, "and keeps the five days it had")
	}

	@Test
	fun `a cascade queues one mirror push per ticket it actually moved`() {
		val a = ticket("A", 1, 10)
		val b = ticket("B", 10, 15)
		val untouched = ticket("Elsewhere", 1, 2)
		deps.insert(a, b)
		jobs.claimBatch(100, "drain")

		tickets.patch(a, TicketPatch(due = day(12)))

		val queued = jobs.claimBatch(100, "test").map { it.entityId }.toSet()
		assertEquals(setOf(a, b), queued, "the moved successor needs its own push; nothing else does")
		assertEquals(false, untouched in queued)
	}

	@Test
	fun `slack means an adjustment writes nothing downstream`() {
		val a = ticket("A", 1, 10)
		val b = ticket("B", 20, 25)
		deps.insert(a, b)

		tickets.patch(a, TicketPatch(due = day(12)))

		val untouched = repo.findById(b)!!
		assertEquals(day(20).at, untouched.start!!.at, "eight days of slack absorbed two days of delay")
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps/api/gradlew -p apps/api test --tests '*ScheduleServiceTest*'`
Expected: FAIL — the successor keeps its original dates; nothing cascades.

- [ ] **Step 3: Add the repository write and read**

In `TicketRepository`, add:

```kotlin
	/**
	 * Writes only the two bounds. The cascade moves dates and nothing else, so a full
	 * row update here would make it capable of clobbering a concurrent edit to a field
	 * it has no business touching.
	 */
	fun reschedule(id: UUID, start: OffsetDateTime, end: OffsetDateTime) {
		Tickets.update({ Tickets.id eq id }) {
			it[startAt] = start
			it[dueAt] = end
		}
	}

	fun findAllById(ids: Collection<UUID>): List<Ticket> =
		if (ids.isEmpty()) emptyList()
		else Tickets.selectAll().where { Tickets.id inList ids }.map { it.toTicket() }
```

- [ ] **Step 4: Write the service**

Create `apps/api/src/main/kotlin/dev/kanso/service/ScheduleService.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketStatus
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.schedule.Cascade
import dev.kanso.schedule.Node
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.SyncOperation
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The database side of the cascade: load the component, hand it to the pure rule,
 * write back what moved.
 *
 * No `@Transactional` of its own — it runs inside the caller's transaction on purpose.
 * A cascade half-applied because it committed separately from the edit that caused it
 * would be worse than no cascade at all.
 */
@Service
class ScheduleService(
	private val tickets: TicketRepository,
	private val dependencies: DependencyRepository,
	private val syncJobs: SyncJobRepository,
) {

	/**
	 * Applies the cascade started by a change to [changedId]. Returns the ids it moved,
	 * each with a mirror push already queued.
	 *
	 * The component closure rather than the direct successors: a diamond in the graph
	 * has a node whose two predecessors are both in the blast radius, and settling it
	 * against only one of them would leave the other violated.
	 */
	fun cascadeFrom(changedId: UUID): List<UUID> {
		val componentIds = dependencies.componentIds(listOf(changedId))
		if (componentIds.size < 2) return emptyList()

		val edges = dependencies.edgesTouching(componentIds)
		val nodes = tickets.findAllById(componentIds).map(::toNode)

		val result = Cascade.apply(nodes, edges, changedId)
		for (placement in result.moved) {
			tickets.reschedule(placement.id, placement.start, placement.end)
			syncJobs.enqueue(SyncEntityType.TICKET, placement.id, SyncOperation.UPSERT)
		}
		return result.moved.map { it.id }
	}

	private fun toNode(ticket: Ticket) = Node(
		id = ticket.id,
		start = ticket.start?.at,
		end = ticket.due?.at,
		done = ticket.status == TicketStatus.DONE,
	)
}
```

- [ ] **Step 5: Hook it into `TicketService.patch`**

Add `private val schedule: ScheduleService` to the `TicketService` constructor. At the
end of `patch`, after the existing `events.publish(...)` line and before the `return`:

```kotlin
		// One event for the whole cascade, not one per ticket: `pg_notify` caps
		// payloads at 8000 bytes and two hundred UUIDs alone come to 7200. Receivers
		// refetch, which is the doctrine every other event here already follows.
		val moved = schedule.cascadeFrom(id)
		if (moved.isNotEmpty()) {
			events.publish(KansoEvent.ticket(ChangeKind.UPDATED, id, updated.teamId, updated.projectId))
		}
```

- [ ] **Step 6: Run the test**

Run: `apps/api/gradlew -p apps/api test --tests '*ScheduleServiceTest*'`
Expected: PASS

- [ ] **Step 7: Run the whole suite**

Run: `apps/api/gradlew -p apps/api test`
Expected: PASS — nothing earlier regressed.

- [ ] **Step 8: Commit**

```bash
git add apps/api
git commit -m "feat(api): an edit cascades through the dependency graph, in one transaction"
```

---

### Task 8: Creating and removing a dependency

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/ScheduleService.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/TicketController.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/DependencyTest.kt`

**Interfaces:**
- Produces: `ScheduleService.link(predecessorId, successorId): List<UUID>` and
  `ScheduleService.unlink(predecessorId, successorId)`; `DependencyRequest(predecessorId)`.

- [ ] **Step 1: Write the failing test**

Append to `DependencyTest`:

```kotlin
	@Test
	fun `linking two tickets refuses a cycle and names the chain`() {
		val a = ticket("A")
		val b = ticket("B")
		schedule.link(a, b)

		val failure = assertFailsWith<ConflictException> { schedule.link(b, a) }
		assertTrue(failure.message!!.contains(a.toString()), failure.message!!)
		assertTrue(failure.message!!.contains(b.toString()), failure.message!!)
	}

	@Test
	fun `linking applies the cascade immediately`() {
		val a = ticket("A", start = 1, due = 10)
		val b = ticket("B", start = 5, due = 8)

		val moved = schedule.link(a, b)

		assertEquals(listOf(b), moved, "the new constraint is violated the moment it exists")
	}

	@Test
	fun `unlinking frees slack without dragging anything backwards`() {
		val a = ticket("A", start = 1, due = 10)
		val b = ticket("B", start = 10, due = 15)
		schedule.link(a, b)

		schedule.unlink(a, b)

		assertEquals(day(10).at, repo.findById(b)!!.start!!.at, "B stays where it is")
		assertFalse(deps.exists(a, b))
	}
```

Add the fields and imports the new cases need: `@Autowired lateinit var schedule: ScheduleService`,
`@Autowired lateinit var repo: TicketRepository`, `kotlin.test.assertFailsWith`. Change
the existing `ticket(title)` helper to `ticket(title: String, start: Int? = null, due: Int? = null)`
with the `day(...)` helper from `ScheduleServiceTest`.

- [ ] **Step 2: Run it and watch it fail**

Run: `apps/api/gradlew -p apps/api test --tests '*DependencyTest*'`
Expected: FAIL — `ScheduleService.link` does not exist.

- [ ] **Step 3: Write the two methods**

Add `private val tickets: TicketRepository` usage is already there; add
`private val events: EventPublisher` to the `ScheduleService` constructor, then:

```kotlin
	/**
	 * Creates a finish-to-start dependency and applies it. Returns the tickets the new
	 * constraint moved.
	 *
	 * A cycle is a 409 naming the chain that would close: "cycle detected" on its own
	 * gives nobody anything to act on.
	 */
	@Transactional
	fun link(predecessorId: UUID, successorId: UUID): List<UUID> {
		val predecessor = tickets.findById(predecessorId)
			?: throw NotFoundException("No ticket $predecessorId")
		val successor = tickets.findById(successorId)
			?: throw NotFoundException("No ticket $successorId")
		if (predecessorId == successorId) {
			throw ConflictException("A ticket cannot depend on itself")
		}
		if (dependencies.exists(predecessorId, successorId)) return emptyList()

		dependencies.pathBetween(successorId, predecessorId)?.let { chain ->
			throw ConflictException(
				"That dependency would close a loop: ${chain.joinToString(" -> ")}"
			)
		}

		dependencies.insert(predecessorId, successorId)
		syncJobs.enqueue(SyncEntityType.TICKET, predecessorId, SyncOperation.UPSERT)
		syncJobs.enqueue(SyncEntityType.TICKET, successorId, SyncOperation.UPSERT)
		events.publish(
			KansoEvent.ticket(ChangeKind.UPDATED, successorId, successor.teamId, successor.projectId)
		)
		return cascadeFrom(predecessor.id)
	}

	/**
	 * Removes a dependency. Nothing moves: freeing slack is not a reason to drag work
	 * into the past, and a schedule that reshuffles itself when an arrow is erased is
	 * doing something nobody asked for.
	 */
	@Transactional
	fun unlink(predecessorId: UUID, successorId: UUID) {
		if (!dependencies.delete(predecessorId, successorId)) {
			throw NotFoundException("No dependency $predecessorId -> $successorId")
		}
		val successor = tickets.findById(successorId)
		syncJobs.enqueue(SyncEntityType.TICKET, predecessorId, SyncOperation.UPSERT)
		syncJobs.enqueue(SyncEntityType.TICKET, successorId, SyncOperation.UPSERT)
		successor?.let {
			events.publish(KansoEvent.ticket(ChangeKind.UPDATED, it.id, it.teamId, it.projectId))
		}
	}
```

Add the imports: `dev.kanso.realtime.ChangeKind`, `dev.kanso.realtime.EventPublisher`,
`dev.kanso.realtime.KansoEvent`, `org.springframework.transaction.annotation.Transactional`.

- [ ] **Step 4: Add the endpoints**

In `Dtos.kt`, next to the ticket DTOs:

```kotlin
data class DependencyRequest(val predecessorId: UUID)

/** What a write returns: the tickets the cascade moved, so the client settles at once. */
data class CascadeResponse(val movedTicketIds: List<UUID>)
```

In `TicketController`:

```kotlin
	/**
	 * `POST /api/tickets/{id}/dependencies` — `{id}` is the successor, the body names
	 * the predecessor, which is the direction the arrow is drawn in the timeline.
	 */
	@PostMapping("/{id}/dependencies")
	@ResponseStatus(HttpStatus.CREATED)
	fun addDependency(
		@PathVariable id: UUID,
		@RequestBody request: DependencyRequest,
	): CascadeResponse = CascadeResponse(schedule.link(request.predecessorId, id))

	@DeleteMapping("/{id}/dependencies/{predecessorId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun removeDependency(@PathVariable id: UUID, @PathVariable predecessorId: UUID) {
		schedule.unlink(predecessorId, id)
	}
```

Add `private val schedule: ScheduleService` to the controller's constructor and
`import dev.kanso.service.ScheduleService`.

- [ ] **Step 5: Run the tests**

Run: `apps/api/gradlew -p apps/api test --tests '*DependencyTest*'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add apps/api
git commit -m "feat(api): dependencies can be drawn and erased, and a refusal names the loop"
```

---

### Task 9: `GET /api/timeline`

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/service/TimelineService.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/api/TimelineController.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/service/TimelineServiceTest.kt`

**Interfaces:**
- Consumes: `CriticalPath.slack` (Task 6), `DependencyRepository` (Task 4).
- Produces: `TimelineService.load(teamId: UUID?, projectId: UUID?): TimelineView` and
  the response shape from the spec.

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/service/TimelineServiceTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Transactional
class TimelineServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var timeline: TimelineService
	@Autowired lateinit var deps: DependencyRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "tl-${UUID.randomUUID()}@kanso.test",
			displayName = "Timeline admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "TL", "T${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun day(d: Int) = KansoInstant(
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC),
		false,
	)

	private fun ticket(title: String, projectId: UUID?, start: Int?, due: Int?, status: TicketStatus = TicketStatus.TODO) =
		tickets.create(
			teamId = team.id,
			title = title,
			description = null,
			status = status,
			priority = TicketPriority.NONE,
			start = start?.let(::day),
			due = due?.let(::day),
			projectId = projectId,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id

	@Test
	fun `a project with no explicit bounds derives them from its tickets`() {
		val project = projects.create(
			name = "Derived",
			status = ProjectStatus.IN_PROGRESS,
			start = null,
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		ticket("Early", project.id, 3, 6)
		ticket("Late", project.id, 10, 20)

		val row = timeline.load(teamId = team.id, projectId = null).projects.single { it.id == project.id }

		assertEquals(day(3).at, row.start!!.at)
		assertEquals(day(20).at, row.end!!.at)
		assertTrue(row.startDerived && row.endDerived, "nothing was posed, so both bounds are consequences")
	}

	@Test
	fun `an explicit bound wins over the derivation, one bound at a time`() {
		val project = projects.create(
			name = "Half posed",
			status = ProjectStatus.IN_PROGRESS,
			start = day(1),
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		ticket("Only", project.id, 5, 9)

		val row = timeline.load(teamId = team.id, projectId = null).projects.single { it.id == project.id }

		assertEquals(day(1).at, row.start!!.at)
		assertEquals(false, row.startDerived)
		assertEquals(day(9).at, row.end!!.at)
		assertEquals(true, row.endDerived)
	}

	@Test
	fun `a project whose tickets are only done falls back to when they were done`() {
		val project = projects.create(
			name = "Retrospective",
			status = ProjectStatus.COMPLETED,
			start = null,
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		val id = ticket("Undated but finished", project.id, null, null)
		tickets.patch(id, TicketPatch(status = TicketStatus.DONE))

		val row = timeline.load(teamId = team.id, projectId = null).projects.single { it.id == project.id }

		assertTrue(row.start != null, "a completion date is a worse answer than a plan, and a better one than a blank row")
	}

	@Test
	fun `undated tickets are listed separately rather than dropped`() {
		val id = ticket("No dates", null, null, null)

		val view = timeline.load(teamId = team.id, projectId = null)

		assertTrue(view.unscheduled.any { it.id == id })
		assertTrue(view.tickets.none { it.id == id })
	}

	@Test
	fun `criticality is computed over the whole component, not the visible scope`() {
		val other = teams.create(admin, "Other", "O${UUID.randomUUID().toString().take(4).uppercase()}", null)
		val here = ticket("Here", null, 1, 10)
		val elsewhere = tickets.create(
			teamId = other.id,
			title = "Elsewhere",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = day(10),
			due = day(30),
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
		deps.insert(here, elsewhere)

		val view = timeline.load(teamId = team.id, projectId = null)
		val row = view.tickets.single { it.id == here }

		assertEquals(true, row.critical, "the chain ends outside the scope, and that is what anchors it")
		assertTrue(
			view.dependencies.single().outOfScope,
			"the other end is not in the response, so the view draws a stub",
		)
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps/api/gradlew -p apps/api test --tests '*TimelineServiceTest*'`
Expected: FAIL — `TimelineService` does not exist.

- [ ] **Step 3: Write the service**

Create `apps/api/src/main/kotlin/dev/kanso/service/TimelineService.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.domain.KansoInstant
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketStatus
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.schedule.CriticalPath
import dev.kanso.schedule.Edge
import dev.kanso.schedule.Node
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

data class TimelineProject(
	val id: UUID,
	val name: String,
	val start: KansoInstant?,
	val end: KansoInstant?,
	val startDerived: Boolean,
	val endDerived: Boolean,
)

data class TimelineTicket(
	val id: UUID,
	val identifier: String,
	val title: String,
	val projectId: UUID?,
	val status: TicketStatus,
	val start: KansoInstant?,
	val due: KansoInstant?,
	/** Null for an unscheduled ticket or one with no dependencies. */
	val slackMinutes: Long?,
	val critical: Boolean,
	val late: Boolean,
)

data class TimelineEdge(
	val predecessorId: UUID,
	val successorId: UUID,
	val violated: Boolean,
	/** True when the other end is absent from this response — the view draws a stub. */
	val outOfScope: Boolean,
)

data class TimelineUnscheduled(val id: UUID, val identifier: String, val title: String)

data class TimelineView(
	val projects: List<TimelineProject>,
	val tickets: List<TimelineTicket>,
	val dependencies: List<TimelineEdge>,
	val unscheduled: List<TimelineUnscheduled>,
)

/**
 * One read for the whole screen: derived bounds, slack and criticality are computed
 * together over the same component closure, and splitting them across endpoints would
 * mean walking that closure three times.
 */
@Service
class TimelineService(
	private val tickets: TicketRepository,
	private val projects: ProjectRepository,
	private val teams: TeamRepository,
	private val dependencies: DependencyRepository,
) {

	@Transactional(readOnly = true)
	fun load(teamId: UUID?, projectId: UUID?): TimelineView {
		val teamIds = teamId?.let { teams.descendantIds(it) }
		val inScope = tickets.search(
			teamIds = teamIds,
			projectId = projectId,
			includeArchived = false,
			limit = 2000,
		)
		val scopeIds = inScope.map { it.id }.toSet()

		// The closure, not the scope: anchoring the critical path on what happens to be
		// visible would repaint identical data when the filter changes.
		val componentIds = dependencies.componentIds(scopeIds)
		val graphTickets = tickets.findAllById(componentIds)
		val edges = dependencies.edgesTouching(componentIds)

		val projectEnds = projects.findAllById(
			graphTickets.mapNotNull { it.projectId }.toSet()
		).mapNotNull { project -> project.end?.let { project.id to it.at } }.toMap()

		val deadlines = graphTickets.mapNotNull { ticket ->
			ticket.projectId?.let { projectEnds[it] }?.let { ticket.id to it }
		}.toMap()

		val slack = CriticalPath.slack(graphTickets.map(::toNode), edges, deadlines)
		val keys = teams.findAllById(inScope.map { it.teamId }.toSet()).associate { it.id to it.key }
		fun identifier(ticket: Ticket) = "${keys[ticket.teamId] ?: "?"}-${ticket.number}"

		val scheduled = inScope.filter { it.start != null || it.due != null }
		val violated = violatedEdges(graphTickets.associateBy { it.id }, edges)

		return TimelineView(
			projects = projectRows(inScope, projectId, teamIds),
			tickets = scheduled.map { ticket ->
				val minutes = slack[ticket.id]?.toMinutes()
				TimelineTicket(
					id = ticket.id,
					identifier = identifier(ticket),
					title = ticket.title,
					projectId = ticket.projectId,
					status = ticket.status,
					start = ticket.start,
					due = ticket.due,
					slackMinutes = minutes,
					critical = minutes == 0L,
					late = minutes != null && minutes < 0L,
				)
			},
			dependencies = edges.filter { it.predecessorId in scopeIds || it.successorId in scopeIds }
				.map {
					TimelineEdge(
						predecessorId = it.predecessorId,
						successorId = it.successorId,
						violated = it in violated,
						outOfScope = it.predecessorId !in scopeIds || it.successorId !in scopeIds,
					)
				},
			unscheduled = inScope.filter { it.start == null && it.due == null }
				.map { TimelineUnscheduled(it.id, identifier(it), it.title) },
		)
	}

	/**
	 * An edge is violated when its predecessor ends after its successor starts and the
	 * cascade could not repair it — which happens exactly when the successor is done.
	 */
	private fun violatedEdges(byId: Map<UUID, Ticket>, edges: List<Edge>): Set<Edge> =
		edges.filterTo(mutableSetOf()) { edge ->
			val predecessorEnd = byId[edge.predecessorId]?.let { it.due?.at ?: it.start?.at }
			val successor = byId[edge.successorId]
			val successorStart = successor?.let { it.start?.at ?: it.due?.at }
			predecessorEnd != null && successorStart != null &&
				successor.status == TicketStatus.DONE && successorStart.isBefore(predecessorEnd)
		}

	/**
	 * Bounds resolve per bound independently: an explicit date, else the tickets'
	 * planned dates, else when the done ones were completed, else no bar. The third
	 * rule is retrospective by construction — a worse answer than a plan, a better one
	 * than a blank row.
	 */
	private fun projectRows(
		inScope: List<Ticket>,
		projectId: UUID?,
		teamIds: List<UUID>?,
	): List<TimelineProject> {
		val candidates = when {
			projectId != null -> projects.findAllById(setOf(projectId))
			teamIds != null -> projects.findAllByTeams(teamIds)
			else -> projects.findAll(includeArchived = false)
		}
		val byProject = inScope.groupBy { it.projectId }

		return candidates.map { project ->
			val members = byProject[project.id].orEmpty()
			val planned = members.mapNotNull { it.start?.at ?: it.due?.at }
			val plannedEnd = members.mapNotNull { it.due?.at ?: it.start?.at }
			val completed = members.mapNotNull { it.completedAt }

			val derivedStart = planned.minOrNull() ?: completed.minOrNull()
			val derivedEnd = plannedEnd.maxOrNull() ?: completed.maxOrNull()

			TimelineProject(
				id = project.id,
				name = project.name,
				start = project.start ?: derivedStart?.let { KansoInstant(it, false) },
				end = project.end ?: derivedEnd?.let { KansoInstant(it, false) },
				startDerived = project.start == null,
				endDerived = project.end == null,
			)
		}
	}

	private fun toNode(ticket: Ticket) = Node(
		id = ticket.id,
		start = ticket.start?.at,
		end = ticket.due?.at,
		done = ticket.status == TicketStatus.DONE,
	)
}
```

Add `ProjectRepository.findAllByTeams(teamIds: Collection<UUID>): List<Project>` and
`ProjectRepository.findAllById(ids: Collection<UUID>): List<Project>` if either is
missing, following the shape `TeamRepository.findAllById` already uses.

- [ ] **Step 4: Write the controller and DTOs**

In `Dtos.kt`:

```kotlin
data class TimelineProjectResponse(
	val id: UUID,
	val name: String,
	val start: TimelineBoundDto?,
	val end: TimelineBoundDto?,
)

/** A bound plus where it came from: without `derived`, nothing says what may be edited. */
data class TimelineBoundDto(val at: OffsetDateTime, val hasTime: Boolean, val derived: Boolean)

data class TimelineTicketResponse(
	val id: UUID,
	val identifier: String,
	val title: String,
	val projectId: UUID?,
	val status: String,
	val start: InstantDto?,
	val due: InstantDto?,
	val slackMinutes: Long?,
	val critical: Boolean,
	val late: Boolean,
)

data class TimelineDependencyResponse(
	val predecessorId: UUID,
	val successorId: UUID,
	val violated: Boolean,
	val outOfScope: Boolean,
)

data class TimelineUnscheduledResponse(val id: UUID, val identifier: String, val title: String)

data class TimelineResponse(
	val projects: List<TimelineProjectResponse>,
	val tickets: List<TimelineTicketResponse>,
	val dependencies: List<TimelineDependencyResponse>,
	val unscheduled: List<TimelineUnscheduledResponse>,
) {
	companion object {
		fun of(view: TimelineView) = TimelineResponse(
			projects = view.projects.map { project ->
				TimelineProjectResponse(
					id = project.id,
					name = project.name,
					start = project.start?.let { TimelineBoundDto(it.at, it.hasTime, project.startDerived) },
					end = project.end?.let { TimelineBoundDto(it.at, it.hasTime, project.endDerived) },
				)
			},
			tickets = view.tickets.map {
				TimelineTicketResponse(
					id = it.id,
					identifier = it.identifier,
					title = it.title,
					projectId = it.projectId,
					status = it.status.wire,
					start = InstantDto.of(it.start),
					due = InstantDto.of(it.due),
					slackMinutes = it.slackMinutes,
					critical = it.critical,
					late = it.late,
				)
			},
			dependencies = view.dependencies.map {
				TimelineDependencyResponse(it.predecessorId, it.successorId, it.violated, it.outOfScope)
			},
			unscheduled = view.unscheduled.map {
				TimelineUnscheduledResponse(it.id, it.identifier, it.title)
			},
		)
	}
}
```

Add the `dev.kanso.service.TimelineView` import. Create
`apps/api/src/main/kotlin/dev/kanso/api/TimelineController.kt`:

```kotlin
package dev.kanso.api

import dev.kanso.service.TimelineService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/timeline")
class TimelineController(private val timeline: TimelineService) {

	@GetMapping
	fun load(
		@RequestParam(required = false) teamId: UUID?,
		@RequestParam(required = false) projectId: UUID?,
	): TimelineResponse = TimelineResponse.of(timeline.load(teamId, projectId))
}
```

- [ ] **Step 5: Run the tests**

Run: `apps/api/gradlew -p apps/api test --tests '*TimelineServiceTest*'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add apps/api
git commit -m "feat(api): one timeline read carrying bounds, slack and the arrows"
```

---

### Task 10: The mirror learns about times and dependencies

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/notion/NotionSchema.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/outbound/NotionMapper.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/inbound/NotionPoller.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/sync/bootstrap/NotionBootstrap.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/ScheduleServiceTest.kt`

**Interfaces:**
- Consumes: `ScheduleService.cascadeFrom` (Task 7), `DependencyRepository` (Task 4).

- [ ] **Step 1: Write the failing test**

Append to `ScheduleServiceTest`:

```kotlin
	@Test
	fun `a date arriving from Notion goes through the cascade like any other write`() {
		val a = ticket("A", 1, 10)
		val b = ticket("B", 10, 15)
		deps.insert(a, b)

		// What the poller does: write the scalar, then let the engine settle the graph.
		repo.reschedule(a, day(1).at, day(12).at)
		schedule.cascadeFrom(a)

		assertEquals(
			day(12).at,
			repo.findById(b)!!.start!!.at,
			"a date edited in Notion must not bypass the engine and break the plan in silence",
		)
	}
```

Add `@Autowired lateinit var schedule: ScheduleService`.

- [ ] **Step 2: Run it and watch it fail**

Run: `apps/api/gradlew -p apps/api test --tests '*ScheduleServiceTest*'`
Expected: PASS already — `cascadeFrom` is public and does the work. This test pins the
contract the poller must use; the failure it guards against is the poller *not* calling
it, which the next step fixes.

- [ ] **Step 3: Route the poller through the engine**

In `NotionPoller.applyTicket`, capture whether the dates changed and cascade after the
write. Replace the block from `tickets.update(` through `tickets.recordNotionEdit(...)`:

```kotlin
		val start = instant(props, NotionProps.START) ?: ticket.start
		val due = instant(props, NotionProps.DUE) ?: ticket.due

		tickets.update(
			id = ticket.id,
			teamId = ticket.teamId,
			title = title(props) ?: ticket.title,
			description = text(props, NotionProps.DESCRIPTION) ?: ticket.description,
			status = status ?: ticket.status,
			priority = priority ?: ticket.priority,
			start = start,
			due = due,
			completedAt = ticket.completedAt,
			projectId = ticket.projectId,
			archived = page.archived,
		)
		tickets.recordNotionEdit(ticket.id, page.lastEditedTime)

		// A date edited in Notion is a date change like any other. Writing it straight
		// to the row would let it bypass the engine, and the plan would break with
		// nobody able to say why. The anti-echo guards above stop the pushes this
		// queues from coming back round.
		if (start != ticket.start || due != ticket.due) schedule.cascadeFrom(ticket.id)
```

Add `private val schedule: ScheduleService` to the `NotionPoller` constructor and the
matching import.

- [ ] **Step 4: Stop pushing derived project bounds**

`NotionMapper` already writes `project.start` and `project.end`, which are null when
derived — so nothing to change in code. Add the comment that stops someone
"fixing" it later, above the two `put` calls in the project branch:

```kotlin
			// Explicit bounds only. `project.start` and `project.end` are null when the
			// bound is derived from the tickets, and that is deliberate: pushing a
			// computed bound would have the poller read it back as a posed one, quietly
			// turning a derivation into a pinned date nobody chose. Same trade as the
			// two-date deviation — an honest round trip over a prettier mirror.
```

- [ ] **Step 5: Mirror the dependencies**

In `NotionProps`, add:

```kotlin
	const val BLOCKED_BY = "Blocked by"
	const val BLOCKS = "Blocks"
```

In `NotionSchema.relationTo`'s caller inside `NotionBootstrap`, register the
self-referencing relation on the Tickets database for `BLOCKED_BY`, pointing at the
Tickets database itself — the same shape the teams' `PARENT_TEAM` relation already
uses. Follow that call site exactly.

In `NotionMapper`'s ticket branch, add:

```kotlin
			// Kanso-authoritative, like every other relation: Notion's self-referencing
			// relation accepts a cycle without complaint, so these arrows are written
			// and never read back.
			put(NotionProps.BLOCKED_BY, NotionProps.relation(predecessorPageIds))
```

where `predecessorPageIds` comes from a new
`DependencyRepository.predecessorPageIds(ticketId: UUID): List<String>` joining
`ticket_dependencies` to `tickets.notion_page_id` and dropping the nulls — a
predecessor with no page yet is deferred by the job's dependency priority, exactly as
the project relation already is.

- [ ] **Step 6: Run the whole suite**

Run: `apps/api/gradlew -p apps/api test`
Expected: PASS

- [ ] **Step 7: Update the architecture notes**

In `docs/architecture.md`, under "Mechanisms", add a `### Scheduling` subsection stating
the three rules (slack respected, never backwards, done never moved), that the critical
path is per connected component, and that the raw-SQL count is now six — the component
closure and the cycle path join the four already listed. Mirror the change in
`docs/architecture.fr.md`.

- [ ] **Step 8: Commit**

```bash
git add apps/api docs
git commit -m "feat(api): the mirror carries the arrows, and Notion edits go through the engine"
```

---

## Self-Review

**Spec coverage.** Every section of
`docs/superpowers/specs/2026-08-10-timeline-dependencies-design.md` that belongs to
part 1 maps to a task: data model → Tasks 1, 3, 4; the engine → Tasks 5, 6, 7; API →
Tasks 8, 9; the Notion mirror → Task 10; tests → each task's own TDD cycle. The view
section and the keyboard section are part 2 and deliberately absent.

**Deviation from the spec, deliberate:** the spec named one migration `V6`. This plan
splits it into `V6__timeline_dates.sql` and `V7__ticket_dependencies.sql`, so Task 1 and
Task 4 are separately reviewable and separately revertable.

**Known gap, carried not closed:** every service test here is `@Transactional` and rolls
back, so no event reaches `pg_notify` in the suite — the single cascade event is
asserted nowhere. `follow-ups.md` already records why, and closing it needs a different
test shape rather than another assertion.
