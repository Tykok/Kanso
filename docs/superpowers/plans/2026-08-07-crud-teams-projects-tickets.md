# Creating Teams, Projects and Tickets — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the web client the create, edit, archive and delete paths the server already
exposes for teams, sub-teams, projects and tickets, behind one action registry that the
keyboard, the palette, the menus and the buttons all read from.

**Architecture:** The server keeps its shape and gains three things — an admin guard on team
writes, a *disposition plan* that both archiving and deletion take so the foreign keys stop
deciding what happens to a team's contents, and block allocation of ticket numbers for the
one case that moves tickets between teams. The client stops defining every action twice
(once in a `switch` over `event.key`, once in a `commands` array) and defines each one once,
in `lib/actions.ts`, with a `when` predicate that answers both "may I show this" and "may I
run this". The sidebar becomes the tree the data already describes.

**Tech Stack:** Kotlin 2 / Spring Boot / Exposed / Flyway / Postgres 16 on the API side;
Next.js 16.3.0, React 19.2.8, TypeScript 5.9.3 (strict), TanStack Query 5, zustand 5 on the
web side. Tests: JUnit 5 + Testcontainers (existing), Vitest 4.1.10 (new, `apps/web`),
Playwright 1.62.1 (new, repository root).

**Spec:** `docs/superpowers/specs/2026-08-07-crud-teams-projects-tickets-design.md`

## Global Constraints

- **No database migration.** The schema already says what is needed:
  `teams.parent_team_id` is a self-referencing FK, `projects.team_id` is nullable,
  `tickets.team_id` is `NOT NULL`, `tickets.project_id` is nullable. The work is taking
  the decisions back from `ON DELETE CASCADE` and `ON DELETE SET NULL`, not changing them.
- **Kotlin is indented with tabs.** Every Kotlin snippet here is tab-indented; paste as is.
- **Raw SQL only where the Exposed DSL cannot go** — recursive CTEs and `UPDATE … RETURNING`
  — and always through `jdbc.sql(...).param(...)` on the connection Spring already holds.
  Everything else is the DSL.
- **Flyway owns the schema.** No DDL generation, ever.
- **pnpm, never npm**, and only inside `apps/web` — there is no root `package.json` until
  task 17 creates one for Playwright alone.
- **Node must be `^20.19.0 || >=22.12.0`** before installing Vitest. Vite 8 builds on
  rolldown, whose native binding is an optional dependency gated on that range; on an older
  Node, `pnpm add` installs nothing for it and the first `vitest run` dies with
  `Cannot find native binding`.
- **TypeScript is strict.** No `any`, no unexplained `!`.
- **The interface is in English.** The spec's mockups are written in French; the application
  is not (`Views`, `All tickets`, `New ticket…`, `Settings`). The layout follows the mockups,
  the strings are English, and the Playwright selectors in task 17 target those strings.
- **Actor-passing, not context-reading.** Services take the acting `User` as a parameter, as
  `AccountService.setInstanceRole` already does. Controllers resolve it with
  `CurrentUser.require()`. This keeps every authorisation rule testable without a security
  context.
- **Docker must be running** for the API tests (Testcontainers) and for the E2E run.
- **Every task ends with a commit.**

## Two deviations from the interface contract, both forced

1. `ActionContext` carries `unarchive: (target: { kind: "team" | "project"; id: string }) => void`.
   `team.unarchive` and `project.unarchive` are mandatory action ids, and nothing else in
   the context could perform that write — `openDialog` only reaches a closed union of dialog
   kinds, and `patchTicket` is for tickets.
2. The Vitest config file is `vitest.config.mts`, not `.ts`. `apps/web/package.json` has no
   `"type": "module"`, so Vite loads a `.ts` config as CommonJS and warns on every run.
   `.mts` is what the in-repo Next docs recommend and `tsconfig.json` already includes it.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `apps/api/…/domain/Disposition.kt` | `DispositionChoice`, `DispositionCounts`, `DispositionPlan` | 3 |
| `apps/api/…/service/Errors.kt` | `CountsChangedException` alongside the existing three | 3 |
| `apps/api/…/api/ApiExceptionHandler.kt` | 409 carrying the fresh counts | 3 |
| `apps/api/…/repo/TeamRepository.kt` | block number allocation, ancestor/child walks, bulk archive | 2, 3, 4 |
| `apps/api/…/repo/TicketRepository.kt` | counting, moving, bulk archive/delete by team and project | 3, 4, 5, 6 |
| `apps/api/…/repo/ProjectRepository.kt` | counting, re-homing, bulk archive/delete by team | 3, 4, 5, 6 |
| `apps/api/…/service/TeamService.kt` | admin guard, `contents`, `archive`, `unarchive`, `delete` | 1, 3, 4, 5 |
| `apps/api/…/service/ProjectService.kt` | `contents`, `archive`, `unarchive`, `delete` | 3, 6 |
| `apps/api/…/service/TicketService.kt` | project/team coherence on patch | 7 |
| `apps/api/…/api/TeamController.kt`, `ProjectController.kt`, `Dtos.kt` | the HTTP surface | 1, 3, 4, 5, 6 |
| `apps/web/vitest.config.mts` | test runner, `@` alias | 8 |
| `apps/web/src/lib/actions.ts` | **the** definition of every action | 8 |
| `apps/web/src/lib/use-action-ctx.ts` | assembles data, mutations, permissions into an `ActionContext` | 11 |
| `apps/web/src/store/ui.ts` | `Scope`, `Dialog`, `showArchived` | 9 |
| `apps/web/src/lib/api.ts`, `queries.ts` | client surface and query keys | 10 |
| `apps/web/src/app/page.tsx` | composition only | 11 |
| `apps/web/src/components/dialogs/field.tsx` | `Field`, `DialogFrame` — dialog chrome | 12 |
| `apps/web/src/components/menu.tsx` | the `⋯` popover | 12 |
| `apps/web/src/components/sidebar.tsx` | the tree, the `Projects` section, row menus | 13 |
| `apps/web/src/components/dialogs/team-dialog.tsx` | create/edit a team | 14 |
| `apps/web/src/components/dialogs/project-dialog.tsx` | create/edit a project | 14 |
| `apps/web/src/components/dialogs/disposition-dialog.tsx` | archive/delete, one severity prop | 15 |
| `apps/web/src/components/composer.tsx` | title + context bar | 16 |
| `playwright.config.ts`, `e2e/` | the five scenarios | 17 |

## Task order and why

Tasks 1–7 are the server, and each leaves it working: the guard before the plan, the block
allocator before the mover that needs it, archiving before deletion because deletion reuses
its dispersal code, and the ticket/project coherence fix last because nothing depends on it.

Tasks 8–11 are the client's foundation. Task 8 is the only one with real unit tests, and it
is deliberately first: the registry it produces is what tasks 11–16 all consume, and its
tests are what say whether the keyboard still behaves.

Tasks 12–16 are the interface. They have no unit tests — there is no React rendering
harness in this repo and this plan does not add one. Their verification is `tsc --noEmit`,
`pnpm lint`, and a written click-through.

Task 17 is what actually covers 12–16, and its scenario 5 is the guard on the whole
refactor: the shortcuts must do in Playwright exactly what they do today.

---


## Part 1 — The server (tasks 1–7)

Everything here happens under `apps/api/`. Commands are written to be run from the
repository root (`/Users/elietreport/Projet/Perso/Kanso`). Docker must be running: the
tests boot a real Postgres through Testcontainers.

Two conventions the codebase already holds and this plan keeps:

- **Kotlin is indented with tabs.** Every snippet below is tab-indented; paste it as is.
- **Raw SQL goes through `jdbc.sql(...).param(...)`, simple CRUD goes through the Exposed
  DSL.** Only recursive CTEs and `UPDATE … RETURNING` cross into raw SQL.

Line numbers in the **Files** blocks refer to each file as it stands *before* that task is
applied. Every step also names the symbol it touches, so it can be found regardless of
drift.

---

### Task 1: Team writes require an instance admin

`TeamService.create`, `update`, `addMember` and `removeMember` take the acting `User` and
refuse anyone who cannot configure the instance, exactly as `AccountService.setInstanceRole`
already does. Controllers resolve the actor with `CurrentUser.require()`.

`update` keeps its `archived` parameter for now — task 4 removes it when `archive`/
`unarchive` take over. Keeping it here means the archive path never stops working
between two commits.

`TicketWorkflowTest` calls `create` and `update`, so it changes with the signatures or the
test source stops compiling.

**Files:**
- Create: `apps/api/src/test/kotlin/dev/kanso/service/TeamPermissionTest.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt:1-17,39-51,101-119`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt:10-59`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TicketWorkflowTest.kt:18-27,153-157,237-245`

**Interfaces:**
- Consumes: existing `TeamService(teams, users, syncJobs, events)`;
  `dev.kanso.auth.CurrentUser.require(): User`;
  `dev.kanso.domain.InstanceRole.canConfigureInstance: Boolean`;
  `dev.kanso.repo.UserRepository.createLocalUser(email: String, displayName: String, passwordHash: String, role: InstanceRole): User`;
  `dev.kanso.auth.hash(password: String): String` (extension on `PasswordEncoder`).
- Produces:
  - `TeamService.create(actor: User, name: String, key: String?, parentTeamId: UUID?): Team`
  - `TeamService.update(actor: User, id: UUID, name: String, key: String, parentTeamId: UUID?, archived: Boolean): Team`
  - `TeamService.addMember(actor: User, teamId: UUID, userId: UUID, role: MemberRole): List<TeamMember>`
  - `TeamService.removeMember(actor: User, teamId: UUID, userId: UUID)`
  - `TeamService.requireConfigurator(actor: User)` (private; tasks 4 and 5 reuse it)

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/service/TeamPermissionTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The shape of the organisation is an admin decision. The daily work — projects,
 * tickets — deliberately is not, and nothing here asserts otherwise.
 */
@Transactional
class TeamPermissionTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole): User = users.createLocalUser(
		email = "user-${UUID.randomUUID()}@kanso.test",
		displayName = "Test ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun key() = "T${UUID.randomUUID().toString().take(5).uppercase()}"

	@Test
	fun `a member cannot create a team`() {
		val member = user(InstanceRole.MEMBER)
		assertFailsWith<AccessDeniedException> { teams.create(member, "Core", key(), null) }
	}

	@Test
	fun `an admin creates and renames a team`() {
		val admin = user(InstanceRole.ADMIN)

		val created = teams.create(admin, "Core", key(), null)
		val renamed = teams.update(admin, created.id, "Core Platform", created.key, null, archived = false)

		assertEquals("Core Platform", renamed.name)
	}

	@Test
	fun `the owner may configure teams too`() {
		val owner = user(InstanceRole.OWNER)
		assertEquals("Owned", teams.create(owner, "Owned", key(), null).name)
	}

	@Test
	fun `a member cannot rename a team an admin created`() {
		val admin = user(InstanceRole.ADMIN)
		val member = user(InstanceRole.MEMBER)
		val team = teams.create(admin, "Growth", key(), null)

		assertFailsWith<AccessDeniedException> {
			teams.update(member, team.id, "Hijacked", team.key, null, archived = false)
		}
	}

	@Test
	fun `only a configurator changes the membership list`() {
		val admin = user(InstanceRole.ADMIN)
		val member = user(InstanceRole.MEMBER)
		val team = teams.create(admin, "Mobile", key(), null)

		assertFailsWith<AccessDeniedException> {
			teams.addMember(member, team.id, member.id, MemberRole.MEMBER)
		}
		assertEquals(1, teams.addMember(admin, team.id, member.id, MemberRole.MEMBER).size)

		assertFailsWith<AccessDeniedException> { teams.removeMember(member, team.id, member.id) }
		teams.removeMember(admin, team.id, member.id)
		assertEquals(0, teams.members(team.id).size)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.TeamPermissionTest"`

Expected: FAIL — the test source does not compile. `compileTestKotlin` reports, for every
call site, `Too many arguments for 'fun create(name: String, key: String?, parentTeamId: UUID?): Team'`
(and the same for `update`, `addMember`, `removeMember`).

- [ ] **Step 3: Add the guard and the actor to `TeamService`**

In `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt`, add two imports next to the
existing ones:

```kotlin
import dev.kanso.domain.User
import org.springframework.security.access.AccessDeniedException
```

Replace the `create` function (currently lines 39–48) with:

```kotlin
	@Transactional
	fun create(actor: User, name: String, key: String?, parentTeamId: UUID?): Team {
		requireConfigurator(actor)
		if (parentTeamId != null && teams.findById(parentTeamId) == null) {
			throw BadRequestException("Parent team $parentTeamId does not exist")
		}
		val team = teams.insert(name, resolveKey(name, key), parentTeamId)
		syncJobs.enqueue(SyncEntityType.TEAM, team.id, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.CREATED, team.id))
		return team
	}
```

Replace the first line of `update` (currently line 51) so the signature and the guard read:

```kotlin
	@Transactional
	fun update(actor: User, id: UUID, name: String, key: String, parentTeamId: UUID?, archived: Boolean): Team {
		requireConfigurator(actor)
		val existing = get(id)
```

(the rest of `update` is unchanged.)

Replace `addMember` and `removeMember` (currently lines 101–119) with:

```kotlin
	@Transactional
	fun addMember(actor: User, teamId: UUID, userId: UUID, role: MemberRole): List<TeamMember> {
		requireConfigurator(actor)
		get(teamId)
		users.findById(userId) ?: throw BadRequestException("No user $userId")
		teams.addMember(teamId, userId, role)
		syncJobs.enqueue(SyncEntityType.TEAM, teamId, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.UPDATED, teamId))
		return teams.members(teamId)
	}

	@Transactional
	fun removeMember(actor: User, teamId: UUID, userId: UUID) {
		requireConfigurator(actor)
		get(teamId)
		if (!teams.removeMember(teamId, userId)) {
			throw NotFoundException("User $userId is not a member of team $teamId")
		}
		syncJobs.enqueue(SyncEntityType.TEAM, teamId, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.UPDATED, teamId))
	}
```

Add the guard itself just above the `// --- keys ---` banner (currently line 121):

```kotlin
	// --- permissions ---------------------------------------------------------

	/**
	 * Teams are instance configuration, not daily work: who reports to whom decides
	 * what everyone else sees. The client hides what it may not do from `/api/me`;
	 * this is the backstop, not the mechanism.
	 */
	private fun requireConfigurator(actor: User) {
		if (!actor.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can change teams")
		}
	}

```

- [ ] **Step 4: Pass the actor from `TeamController`**

Replace `apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt` in full:

```kotlin
package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.MemberRole
import dev.kanso.service.TeamService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/teams")
class TeamController(
	private val teams: TeamService,
	private val currentUser: CurrentUser,
) {

	@GetMapping
	fun list(@RequestParam(defaultValue = "false") includeArchived: Boolean): List<TeamResponse> =
		teams.list(includeArchived).map(TeamResponse::of)

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): TeamResponse = TeamResponse.of(teams.get(id))

	/** The team and every team under it, at any depth. */
	@GetMapping("/{id}/descendants")
	fun descendants(@PathVariable id: UUID): List<TeamResponse> =
		teams.descendants(id).map(TeamResponse::of)

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: TeamRequest): TeamResponse = TeamResponse.of(
		teams.create(currentUser.require(), request.name, request.key?.uppercase(), request.parentTeamId)
	)

	@PutMapping("/{id}")
	fun update(@PathVariable id: UUID, @Valid @RequestBody request: TeamRequest): TeamResponse {
		val actor = currentUser.require()
		val current = teams.get(id)
		return TeamResponse.of(
			teams.update(
				actor = actor,
				id = id,
				name = request.name,
				key = request.key?.uppercase() ?: current.key,
				parentTeamId = request.parentTeamId,
				archived = request.archived,
			)
		)
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) = teams.delete(id)

	@GetMapping("/{id}/members")
	fun members(@PathVariable id: UUID): List<MemberResponse> =
		teams.members(id).map(MemberResponse::of)

	@PostMapping("/{id}/members")
	fun addMember(@PathVariable id: UUID, @RequestBody request: AddMemberRequest): List<MemberResponse> =
		teams.addMember(currentUser.require(), id, request.userId, MemberRole.from(request.role))
			.map(MemberResponse::of)

	@DeleteMapping("/{id}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun removeMember(@PathVariable id: UUID, @PathVariable userId: UUID) =
		teams.removeMember(currentUser.require(), id, userId)
}
```

- [ ] **Step 5: Give the existing workflow test an actor**

In `apps/api/src/test/kotlin/dev/kanso/service/TicketWorkflowTest.kt`, add these imports to
the existing block:

```kotlin
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
```

Replace the field block and `newTeam` helper (currently lines 21–27) with:

```kotlin
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	/** Team writes are admin-only, so every team this file builds needs one. */
	private val admin: User by lazy {
		users.createLocalUser(
			email = "workflow-${UUID.randomUUID()}@kanso.test",
			displayName = "Workflow admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun newTeam(name: String = "Team ${UUID.randomUUID().toString().take(4)}") =
		teams.create(admin, name, "K${UUID.randomUUID().toString().take(4).uppercase()}", null)
```

In `tickets can be listed across a team subtree` (currently line 156) replace the child
creation with:

```kotlin
		val child = teams.create(admin, "Child", "CH${UUID.randomUUID().toString().take(3).uppercase()}", parent.id)
```

In `a team cannot be moved under its own descendant` (currently lines 239–244) replace the
body with:

```kotlin
		val root = newTeam("Root")
		val child = teams.create(admin, "Child", "CD${UUID.randomUUID().toString().take(3).uppercase()}", root.id)

		assertFailsWith<ConflictException> {
			teams.update(admin, root.id, root.name, root.key, child.id, archived = false)
		}
```

- [ ] **Step 6: Run both tests to verify they pass**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.TeamPermissionTest" --tests "dev.kanso.service.TicketWorkflowTest"`

Expected: PASS, 5 + 9 tests.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt \
        apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt \
        apps/api/src/test/kotlin/dev/kanso/service/TeamPermissionTest.kt \
        apps/api/src/test/kotlin/dev/kanso/service/TicketWorkflowTest.kt
git commit -m "feat(api): require an instance admin for team writes"
```

---

### Task 2: Allocate ticket numbers a block at a time

Moving 47 tickets into another team needs 47 numbers from that team's counter. Taking them
one at a time holds the same row lock for the same span while adding a round trip per
ticket inside it, so every `c` pressed in the destination team would wait longer for no
benefit. One `UPDATE … RETURNING` reserves the block and hands back
`(new − n + 1 … new)`.

**Files:**
- Create: `apps/api/src/test/kotlin/dev/kanso/repo/TicketNumberBlockTest.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TeamRepository.kt:98-105`
- Test: `apps/api/src/test/kotlin/dev/kanso/repo/TicketNumberBlockTest.kt`

**Interfaces:**
- Consumes: `TeamRepository.nextTicketNumber(teamId: UUID): Int`,
  `TeamRepository.insert(name: String, key: String, parentTeamId: UUID?): Team`,
  `TeamRepository.findById(id: UUID): Team?`, `TeamRepository.delete(id: UUID): Boolean`.
- Produces: `TeamRepository.nextTicketNumbers(teamId: UUID, count: Int): List<Int>` —
  consumed by task 4 (`TeamService`) when tickets are re-homed.

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/repo/TicketNumberBlockTest.kt`. Two classes in
one file, as `TeamHierarchyTest.kt` already does: the transactional one for the arithmetic,
a non-transactional one for the lock.

```kotlin
package dev.kanso.repo

import dev.kanso.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Transactional
class TicketNumberBlockTest : PostgresTest() {

	@Autowired lateinit var teams: TeamRepository

	private fun uniqueKey() = "B${UUID.randomUUID().toString().take(5).uppercase()}"

	@Test
	fun `a block is consecutive and continues from the counter`() {
		val team = teams.insert("Block", uniqueKey(), null)

		assertEquals(listOf(1, 2, 3), teams.nextTicketNumbers(team.id, 3))
		assertEquals(4, teams.nextTicketNumber(team.id), "a block leaves the counter where it stopped")
		assertEquals(listOf(5, 6), teams.nextTicketNumbers(team.id, 2))
		assertEquals(6, requireNotNull(teams.findById(team.id)).ticketCounter)
	}

	@Test
	fun `a block of zero reserves nothing`() {
		val team = teams.insert("Empty block", uniqueKey(), null)

		assertEquals(emptyList<Int>(), teams.nextTicketNumbers(team.id, 0))
		assertEquals(0, requireNotNull(teams.findById(team.id)).ticketCounter)
	}

	@Test
	fun `a negative block is a programming error, not a silent no-op`() {
		val team = teams.insert("Negative block", uniqueKey(), null)
		assertFailsWith<IllegalArgumentException> { teams.nextTicketNumbers(team.id, -1) }
	}
}

/**
 * Not `@Transactional`: the allocation is only serialised by a real row lock, so the
 * threads need real committed transactions to contend for it.
 */
class TicketNumberBlockConcurrencyTest : PostgresTest() {

	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var tx: TransactionTemplate

	@Test
	fun `blocks stay contiguous while other threads allocate`() {
		val team = tx.execute {
			teams.insert("Blocks", "BLK${UUID.randomUUID().toString().take(4).uppercase()}", null)
		}!!
		val threads = 8
		val blocksPerThread = 5
		val blockSize = 4
		val expected = threads * blocksPerThread * blockSize

		val pool = Executors.newFixedThreadPool(threads)
		try {
			// Half the threads take blocks, half take one number at a time — the two
			// paths share the counter and must not tread on each other.
			val tasks = List(threads) { index ->
				Callable {
					if (index % 2 == 0) {
						List(blocksPerThread) { tx.execute { teams.nextTicketNumbers(team.id, blockSize) }!! }
					} else {
						List(blocksPerThread * blockSize) { listOf(tx.execute { teams.nextTicketNumber(team.id) }!!) }
					}
				}
			}
			val blocks = pool.invokeAll(tasks).flatMap { it.get(30, TimeUnit.SECONDS) }
			val allocated = blocks.flatten()

			assertEquals(expected, allocated.size)
			assertEquals(
				(1..expected).toSet(),
				allocated.toSet(),
				"every number from 1..N should be handed out exactly once",
			)
			blocks.forEach { block ->
				assertEquals(
					(block.first() until block.first() + block.size).toList(),
					block,
					"a block must be contiguous: no other thread may take a number out of its middle",
				)
			}
		} finally {
			pool.shutdownNow()
			tx.executeWithoutResult { teams.delete(team.id) }
		}
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.repo.TicketNumberBlockTest" --tests "dev.kanso.repo.TicketNumberBlockConcurrencyTest"`

Expected: FAIL — the test source does not compile: `Unresolved reference 'nextTicketNumbers'`.

- [ ] **Step 3: Add `nextTicketNumbers` to `TeamRepository`**

In `apps/api/src/main/kotlin/dev/kanso/repo/TeamRepository.kt`, immediately after
`nextTicketNumber` (currently ends line 105), add:

```kotlin
	/**
	 * Reserves [count] consecutive numbers in one statement and returns them in order.
	 *
	 * One statement rather than [count] of them: the row lock is held for the same
	 * span either way, so the loop would only add a round trip per ticket inside it —
	 * time every concurrent "new ticket" in this team spends waiting.
	 */
	fun nextTicketNumbers(teamId: UUID, count: Int): List<Int> {
		require(count >= 0) { "Cannot reserve a negative number of ticket numbers ($count)" }
		if (count == 0) return emptyList()
		val last = jdbc.sql(
			"UPDATE teams SET ticket_counter = ticket_counter + :count WHERE id = :id RETURNING ticket_counter"
		).param("count", count).param("id", teamId).query(Int::class.java).single()
		return ((last - count + 1)..last).toList()
	}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.repo.TicketNumberBlockTest" --tests "dev.kanso.repo.TicketNumberBlockConcurrencyTest"`

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/repo/TeamRepository.kt \
        apps/api/src/test/kotlin/dev/kanso/repo/TicketNumberBlockTest.kt
git commit -m "feat(api): reserve ticket numbers a block at a time"
```

---

### Task 3: The disposition model, and counting what is held

Archiving and deleting ask the same question — what happens to what this thing holds? —
so both take one shape, a **disposition plan**. This task introduces the vocabulary
(`DispositionChoice`, `DispositionCounts`, `DispositionPlan`), the error a drifted count
raises, and the read endpoints that fill the modal. Nothing acts on a plan yet; tasks 4
to 6 do.

`DispositionCounts` describes what a team holds **directly**: its own sub-teams, its own
projects, its own tickets. A kept sub-team leaves with its own contents untouched, so
counting those here would describe a disposition nobody was offered.

`domain/Model.kt` needs one word changed: its `parse` helper is `private`, which in Kotlin
means *file*-private, so a new file in the same package cannot call it.

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/domain/Disposition.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/service/DispositionCountsTest.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/domain/Model.kt:16`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/Errors.kt:1-9`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/ApiExceptionHandler.kt:1-18,35-36`
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TeamRepository.kt` (after `nextTicketNumbers`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt:118`
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/ProjectRepository.kt:88`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt:19-37`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/ProjectService.kt:24-46`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt:32,101`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt` (after `descendants`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/ProjectController.kt:23-24`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/DispositionCountsTest.kt`

**Interfaces:**
- Consumes: `TeamService.create(actor, name, key, parentTeamId)` (task 1);
  `dev.kanso.domain.Wire` and the file-private `parse` helper in `domain/Model.kt`;
  `ProjectService.create(...)`, `TicketService.create(...)`.
- Produces:
  - `dev.kanso.domain.DispositionChoice { TAKE("take"), KEEP("keep") }` with `from(raw: String)`
  - `dev.kanso.domain.DispositionCounts(subTeams: Int, projects: Int, tickets: Int)`
  - `dev.kanso.domain.DispositionPlan(subTeams, projects, tickets, ticketsTargetTeamId, counts)`
  - `dev.kanso.service.CountsChangedException(counts: DispositionCounts)` → HTTP 409 with a `counts` property
  - `TeamRepository.directChildIds(id: UUID): List<UUID>`
  - `TicketRepository.countByTeams(teamIds: Collection<UUID>, includeArchived: Boolean = true): Int`
  - `TicketRepository.countByProject(projectId: UUID, includeArchived: Boolean = true): Int`
  - `ProjectRepository.countByTeams(teamIds: Collection<UUID>, includeArchived: Boolean = true): Int`
  - `TeamService.contents(id: UUID): DispositionCounts` and private `countsOf(id: UUID)`
  - `ProjectService.contents(id: UUID): DispositionCounts`
  - `api.DispositionCountsResponse`, `api.DispositionPlanRequest` with `toPlan(): DispositionPlan`
  - `GET /api/teams/{id}/contents`, `GET /api/projects/{id}/contents`

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/service/DispositionCountsTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Transactional
class DispositionCountsTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "counts-${UUID.randomUUID()}@kanso.test",
			displayName = "Counts admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun key() = "C${UUID.randomUUID().toString().take(5).uppercase()}"

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		startDate = null,
		endDate = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	)

	private fun newTicket(teamId: UUID, projectId: UUID? = null) = tickets.create(
		teamId = teamId,
		title = "Ticket ${UUID.randomUUID().toString().take(4)}",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		startDate = null,
		dueDate = null,
		projectId = projectId,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `a team counts what it holds directly, not what its sub-teams hold`() {
		val core = teams.create(admin, "Core", key(), null)
		val mobile = teams.create(admin, "Mobile", key(), core.id)
		teams.create(admin, "iOS", key(), mobile.id)
		newProject(core.id)
		newProject(mobile.id)
		newTicket(core.id)
		newTicket(core.id)
		newTicket(mobile.id)

		assertEquals(
			DispositionCounts(subTeams = 1, projects = 1, tickets = 2),
			teams.contents(core.id),
			"a kept sub-team leaves with its own contents, so they are not Core's to dispose of",
		)
	}

	@Test
	fun `an archived ticket still has to be disposed of, so it still counts`() {
		val team = teams.create(admin, "Core", key(), null)
		val ticket = newTicket(team.id)
		tickets.patch(ticket.ticket.id, TicketPatch(archived = true))

		assertEquals(1, teams.contents(team.id).tickets)
	}

	@Test
	fun `a project counts only its tickets`() {
		val team = teams.create(admin, "Core", key(), null)
		val project = newProject(team.id)
		newTicket(team.id, project.project.id)
		newTicket(team.id, project.project.id)
		newTicket(team.id)

		assertEquals(
			DispositionCounts(subTeams = 0, projects = 0, tickets = 2),
			projects.contents(project.project.id),
			"a project holds no teams and no projects; only its tickets need a decision",
		)
	}

	@Test
	fun `counting something that does not exist is a 404, not a zero`() {
		assertFailsWith<NotFoundException> { teams.contents(UUID.randomUUID()) }
		assertFailsWith<NotFoundException> { projects.contents(UUID.randomUUID()) }
	}

	@Test
	fun `a disposition choice parses from the wire and refuses anything else`() {
		assertEquals(DispositionChoice.TAKE, DispositionChoice.from("take"))
		assertEquals(DispositionChoice.KEEP, DispositionChoice.from("keep"))
		assertFailsWith<IllegalArgumentException> { DispositionChoice.from("destroy") }
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.DispositionCountsTest"`

Expected: FAIL — the test source does not compile:
`Unresolved reference 'DispositionChoice'`, `Unresolved reference 'DispositionCounts'`,
`Unresolved reference 'contents'`.

- [ ] **Step 3: Open the enum parser to the rest of the package**

In `apps/api/src/main/kotlin/dev/kanso/domain/Model.kt` line 16, change one modifier — a
`private` top-level function is visible only inside its own file, and the new
`Disposition.kt` needs it:

```kotlin
internal inline fun <reified E> parse(values: Array<E>, raw: String): E where E : Enum<E>, E : Wire =
```

- [ ] **Step 4: Create the disposition model**

Create `apps/api/src/main/kotlin/dev/kanso/domain/Disposition.kt`:

```kotlin
package dev.kanso.domain

import java.util.UUID

/** What happens to something a team or project holds when the container goes away. */
enum class DispositionChoice(override val wire: String) : Wire {
	/** Goes with the container: archived alongside it, or deleted with it. */
	TAKE("take"),

	/** Stays active, which always implies re-homing. */
	KEEP("keep");

	companion object {
		fun from(raw: String): DispositionChoice = parse(entries.toTypedArray(), raw)
	}
}

data class DispositionCounts(
	val subTeams: Int,
	val projects: Int,
	val tickets: Int,
)

/**
 * [counts] is what the modal displayed. When present it is compared against a fresh
 * count and a mismatch aborts the whole operation; deletion requires it, archiving
 * ignores it.
 */
data class DispositionPlan(
	val subTeams: DispositionChoice = DispositionChoice.KEEP,
	val projects: DispositionChoice = DispositionChoice.KEEP,
	val tickets: DispositionChoice = DispositionChoice.KEEP,
	val ticketsTargetTeamId: UUID? = null,
	val counts: DispositionCounts? = null,
)
```

- [ ] **Step 5: Add the drifted-count error and its HTTP shape**

Append to `apps/api/src/main/kotlin/dev/kanso/service/Errors.kt`:

```kotlin

/**
 * The contents changed between the preview the person saw and the request. Carries the
 * fresh counts so the modal can reopen on the truth.
 */
class CountsChangedException(val counts: dev.kanso.domain.DispositionCounts) :
	RuntimeException("The contents changed since they were counted")
```

In `apps/api/src/main/kotlin/dev/kanso/api/ApiExceptionHandler.kt`, add the import next to
the existing service imports:

```kotlin
import dev.kanso.service.CountsChangedException
```

and add this handler immediately after `conflict` (currently lines 35–36):

```kotlin
	/**
	 * A 409 that carries data: the client reopens the modal on the fresh numbers rather
	 * than asking the person to guess what changed.
	 */
	@ExceptionHandler(CountsChangedException::class)
	fun countsChanged(e: CountsChangedException): ProblemDetail {
		val problem = problem(HttpStatus.CONFLICT, e.message)
		problem.setProperty(
			"counts",
			mapOf(
				"subTeams" to e.counts.subTeams,
				"projects" to e.counts.projects,
				"tickets" to e.counts.tickets,
			),
		)
		return problem
	}
```

- [ ] **Step 6: Add the counting queries to the three repositories**

`apps/api/src/main/kotlin/dev/kanso/repo/TeamRepository.kt`, after `nextTicketNumbers`:

```kotlin
	/** Only the teams whose parent is [id] — the ones a plan can actually re-home. */
	fun directChildIds(id: UUID): List<UUID> =
		Teams.select(Teams.id).where { Teams.parentTeamId eq id }.map { it[Teams.id] }
```

`apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt`, after `delete` (line 118):

```kotlin
	// --- bulk reads ----------------------------------------------------------

	/** Archived tickets count: they still need a decision when their team goes away. */
	fun countByTeams(teamIds: Collection<UUID>, includeArchived: Boolean = true): Int {
		if (teamIds.isEmpty()) return 0
		val where =
			if (includeArchived) Tickets.teamId inList teamIds
			else (Tickets.teamId inList teamIds) and (Tickets.archived eq false)
		return Tickets.selectAll().where(where).count().toInt()
	}

	fun countByProject(projectId: UUID, includeArchived: Boolean = true): Int {
		val where =
			if (includeArchived) Tickets.projectId eq projectId
			else (Tickets.projectId eq projectId) and (Tickets.archived eq false)
		return Tickets.selectAll().where(where).count().toInt()
	}
```

`apps/api/src/main/kotlin/dev/kanso/repo/ProjectRepository.kt`, after `delete` (line 88):

```kotlin
	// --- bulk reads ----------------------------------------------------------

	fun countByTeams(teamIds: Collection<UUID>, includeArchived: Boolean = true): Int {
		if (teamIds.isEmpty()) return 0
		val where =
			if (includeArchived) Projects.teamId inList teamIds
			else (Projects.teamId inList teamIds) and (Projects.archived eq false)
		return Projects.selectAll().where(where).count().toInt()
	}
```

- [ ] **Step 7: Add `contents` to both services**

In `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt`, add imports:

```kotlin
import dev.kanso.domain.DispositionCounts
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TicketRepository
```

and widen the constructor (currently lines 20–25) — a team's disposition is about what it
holds, so the service needs to see it:

```kotlin
@Service
class TeamService(
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val tickets: TicketRepository,
	private val users: UserRepository,
	private val syncJobs: SyncJobRepository,
	private val events: EventPublisher,
) {
```

Add, just after `descendants` (currently ends line 37):

```kotlin
	// --- disposition ---------------------------------------------------------

	@Transactional(readOnly = true)
	fun contents(id: UUID): DispositionCounts {
		get(id)
		return countsOf(id)
	}

	/**
	 * What the team holds directly. A kept sub-team leaves with its own projects and
	 * tickets untouched, so counting those would describe a decision nobody was
	 * offered.
	 */
	private fun countsOf(id: UUID) = DispositionCounts(
		subTeams = teams.directChildIds(id).size,
		projects = projects.countByTeams(listOf(id)),
		tickets = tickets.countByTeams(listOf(id)),
	)
```

In `apps/api/src/main/kotlin/dev/kanso/service/ProjectService.kt`, add imports:

```kotlin
import dev.kanso.domain.DispositionCounts
import dev.kanso.repo.TicketRepository
```

add `tickets` to the constructor (currently lines 25–32):

```kotlin
@Service
class ProjectService(
	private val projects: ProjectRepository,
	private val teams: TeamRepository,
	private val tickets: TicketRepository,
	private val users: UserRepository,
	private val docs: DocRepository,
	private val syncJobs: SyncJobRepository,
	private val events: EventPublisher,
) {
```

and add, just after `get` (currently ends line 46):

```kotlin
	// --- disposition ---------------------------------------------------------

	/** A project holds no teams and no projects; only its tickets need a decision. */
	@Transactional(readOnly = true)
	fun contents(id: UUID): DispositionCounts {
		get(id)
		return DispositionCounts(subTeams = 0, projects = 0, tickets = tickets.countByProject(id))
	}
```

- [ ] **Step 8: Add the wire types and the two read endpoints**

In `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt`, add the imports:

```kotlin
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.DispositionPlan
```

and insert this section just before the `// --- teams ---` banner (currently line 32):

```kotlin
// --- disposition -------------------------------------------------------------

data class DispositionCountsResponse(val subTeams: Int, val projects: Int, val tickets: Int) {
	companion object {
		fun of(counts: DispositionCounts) =
			DispositionCountsResponse(counts.subTeams, counts.projects, counts.tickets)
	}
}

/**
 * What happens to the contents. Every category defaults to `keep`: the destructive
 * reading of a missing field is never the safe one.
 */
data class DispositionPlanRequest(
	val subTeams: String = "keep",
	val projects: String = "keep",
	val tickets: String = "keep",
	val ticketsTargetTeamId: UUID? = null,
	val counts: DispositionCountsResponse? = null,
) {
	fun toPlan() = DispositionPlan(
		subTeams = DispositionChoice.from(subTeams),
		projects = DispositionChoice.from(projects),
		tickets = DispositionChoice.from(tickets),
		ticketsTargetTeamId = ticketsTargetTeamId,
		counts = counts?.let { DispositionCounts(it.subTeams, it.projects, it.tickets) },
	)
}

```

In `apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt`, add after `descendants`:

```kotlin
	/** What the modal shows before anyone chooses anything. */
	@GetMapping("/{id}/contents")
	fun contents(@PathVariable id: UUID): DispositionCountsResponse =
		DispositionCountsResponse.of(teams.contents(id))
```

In `apps/api/src/main/kotlin/dev/kanso/api/ProjectController.kt`, add after `get`
(currently line 24):

```kotlin
	@GetMapping("/{id}/contents")
	fun contents(@PathVariable id: UUID): DispositionCountsResponse =
		DispositionCountsResponse.of(projects.contents(id))
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.DispositionCountsTest"`

Expected: PASS, 5 tests.

- [ ] **Step 10: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/domain/Disposition.kt \
        apps/api/src/main/kotlin/dev/kanso/domain/Model.kt \
        apps/api/src/main/kotlin/dev/kanso/service/Errors.kt \
        apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt \
        apps/api/src/main/kotlin/dev/kanso/service/ProjectService.kt \
        apps/api/src/main/kotlin/dev/kanso/repo/TeamRepository.kt \
        apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt \
        apps/api/src/main/kotlin/dev/kanso/repo/ProjectRepository.kt \
        apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt \
        apps/api/src/main/kotlin/dev/kanso/api/ApiExceptionHandler.kt \
        apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt \
        apps/api/src/main/kotlin/dev/kanso/api/ProjectController.kt \
        apps/api/src/test/kotlin/dev/kanso/service/DispositionCountsTest.kt
git commit -m "feat(api): add the disposition plan and count what a team or project holds"
```

---

### Task 4: Archive a team under a plan

`archive` empties the team of everything the plan keeps, then archives what is left. The
plan's `projects` and `tickets` choices apply to every team that is going away — the team
itself, plus its whole subtree when `subTeams = TAKE`. Sub-teams that are **kept** leave
first, with their own projects and tickets untouched.

**Invariant: an unarchived team never has an archived ancestor.** `keep` upholds it by
re-parenting the sub-team out to the grandparent; `take` upholds it by archiving the
subtree; `unarchive` upholds it by unarchiving the ancestors; and `update` now refuses to
move a live team under an archived one.

Everything is validated before anything is written. A plan refused half-way would leave the
team stripped of what had already been re-homed.

`update` loses its `archived` parameter here, and `TeamRequest` loses its `archived` field:
archiving now has its own verb.

**Files:**
- Create: `apps/api/src/test/kotlin/dev/kanso/service/TeamArchiveTest.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TeamRepository.kt` (after `directChildIds`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt` (in `// --- bulk reads`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/ProjectRepository.kt` (in `// --- bulk reads`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt` (`update`, `contents` section)
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt` (`TeamRequest`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt` (`update`, new verbs)
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TicketWorkflowTest.kt` (drop `archived` from the `update` call)

**Interfaces:**
- Consumes: `TeamRepository.nextTicketNumbers` (task 2); `DispositionPlan`,
  `DispositionChoice`, `TeamService.countsOf`, `TeamService.requireConfigurator` (tasks 1, 3).
- Produces:
  - `TeamRepository.ancestorIds(id: UUID): List<UUID>` — nearest first, excludes `id`
  - `TeamRepository.setParent(id: UUID, parentTeamId: UUID?): Boolean`
  - `TeamRepository.setArchived(ids: Collection<UUID>, archived: Boolean): Int`
  - `TicketRepository.idsByTeam(teamId: UUID): List<UUID>`
  - `TicketRepository.moveToTeam(ticketId: UUID, teamId: UUID, number: Int): Boolean`
  - `TicketRepository.setArchivedByTeams(teamIds: Collection<UUID>, archived: Boolean): List<UUID>`
  - `ProjectRepository.idsByTeam(teamId: UUID): List<UUID>`
  - `ProjectRepository.setTeam(projectId: UUID, teamId: UUID?): Boolean`
  - `ProjectRepository.setArchivedByTeams(teamIds: Collection<UUID>, archived: Boolean): List<UUID>`
  - `TeamService.archive(actor: User, id: UUID, plan: DispositionPlan): Team`
  - `TeamService.unarchive(actor: User, id: UUID): Team`
  - `TeamService.update(actor: User, id: UUID, name: String, key: String, parentTeamId: UUID?): Team`
  - private `TeamService.requireTicketDestination(team: Team, plan: DispositionPlan): UUID?`
    and `TeamService.disperse(...)` — task 5 generalises both
  - `PUT /api/teams/{id}/archive`, `POST /api/teams/{id}/unarchive`

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/service/TeamArchiveTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.SyncOperation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class TeamArchiveTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole): User = users.createLocalUser(
		email = "arch-${UUID.randomUUID()}@kanso.test",
		displayName = "Archive ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "A${UUID.randomUUID().toString().take(5).uppercase()}"

	private fun newTeam(name: String, parentId: UUID? = null) = teams.create(admin, name, key(), parentId)

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		startDate = null,
		endDate = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun newTicket(teamId: UUID, title: String = "Ticket") = tickets.create(
		teamId = teamId,
		title = title,
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		startDate = null,
		dueDate = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `a member cannot archive a team`() {
		val team = newTeam("Core")
		assertFailsWith<AccessDeniedException> {
			teams.archive(user(InstanceRole.MEMBER), team.id, DispositionPlan())
		}
	}

	@Test
	fun `taking the sub-teams archives the whole subtree`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)

		teams.archive(admin, core.id, DispositionPlan(subTeams = DispositionChoice.TAKE))

		assertTrue(teams.get(core.id).archived)
		assertTrue(teams.get(mobile.id).archived, "a taken sub-team goes with its parent")
		assertTrue(teams.get(ios.id).archived, "and so does everything under it")
	}

	@Test
	fun `keeping the sub-teams re-homes them to the grandparent, not to the root`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)

		teams.archive(admin, mobile.id, DispositionPlan(subTeams = DispositionChoice.KEEP))

		assertTrue(teams.get(mobile.id).archived)
		assertFalse(teams.get(ios.id).archived, "a kept sub-team stays active")
		assertEquals(core.id, teams.get(ios.id).parentTeamId, "ON DELETE SET NULL would have said root")
	}

	@Test
	fun `keeping the projects sends them to the parent team, or to no team at the root`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val nested = newProject(mobile.id)
		val rooted = newProject(core.id)

		teams.archive(admin, mobile.id, DispositionPlan(projects = DispositionChoice.KEEP))
		assertEquals(core.id, projects.get(nested.id).project.teamId)

		teams.archive(admin, core.id, DispositionPlan(projects = DispositionChoice.KEEP))
		assertNull(projects.get(rooted.id).project.teamId, "a root team has no parent to hand them to")
	}

	@Test
	fun `taking the projects archives them alongside the team`() {
		val core = newTeam("Core")
		val project = newProject(core.id)

		teams.archive(admin, core.id, DispositionPlan(projects = DispositionChoice.TAKE))

		assertTrue(projects.get(project.id).project.archived)
	}

	@Test
	fun `keeping the tickets renumbers them from the destination team's counter`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		newTicket(growth.id, "already there")
		val first = newTicket(core.id, "first")
		val second = newTicket(core.id, "second")

		teams.archive(
			admin,
			core.id,
			DispositionPlan(tickets = DispositionChoice.KEEP, ticketsTargetTeamId = growth.id),
		)

		val moved = listOf(first, second).map { tickets.get(it.ticket.id) }
		assertEquals(listOf(growth.id, growth.id), moved.map { it.ticket.teamId })
		assertEquals(
			listOf("${growth.key}-2", "${growth.key}-3"),
			moved.map { it.identifier },
			"the block continues the destination counter, leaving no gap and no collision",
		)
	}

	@Test
	fun `tickets of a kept sub-team keep their identifier`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val growth = newTeam("Growth")
		val untouched = newTicket(mobile.id)

		teams.archive(
			admin,
			core.id,
			DispositionPlan(
				subTeams = DispositionChoice.KEEP,
				tickets = DispositionChoice.KEEP,
				ticketsTargetTeamId = growth.id,
			),
		)

		val after = tickets.get(untouched.ticket.id)
		assertEquals(mobile.id, after.ticket.teamId)
		assertEquals(untouched.identifier, after.identifier, "nothing happens to them at all")
	}

	@Test
	fun `keeping tickets without a destination is refused and changes nothing`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		newTicket(core.id)

		assertFailsWith<BadRequestException> {
			teams.archive(admin, core.id, DispositionPlan(tickets = DispositionChoice.KEEP))
		}

		assertFalse(teams.get(core.id).archived, "a refused plan writes nothing at all")
		assertEquals(core.id, teams.get(mobile.id).parentTeamId, "not even the easy half of it")
	}

	@Test
	fun `the destination cannot be a team that is going away`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		newTicket(core.id)

		assertFailsWith<BadRequestException> {
			teams.archive(
				admin,
				core.id,
				DispositionPlan(
					subTeams = DispositionChoice.TAKE,
					tickets = DispositionChoice.KEEP,
					ticketsTargetTeamId = mobile.id,
				),
			)
		}
	}

	@Test
	fun `unarchiving a nested team unarchives its ancestors`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)
		teams.archive(admin, core.id, DispositionPlan(subTeams = DispositionChoice.TAKE))

		teams.unarchive(admin, ios.id)

		assertFalse(teams.get(ios.id).archived)
		assertFalse(teams.get(mobile.id).archived, "an unarchived team may not have an archived ancestor")
		assertFalse(teams.get(core.id).archived)
	}

	@Test
	fun `a live team cannot be moved under an archived one`() {
		val archived = newTeam("Archived")
		val live = newTeam("Live")
		teams.archive(admin, archived.id, DispositionPlan())

		assertFailsWith<ConflictException> {
			teams.update(admin, live.id, live.name, live.key, archived.id)
		}
	}

	@Test
	fun `one mirror push per entity touched, whichever plan ran`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val project = newProject(core.id)
		val ticket = newTicket(core.id)
		jobs.claimBatch(200, "drain")

		teams.archive(
			admin,
			core.id,
			DispositionPlan(
				subTeams = DispositionChoice.TAKE,
				projects = DispositionChoice.TAKE,
				tickets = DispositionChoice.TAKE,
			),
		)

		val queued = jobs.claimBatch(200, "test")
		assertEquals(1, queued.count { it.entityId == core.id })
		assertEquals(1, queued.count { it.entityId == mobile.id })
		assertEquals(SyncOperation.ARCHIVE, queued.single { it.entityId == project.id }.operation)
		assertEquals(SyncOperation.ARCHIVE, queued.single { it.entityId == ticket.ticket.id }.operation)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.TeamArchiveTest"`

Expected: FAIL — the test source does not compile: `Unresolved reference 'archive'`,
`Unresolved reference 'unarchive'`, and `No value passed for parameter 'archived'` on the
`teams.update(admin, live.id, live.name, live.key, archived.id)` call.

- [ ] **Step 3: Add the team-shape queries to `TeamRepository`**

In `apps/api/src/main/kotlin/dev/kanso/repo/TeamRepository.kt`, after `directChildIds`:

```kotlin
	/**
	 * The chain above [id], nearest first, excluding [id] itself.
	 *
	 * Raw SQL for the same reason [descendantIds] is: Exposed has no `WITH RECURSIVE`.
	 * Cycles are impossible — [wouldCreateCycle] refuses them on the way in — so the
	 * walk always terminates at a root.
	 */
	fun ancestorIds(id: UUID): List<UUID> = jdbc.sql(
		"""
		WITH RECURSIVE ancestors AS (
		    SELECT parent_team_id AS id, 1 AS depth
		      FROM teams WHERE id = :id AND parent_team_id IS NOT NULL
		    UNION ALL
		    SELECT t.parent_team_id, a.depth + 1
		      FROM teams t JOIN ancestors a ON t.id = a.id
		     WHERE t.parent_team_id IS NOT NULL
		)
		SELECT id FROM ancestors ORDER BY depth
		""".trimIndent()
	).param("id", id).query(UUID::class.java).list().filterNotNull()

	fun setParent(id: UUID, parentTeamId: UUID?): Boolean =
		Teams.update({ Teams.id eq id }) { it[Teams.parentTeamId] = parentTeamId } > 0

	fun setArchived(ids: Collection<UUID>, archived: Boolean): Int =
		if (ids.isEmpty()) 0
		else Teams.update({ Teams.id inList ids }) { it[Teams.archived] = archived }
```

- [ ] **Step 4: Add the bulk ticket and project writes**

In `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt`, inside the
`// --- bulk reads` section added in task 3, after `countByProject`:

```kotlin
	fun idsByTeam(teamId: UUID): List<UUID> =
		Tickets.select(Tickets.id).where { Tickets.teamId eq teamId }
			.orderBy(Tickets.number to SortOrder.ASC)
			.map { it[Tickets.id] }

	/** A move renames the ticket for good: `UNIQUE (team_id, number)` leaves no choice. */
	fun moveToTeam(ticketId: UUID, teamId: UUID, number: Int): Boolean =
		Tickets.update({ Tickets.id eq ticketId }) {
			it[Tickets.teamId] = teamId
			it[Tickets.number] = number
		} > 0

	/** Returns only the rows that actually changed — the others need no mirror push. */
	fun setArchivedByTeams(teamIds: Collection<UUID>, archived: Boolean): List<UUID> {
		if (teamIds.isEmpty()) return emptyList()
		val ids = Tickets.select(Tickets.id)
			.where { (Tickets.teamId inList teamIds) and (Tickets.archived neq archived) }
			.map { it[Tickets.id] }
		if (ids.isNotEmpty()) Tickets.update({ Tickets.id inList ids }) { it[Tickets.archived] = archived }
		return ids
	}
```

In `apps/api/src/main/kotlin/dev/kanso/repo/ProjectRepository.kt`, after `countByTeams`:

```kotlin
	fun idsByTeam(teamId: UUID): List<UUID> =
		Projects.select(Projects.id).where { Projects.teamId eq teamId }.map { it[Projects.id] }

	/** Null is a real destination: a project with no team is the transverse case. */
	fun setTeam(projectId: UUID, teamId: UUID?): Boolean =
		Projects.update({ Projects.id eq projectId }) { it[Projects.teamId] = teamId } > 0

	fun setArchivedByTeams(teamIds: Collection<UUID>, archived: Boolean): List<UUID> {
		if (teamIds.isEmpty()) return emptyList()
		val ids = Projects.select(Projects.id)
			.where { (Projects.teamId inList teamIds) and (Projects.archived neq archived) }
			.map { it[Projects.id] }
		if (ids.isNotEmpty()) Projects.update({ Projects.id inList ids }) { it[Projects.archived] = archived }
		return ids
	}
```

- [ ] **Step 5: Teach `update` the invariant and drop its `archived` flag**

In `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt`, replace the whole `update`
function with:

```kotlin
	@Transactional
	fun update(actor: User, id: UUID, name: String, key: String, parentTeamId: UUID?): Team {
		requireConfigurator(actor)
		val existing = get(id)
		if (parentTeamId != existing.parentTeamId) {
			if (parentTeamId == id) throw ConflictException("A team cannot be its own parent")
			val parent = parentTeamId?.let {
				teams.findById(it) ?: throw BadRequestException("Parent team $it does not exist")
			}
			if (teams.wouldCreateCycle(id, parentTeamId)) {
				throw ConflictException("Moving team $id under $parentTeamId would create a cycle")
			}
			// An unarchived team never has an archived ancestor: it would be invisible
			// in every view while still counting as live work.
			if (parent != null && parent.archived && !existing.archived) {
				throw ConflictException("Team ${parent.id} is archived; unarchive it before moving a team under it")
			}
		}
		if (key != existing.key) validateKey(key)

		val updated = teams.update(id, name, key, parentTeamId, existing.archived)
			?: throw NotFoundException("No team $id")
		syncJobs.enqueue(SyncEntityType.TEAM, id, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.UPDATED, id))
		return updated
	}
```

- [ ] **Step 6: Add `archive`, `unarchive` and the dispersal helpers**

Add these imports to `TeamService.kt`:

```kotlin
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionPlan
```

Add, at the end of the `// --- disposition ---` section (just after `countsOf`):

```kotlin
	/**
	 * Removes the team from view after deciding what happens to what it holds.
	 *
	 * The plan's `projects` and `tickets` choices apply to every team going away — this
	 * one, plus its whole subtree when its sub-teams are taken. Kept sub-teams leave
	 * first, with their own contents untouched.
	 */
	@Transactional
	fun archive(actor: User, id: UUID, plan: DispositionPlan): Team {
		requireConfigurator(actor)
		val team = get(id)
		val ticketsTarget = requireTicketDestination(team, plan)

		val doomed = disperse(team, plan, ticketsTarget)
		teams.setArchived(doomed, true)
		doomed.forEach {
			syncJobs.enqueue(SyncEntityType.TEAM, it, SyncOperation.ARCHIVE)
			events.publish(KansoEvent.team(ChangeKind.UPDATED, it))
		}
		return get(id)
	}

	/**
	 * Brings the team back, and its ancestors with it: a subtree archived together has
	 * to be restorable from any point in it rather than walked by hand, and a live team
	 * under an archived one is invisible anyway.
	 */
	@Transactional
	fun unarchive(actor: User, id: UUID): Team {
		requireConfigurator(actor)
		get(id)
		val chain = listOf(id) + teams.ancestorIds(id)
		val restored = teams.findAllById(chain).filter { it.archived }.map { it.id }
		teams.setArchived(restored, false)
		restored.forEach {
			syncJobs.enqueue(SyncEntityType.TEAM, it, SyncOperation.UPSERT)
			events.publish(KansoEvent.team(ChangeKind.UPDATED, it))
		}
		return get(id)
	}

	/**
	 * Validated before a single row is written. A plan refused half-way would leave the
	 * team stripped of whatever had already been re-homed, with nothing to undo it.
	 *
	 * Returns the destination when there is one to check, null when there is nothing to
	 * move.
	 */
	private fun requireTicketDestination(team: Team, plan: DispositionPlan): UUID? {
		if (plan.tickets != DispositionChoice.KEEP) return null
		val doomed =
			if (plan.subTeams == DispositionChoice.TAKE) teams.descendantIds(team.id) else listOf(team.id)
		if (tickets.countByTeams(doomed) == 0) return null

		val target = plan.ticketsTargetTeamId ?: throw BadRequestException(
			"Keeping the tickets of team ${team.id} needs a destination team: a ticket has no team-less state"
		)
		if (target in doomed) {
			throw BadRequestException("Team $target is being removed and cannot receive the tickets")
		}
		teams.findById(target) ?: throw BadRequestException("No team $target")
		return target
	}

	/**
	 * Empties the team of everything the plan keeps and returns the teams that are
	 * actually going away — this one alone when its sub-teams were kept, the whole
	 * subtree when they were taken.
	 */
	private fun disperse(team: Team, plan: DispositionPlan, ticketsTarget: UUID?): List<UUID> {
		val doomed = if (plan.subTeams == DispositionChoice.TAKE) {
			teams.descendantIds(team.id)
		} else {
			// Explicitly, rather than leaving it to ON DELETE SET NULL: the cascade
			// promotes them to the root, which is the wrong answer exactly when a
			// grandparent exists.
			teams.directChildIds(team.id).forEach { child ->
				teams.setParent(child, team.parentTeamId)
				syncJobs.enqueue(SyncEntityType.TEAM, child, SyncOperation.UPSERT)
				events.publish(KansoEvent.team(ChangeKind.UPDATED, child))
			}
			listOf(team.id)
		}

		disperseProjects(team, plan, doomed)
		disperseTickets(plan, doomed, ticketsTarget)
		return doomed
	}

	private fun disperseProjects(team: Team, plan: DispositionPlan, doomed: List<UUID>) {
		val held = projects.findAllById(doomed.flatMap { projects.idsByTeam(it) })
		if (held.isEmpty()) return

		if (plan.projects == DispositionChoice.KEEP) {
			held.forEach {
				projects.setTeam(it.id, team.parentTeamId)
				syncJobs.enqueue(SyncEntityType.PROJECT, it.id, SyncOperation.UPSERT)
				events.publish(KansoEvent.project(ChangeKind.UPDATED, it.id, team.parentTeamId))
			}
			return
		}

		val teamOf = held.associate { it.id to it.teamId }
		projects.setArchivedByTeams(doomed, true).forEach {
			syncJobs.enqueue(SyncEntityType.PROJECT, it, SyncOperation.ARCHIVE)
			events.publish(KansoEvent.project(ChangeKind.UPDATED, it, teamOf[it]))
		}
	}

	private fun disperseTickets(plan: DispositionPlan, doomed: List<UUID>, ticketsTarget: UUID?) {
		val held = tickets.search(teamIds = doomed, includeArchived = true, limit = Int.MAX_VALUE)
			.sortedWith(compareBy<Ticket>({ it.teamId }, { it.number }))
		if (held.isEmpty()) return

		if (plan.tickets == DispositionChoice.KEEP) {
			val target = checkNotNull(ticketsTarget) { "the destination is validated before dispersal" }
			// One statement for the whole block: the row lock on the destination team is
			// held for the same span either way, so allocating one number at a time would
			// only add a round trip per ticket inside it.
			val numbers = teams.nextTicketNumbers(target, held.size)
			held.forEachIndexed { index, ticket ->
				tickets.moveToTeam(ticket.id, target, numbers[index])
				syncJobs.enqueue(SyncEntityType.TICKET, ticket.id, SyncOperation.UPSERT)
				events.publish(KansoEvent.ticket(ChangeKind.UPDATED, ticket.id, target, ticket.projectId))
			}
			return
		}

		val byId = held.associateBy { it.id }
		tickets.setArchivedByTeams(doomed, true).forEach {
			syncJobs.enqueue(SyncEntityType.TICKET, it, SyncOperation.ARCHIVE)
			events.publish(KansoEvent.ticket(ChangeKind.UPDATED, it, byId[it]?.teamId, byId[it]?.projectId))
		}
	}
```

Add the one import those helpers need:

```kotlin
import dev.kanso.domain.Ticket
```

- [ ] **Step 7: Expose the two verbs and drop `archived` from the wire**

In `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt`, replace `TeamRequest` with:

```kotlin
data class TeamRequest(
	@field:NotBlank val name: String,
	/** Ticket prefix, e.g. `KAN`. Derived from the name when omitted. */
	@field:Size(min = 2, max = 8) val key: String? = null,
	val parentTeamId: UUID? = null,
)
```

In `apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt`, replace `update` with:

```kotlin
	@PutMapping("/{id}")
	fun update(@PathVariable id: UUID, @Valid @RequestBody request: TeamRequest): TeamResponse {
		val actor = currentUser.require()
		val current = teams.get(id)
		return TeamResponse.of(
			teams.update(
				actor = actor,
				id = id,
				name = request.name,
				key = request.key?.uppercase() ?: current.key,
				parentTeamId = request.parentTeamId,
			)
		)
	}

	@PutMapping("/{id}/archive")
	fun archive(@PathVariable id: UUID, @RequestBody request: DispositionPlanRequest): TeamResponse =
		TeamResponse.of(teams.archive(currentUser.require(), id, request.toPlan()))

	@PostMapping("/{id}/unarchive")
	fun unarchive(@PathVariable id: UUID): TeamResponse =
		TeamResponse.of(teams.unarchive(currentUser.require(), id))
```

- [ ] **Step 8: Fix the one workflow test that still passes `archived`**

In `apps/api/src/test/kotlin/dev/kanso/service/TicketWorkflowTest.kt`, in
`a team cannot be moved under its own descendant`:

```kotlin
		assertFailsWith<ConflictException> {
			teams.update(admin, root.id, root.name, root.key, child.id)
		}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.TeamArchiveTest" --tests "dev.kanso.service.TicketWorkflowTest"`

Expected: PASS, 12 + 9 tests.

- [ ] **Step 10: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/repo/TeamRepository.kt \
        apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt \
        apps/api/src/main/kotlin/dev/kanso/repo/ProjectRepository.kt \
        apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt \
        apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt \
        apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt \
        apps/api/src/test/kotlin/dev/kanso/service/TeamArchiveTest.kt \
        apps/api/src/test/kotlin/dev/kanso/service/TicketWorkflowTest.kt
git commit -m "feat(api): archive a team under a disposition plan"
```

---

### Task 5: Delete a team under a plan, and refuse a drifted count

Deleting takes the same plan as archiving; the dispersal helpers grow a `destructive`
flag and the three branches part only at the last statement.

One thing is new: **the count check**. Recounting inside the transaction keeps the
*operation* coherent, but it cannot keep a person's *consent* honest — someone who agreed
to destroy 47 tickets did not agree to destroy 50. Everywhere else in Kanso a write that
turns out wrong snaps back; here it does not come back at all, which is what buys the
extra round trip. Archiving snaps back, so it does not pay it: `archive` never looks at
`plan.counts`.

Order matters inside the transaction. Projects are disposed of before tickets, so a ticket
whose project was just deleted is read with its `project_id` already null rather than
carrying a stale one into its mirror push.

**Files:**
- Create: `apps/api/src/test/kotlin/dev/kanso/service/TeamDeleteTest.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt` (after `setArchivedByTeams`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/ProjectRepository.kt` (after `setArchivedByTeams`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt` (`delete`, `archive`, dispersal helpers)
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt` (`delete`)
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TeamDeleteTest.kt`

**Interfaces:**
- Consumes: `TeamService.requireTicketDestination`, `TeamService.disperse`,
  `TeamService.countsOf`, `TeamService.requireConfigurator`;
  `CountsChangedException(counts: DispositionCounts)`;
  `dev.kanso.sync.deletePayload(notionPageId: String?): String?`.
- Produces:
  - `TicketRepository.deleteByTeams(teamIds: Collection<UUID>): List<UUID>`
  - `ProjectRepository.deleteByTeams(teamIds: Collection<UUID>): List<UUID>`
  - `TeamService.delete(actor: User, id: UUID, plan: DispositionPlan)`
  - `DELETE /api/teams/{id}` taking a `DispositionPlanRequest` body
  - private `disperse(team, plan, ticketsTarget, destructive)` — the shape tasks 4 and 5 share

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/service/TeamDeleteTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.SyncOperation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class TeamDeleteTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole): User = users.createLocalUser(
		email = "del-${UUID.randomUUID()}@kanso.test",
		displayName = "Delete ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "D${UUID.randomUUID().toString().take(5).uppercase()}"

	private fun newTeam(name: String, parentId: UUID? = null) = teams.create(admin, name, key(), parentId)

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		startDate = null,
		endDate = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun newTicket(teamId: UUID) = tickets.create(
		teamId = teamId,
		title = "Ticket ${UUID.randomUUID().toString().take(4)}",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		startDate = null,
		dueDate = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `a member cannot delete a team`() {
		val team = newTeam("Core")
		assertFailsWith<AccessDeniedException> {
			teams.delete(user(InstanceRole.MEMBER), team.id, DispositionPlan(counts = teams.contents(team.id)))
		}
	}

	@Test
	fun `deleting without the counts the modal showed is refused`() {
		val team = newTeam("Core")
		val failure = assertFailsWith<BadRequestException> {
			teams.delete(admin, team.id, DispositionPlan())
		}
		assertTrue(failure.message!!.contains("counts"), failure.message!!)
	}

	@Test
	fun `keeping everything re-homes it and only the team disappears`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)
		val growth = newTeam("Growth")
		val project = newProject(mobile.id)
		val ticket = newTicket(mobile.id)

		teams.delete(
			admin,
			mobile.id,
			DispositionPlan(
				tickets = DispositionChoice.KEEP,
				ticketsTargetTeamId = growth.id,
				counts = teams.contents(mobile.id),
			),
		)

		assertFailsWith<NotFoundException> { teams.get(mobile.id) }
		assertEquals(core.id, teams.get(ios.id).parentTeamId, "the sub-team goes to the grandparent")
		assertEquals(core.id, projects.get(project.id).project.teamId, "the project goes to the parent")
		assertEquals("${growth.key}-1", tickets.get(ticket.ticket.id).identifier, "the ticket is renamed for good")
	}

	@Test
	fun `taking everything destroys the whole subtree and what it held`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val project = newProject(mobile.id)
		val ticket = newTicket(mobile.id)

		teams.delete(
			admin,
			core.id,
			DispositionPlan(
				subTeams = DispositionChoice.TAKE,
				projects = DispositionChoice.TAKE,
				tickets = DispositionChoice.TAKE,
				counts = teams.contents(core.id),
			),
		)

		assertFailsWith<NotFoundException> { teams.get(core.id) }
		assertFailsWith<NotFoundException> { teams.get(mobile.id) }
		assertFailsWith<NotFoundException> { projects.get(project.id) }
		assertFailsWith<NotFoundException> { tickets.get(ticket.ticket.id) }
	}

	@Test
	fun `a root team hands its kept projects to no team at all`() {
		val core = newTeam("Core")
		val project = newProject(core.id)

		teams.delete(admin, core.id, DispositionPlan(counts = teams.contents(core.id)))

		assertNull(projects.get(project.id).project.teamId)
	}

	@Test
	fun `a ticket created after the counts were read makes the delete fail with the fresh ones`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		newTicket(core.id)
		val stale = teams.contents(core.id)
		newTicket(core.id)

		val failure = assertFailsWith<CountsChangedException> {
			teams.delete(
				admin,
				core.id,
				DispositionPlan(
					tickets = DispositionChoice.KEEP,
					ticketsTargetTeamId = growth.id,
					counts = stale,
				),
			)
		}

		assertEquals(DispositionCounts(subTeams = 0, projects = 0, tickets = 2), failure.counts)
		assertEquals("Core", teams.get(core.id).name, "nothing happens; the modal reopens on the truth")

		// Replaying with the numbers the person can now actually see goes through.
		teams.delete(
			admin,
			core.id,
			DispositionPlan(
				tickets = DispositionChoice.KEEP,
				ticketsTargetTeamId = growth.id,
				counts = failure.counts,
			),
		)
		assertFailsWith<NotFoundException> { teams.get(core.id) }
	}

	@Test
	fun `the same drift lets an archive through`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		newTicket(core.id)
		val stale = teams.contents(core.id)
		newTicket(core.id)

		teams.archive(
			admin,
			core.id,
			DispositionPlan(
				tickets = DispositionChoice.KEEP,
				ticketsTargetTeamId = growth.id,
				counts = stale,
			),
		)

		assertTrue(teams.get(core.id).archived, "archiving is reversible, so it does not pay for consent")
		assertEquals(2, tickets.search(growth.id, false, null, emptyList(), null, true, 50, 0).size)
	}

	@Test
	fun `each destroyed entity gets its own delete job, not a silent cascade`() {
		val core = newTeam("Core")
		val project = newProject(core.id)
		val ticket = newTicket(core.id)
		jobs.claimBatch(200, "drain")

		teams.delete(
			admin,
			core.id,
			DispositionPlan(
				projects = DispositionChoice.TAKE,
				tickets = DispositionChoice.TAKE,
				counts = teams.contents(core.id),
			),
		)

		val queued = jobs.claimBatch(200, "test")
		assertEquals(SyncOperation.DELETE, queued.single { it.entityId == core.id }.operation)
		assertEquals(SyncOperation.DELETE, queued.single { it.entityId == project.id }.operation)
		assertEquals(
			SyncOperation.DELETE,
			queued.single { it.entityId == ticket.ticket.id }.operation,
			"ON DELETE CASCADE would have destroyed it in Postgres and left the Notion page behind",
		)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.TeamDeleteTest"`

Expected: FAIL — the test source does not compile:
`Too many arguments for 'fun delete(id: UUID): Unit'` on every `teams.delete(admin, …, plan)`
call, and `Unresolved reference 'CountsChangedException'` is resolved but
`No parameter with name 'counts'` never appears — the compiler stops at `delete`.

- [ ] **Step 3: Add the bulk deletes to the two repositories**

In `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt`, after
`setArchivedByTeams`:

```kotlin
	/**
	 * Returns what it removed. `tickets.team_id` is ON DELETE CASCADE, so dropping the
	 * team would take these with it in Postgres while leaving the Notion page behind —
	 * the mirror outliving the source of truth. Removing them here means one sync job
	 * each.
	 */
	fun deleteByTeams(teamIds: Collection<UUID>): List<UUID> {
		if (teamIds.isEmpty()) return emptyList()
		val ids = Tickets.select(Tickets.id).where { Tickets.teamId inList teamIds }.map { it[Tickets.id] }
		if (ids.isNotEmpty()) Tickets.deleteWhere { Tickets.id inList ids }
		return ids
	}
```

In `apps/api/src/main/kotlin/dev/kanso/repo/ProjectRepository.kt`, after
`setArchivedByTeams`:

```kotlin
	fun deleteByTeams(teamIds: Collection<UUID>): List<UUID> {
		if (teamIds.isEmpty()) return emptyList()
		val ids = Projects.select(Projects.id).where { Projects.teamId inList teamIds }.map { it[Projects.id] }
		if (ids.isNotEmpty()) Projects.deleteWhere { Projects.id inList ids }
		return ids
	}
```

- [ ] **Step 4: Generalise the dispersal helpers to destruction**

In `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt`, replace the three private
helpers `disperse`, `disperseProjects` and `disperseTickets` written in task 4 with these.
Only the last branch of each is new; the `KEEP` branches are untouched.

```kotlin
	/**
	 * Empties the team of everything the plan keeps and returns the teams that are
	 * actually going away — this one alone when its sub-teams were kept, the whole
	 * subtree when they were taken.
	 *
	 * [destructive] is the only difference between archiving and deleting: `take` means
	 * archived-alongside in one case and deleted-with in the other.
	 */
	private fun disperse(
		team: Team,
		plan: DispositionPlan,
		ticketsTarget: UUID?,
		destructive: Boolean,
	): List<UUID> {
		val doomed = if (plan.subTeams == DispositionChoice.TAKE) {
			teams.descendantIds(team.id)
		} else {
			// Explicitly, rather than leaving it to ON DELETE SET NULL: the cascade
			// promotes them to the root, which is the wrong answer exactly when a
			// grandparent exists.
			teams.directChildIds(team.id).forEach { child ->
				teams.setParent(child, team.parentTeamId)
				syncJobs.enqueue(SyncEntityType.TEAM, child, SyncOperation.UPSERT)
				events.publish(KansoEvent.team(ChangeKind.UPDATED, child))
			}
			listOf(team.id)
		}

		// Projects first: a ticket whose project has just been deleted must be read with
		// its project_id already cleared, not carry a stale one into its mirror push.
		disperseProjects(team, plan, doomed, destructive)
		disperseTickets(plan, doomed, ticketsTarget, destructive)
		return doomed
	}

	private fun disperseProjects(
		team: Team,
		plan: DispositionPlan,
		doomed: List<UUID>,
		destructive: Boolean,
	) {
		val held = projects.findAllById(doomed.flatMap { projects.idsByTeam(it) })
		if (held.isEmpty()) return

		when {
			plan.projects == DispositionChoice.KEEP -> held.forEach {
				projects.setTeam(it.id, team.parentTeamId)
				syncJobs.enqueue(SyncEntityType.PROJECT, it.id, SyncOperation.UPSERT)
				events.publish(KansoEvent.project(ChangeKind.UPDATED, it.id, team.parentTeamId))
			}

			destructive -> {
				// Read before the delete: the job carries the Notion page id, and by the
				// time the worker runs there is no row left to look it up from.
				projects.deleteByTeams(doomed)
				held.forEach {
					syncJobs.enqueue(
						SyncEntityType.PROJECT,
						it.id,
						SyncOperation.DELETE,
						payload = deletePayload(it.mirror.notionPageId),
					)
					events.publish(KansoEvent.project(ChangeKind.DELETED, it.id, it.teamId))
				}
			}

			else -> {
				val teamOf = held.associate { it.id to it.teamId }
				projects.setArchivedByTeams(doomed, true).forEach {
					syncJobs.enqueue(SyncEntityType.PROJECT, it, SyncOperation.ARCHIVE)
					events.publish(KansoEvent.project(ChangeKind.UPDATED, it, teamOf[it]))
				}
			}
		}
	}

	private fun disperseTickets(
		plan: DispositionPlan,
		doomed: List<UUID>,
		ticketsTarget: UUID?,
		destructive: Boolean,
	) {
		val held = tickets.search(teamIds = doomed, includeArchived = true, limit = Int.MAX_VALUE)
			.sortedWith(compareBy<Ticket>({ it.teamId }, { it.number }))
		if (held.isEmpty()) return

		when {
			plan.tickets == DispositionChoice.KEEP -> {
				val target = checkNotNull(ticketsTarget) { "the destination is validated before dispersal" }
				// One statement for the whole block: the row lock on the destination team
				// is held for the same span either way, so allocating one number at a time
				// would only add a round trip per ticket inside it.
				val numbers = teams.nextTicketNumbers(target, held.size)
				held.forEachIndexed { index, ticket ->
					tickets.moveToTeam(ticket.id, target, numbers[index])
					syncJobs.enqueue(SyncEntityType.TICKET, ticket.id, SyncOperation.UPSERT)
					events.publish(KansoEvent.ticket(ChangeKind.UPDATED, ticket.id, target, ticket.projectId))
				}
			}

			destructive -> {
				tickets.deleteByTeams(doomed)
				held.forEach {
					syncJobs.enqueue(
						SyncEntityType.TICKET,
						it.id,
						SyncOperation.DELETE,
						payload = deletePayload(it.mirror.notionPageId),
					)
					events.publish(KansoEvent.ticket(ChangeKind.DELETED, it.id, it.teamId, it.projectId))
				}
			}

			else -> {
				val byId = held.associateBy { it.id }
				tickets.setArchivedByTeams(doomed, true).forEach {
					syncJobs.enqueue(SyncEntityType.TICKET, it, SyncOperation.ARCHIVE)
					events.publish(KansoEvent.ticket(ChangeKind.UPDATED, it, byId[it]?.teamId, byId[it]?.projectId))
				}
			}
		}
	}
```

Update the one call site in `archive` so it names the new parameter:

```kotlin
		val doomed = disperse(team, plan, ticketsTarget, destructive = false)
```

- [ ] **Step 5: Replace `delete` with the planned version**

In `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt`, replace the whole existing
`delete` function (the one with the "Deleting locally still archives in Notion" comment)
with:

```kotlin
	/**
	 * Removes the team for good, after checking that the person is still agreeing to
	 * what they were shown.
	 *
	 * Recounting inside the transaction keeps the *operation* coherent but cannot keep
	 * *consent* honest: whoever agreed to destroy 47 tickets did not agree to destroy
	 * 50. Everywhere else in Kanso a write that turns out wrong snaps back; this one
	 * does not come back at all, which is what buys the extra round trip. Archiving
	 * snaps back, so it ignores [DispositionPlan.counts] entirely.
	 *
	 * Deleting locally still archives in Notion: Notion has no hard delete worth
	 * relying on, and a page that silently disappears from the mirror is worse than one
	 * marked archived.
	 */
	@Transactional
	fun delete(actor: User, id: UUID, plan: DispositionPlan) {
		requireConfigurator(actor)
		val team = get(id)

		val declared = plan.counts
			?: throw BadRequestException("Deleting a team requires the counts the confirmation showed")
		val fresh = countsOf(id)
		if (declared != fresh) throw CountsChangedException(fresh)

		val ticketsTarget = requireTicketDestination(team, plan)
		val doomed = disperse(team, plan, ticketsTarget, destructive = true)

		val rows = teams.findAllById(doomed)
		rows.forEach {
			syncJobs.enqueue(
				SyncEntityType.TEAM,
				it.id,
				SyncOperation.DELETE,
				payload = deletePayload(it.mirror.notionPageId),
			)
		}
		// Order is irrelevant: parent_team_id is ON DELETE SET NULL, so no delete can
		// fail on a child that is still present.
		doomed.forEach { teams.delete(it) }
		rows.forEach { events.publish(KansoEvent.team(ChangeKind.DELETED, it.id)) }
	}
```

- [ ] **Step 6: Let the endpoint carry the plan**

In `apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt`, replace `delete` with:

```kotlin
	/**
	 * The body is optional at this layer so a request without one gets the service's
	 * own message about the missing counts rather than Spring's "required request body
	 * is missing".
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID, @RequestBody(required = false) request: DispositionPlanRequest?) =
		teams.delete(currentUser.require(), id, (request ?: DispositionPlanRequest()).toPlan())
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.TeamDeleteTest" --tests "dev.kanso.service.TeamArchiveTest"`

Expected: PASS, 8 + 12 tests.

- [ ] **Step 8: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt \
        apps/api/src/main/kotlin/dev/kanso/repo/ProjectRepository.kt \
        apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt \
        apps/api/src/main/kotlin/dev/kanso/api/TeamController.kt \
        apps/api/src/test/kotlin/dev/kanso/service/TeamDeleteTest.kt
git commit -m "feat(api): delete a team under a plan, refusing a drifted count"
```

---

### Task 6: Archive and delete projects under the same plan

The same component, one row. A project holds no teams and no projects, so only
`plan.tickets` is read: `TAKE` archives or deletes them, `KEEP` clears their `project_id`.
`ticketsTargetTeamId` is ignored — the tickets already have a team of their own, which is
exactly why keeping one costs nothing here.

No actor: projects are open to every member. Only the shape of the organisation is an
admin decision.

`ProjectService.update` loses its `archived` parameter, and `ProjectRequest` loses its
`archived` field, for the same reason teams did.

**Files:**
- Create: `apps/api/src/test/kotlin/dev/kanso/service/ProjectDispositionTest.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt` (after `deleteByTeams`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/ProjectService.kt:70-112`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt` (`ProjectRequest`)
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/ProjectController.kt:40-58`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/ProjectDispositionTest.kt`

**Interfaces:**
- Consumes: `DispositionPlan`, `DispositionChoice`, `DispositionCounts`,
  `CountsChangedException`, `ProjectService.contents(id)` (task 3);
  `TicketRepository.countByProject` (task 3); `deletePayload`.
- Produces:
  - `TicketRepository.idsByProject(projectId: UUID): List<UUID>`
  - `TicketRepository.clearProject(projectId: UUID): List<UUID>`
  - `TicketRepository.setArchivedByProject(projectId: UUID, archived: Boolean): List<UUID>`
  - `TicketRepository.deleteByProject(projectId: UUID): List<UUID>`
  - `ProjectService.archive(id: UUID, plan: DispositionPlan): ProjectDetail`
  - `ProjectService.unarchive(id: UUID): ProjectDetail`
  - `ProjectService.delete(id: UUID, plan: DispositionPlan)`
  - `ProjectService.update(id, name, status, startDate, endDate, leadUserId, teamId, docIds): ProjectDetail`
  - `PUT /api/projects/{id}/archive`, `POST /api/projects/{id}/unarchive`,
    `DELETE /api/projects/{id}` with a `DispositionPlanRequest` body

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/service/ProjectDispositionTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.SyncOperation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class ProjectDispositionTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "proj-${UUID.randomUUID()}@kanso.test",
			displayName = "Project admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun newTeam() =
		teams.create(admin, "Team ${UUID.randomUUID().toString().take(4)}", "P${UUID.randomUUID().toString().take(5).uppercase()}", null)

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		startDate = null,
		endDate = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun newTicket(teamId: UUID, projectId: UUID?) = tickets.create(
		teamId = teamId,
		title = "Ticket ${UUID.randomUUID().toString().take(4)}",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		startDate = null,
		dueDate = null,
		projectId = projectId,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `archiving with keep only detaches the tickets`() {
		val team = newTeam()
		val project = newProject(team.id)
		val ticket = newTicket(team.id, project.id)

		projects.archive(project.id, DispositionPlan(tickets = DispositionChoice.KEEP))

		assertTrue(projects.get(project.id).project.archived)
		val after = tickets.get(ticket.ticket.id)
		assertNull(after.ticket.projectId, "the ticket loses its project, not its life")
		assertFalse(after.ticket.archived)
		assertEquals(team.id, after.ticket.teamId, "and never its team")
	}

	@Test
	fun `archiving with take archives the tickets alongside it`() {
		val team = newTeam()
		val project = newProject(team.id)
		val ticket = newTicket(team.id, project.id)

		projects.archive(project.id, DispositionPlan(tickets = DispositionChoice.TAKE))

		val after = tickets.get(ticket.ticket.id)
		assertTrue(after.ticket.archived)
		assertEquals(project.id, after.ticket.projectId, "taken means it goes along, not that it is cut loose")
	}

	@Test
	fun `unarchiving brings the project back`() {
		val team = newTeam()
		val project = newProject(team.id)
		projects.archive(project.id, DispositionPlan())

		assertFalse(projects.unarchive(project.id).project.archived)
	}

	@Test
	fun `deleting with keep leaves the tickets behind, without their project`() {
		val team = newTeam()
		val project = newProject(team.id)
		val ticket = newTicket(team.id, project.id)

		projects.delete(project.id, DispositionPlan(counts = projects.contents(project.id)))

		assertFailsWith<NotFoundException> { projects.get(project.id) }
		assertNull(tickets.get(ticket.ticket.id).ticket.projectId)
	}

	@Test
	fun `deleting with take removes them, each with its own delete job`() {
		val team = newTeam()
		val project = newProject(team.id)
		val ticket = newTicket(team.id, project.id)
		jobs.claimBatch(200, "drain")

		projects.delete(
			project.id,
			DispositionPlan(tickets = DispositionChoice.TAKE, counts = projects.contents(project.id)),
		)

		assertFailsWith<NotFoundException> { tickets.get(ticket.ticket.id) }
		val queued = jobs.claimBatch(200, "test")
		assertEquals(SyncOperation.DELETE, queued.single { it.entityId == project.id }.operation)
		assertEquals(SyncOperation.DELETE, queued.single { it.entityId == ticket.ticket.id }.operation)
	}

	@Test
	fun `deleting a project on stale counts is refused, archiving it is not`() {
		val team = newTeam()
		val project = newProject(team.id)
		newTicket(team.id, project.id)
		val stale = projects.contents(project.id)
		newTicket(team.id, project.id)

		val failure = assertFailsWith<CountsChangedException> {
			projects.delete(project.id, DispositionPlan(counts = stale))
		}
		assertEquals(DispositionCounts(subTeams = 0, projects = 0, tickets = 2), failure.counts)

		projects.archive(project.id, DispositionPlan(counts = stale))
		assertTrue(projects.get(project.id).project.archived)
	}

	@Test
	fun `deleting without the counts the modal showed is refused`() {
		val project = newProject(newTeam().id)
		val failure = assertFailsWith<BadRequestException> { projects.delete(project.id, DispositionPlan()) }
		assertTrue(failure.message!!.contains("counts"), failure.message!!)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.ProjectDispositionTest"`

Expected: FAIL — the test source does not compile: `Unresolved reference 'archive'`,
`Unresolved reference 'unarchive'`, and
`Too many arguments for 'fun delete(id: UUID): Unit'`.

- [ ] **Step 3: Add the per-project ticket writes**

In `apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt`, after `deleteByTeams`:

```kotlin
	fun idsByProject(projectId: UUID): List<UUID> =
		Tickets.select(Tickets.id).where { Tickets.projectId eq projectId }.map { it[Tickets.id] }

	/** Keeping a ticket whose project goes away costs it only the grouping. */
	fun clearProject(projectId: UUID): List<UUID> {
		val ids = idsByProject(projectId)
		if (ids.isNotEmpty()) Tickets.update({ Tickets.id inList ids }) { it[Tickets.projectId] = null }
		return ids
	}

	fun setArchivedByProject(projectId: UUID, archived: Boolean): List<UUID> {
		val ids = Tickets.select(Tickets.id)
			.where { (Tickets.projectId eq projectId) and (Tickets.archived neq archived) }
			.map { it[Tickets.id] }
		if (ids.isNotEmpty()) Tickets.update({ Tickets.id inList ids }) { it[Tickets.archived] = archived }
		return ids
	}

	fun deleteByProject(projectId: UUID): List<UUID> {
		val ids = idsByProject(projectId)
		if (ids.isNotEmpty()) Tickets.deleteWhere { Tickets.id inList ids }
		return ids
	}
```

- [ ] **Step 4: Rework `ProjectService.update` and add the three verbs**

In `apps/api/src/main/kotlin/dev/kanso/service/ProjectService.kt`, add the imports:

```kotlin
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionPlan
```

Replace `update` (currently lines 70–99) with a version that no longer decides archiving:

```kotlin
	@Transactional
	fun update(
		id: UUID,
		name: String,
		status: ProjectStatus,
		startDate: LocalDate?,
		endDate: LocalDate?,
		leadUserId: UUID?,
		teamId: UUID?,
		docIds: List<UUID>?,
	): ProjectDetail {
		val existing = projects.findById(id) ?: throw NotFoundException("No project $id")
		validateDates(startDate, endDate)
		teamId?.let { requireTeam(it) }
		leadUserId?.let { requireUser(it) }
		docIds?.let { requireDocs(it) }

		// Archiving has its own verb; an edit never changes that flag by accident.
		val updated = projects.update(id, name, status, startDate, endDate, leadUserId, teamId, existing.archived)
			?: throw NotFoundException("No project $id")
		if (docIds != null) projects.setDocs(id, docIds)

		syncJobs.enqueue(SyncEntityType.PROJECT, id, SyncOperation.UPSERT)
		events.publish(KansoEvent.project(ChangeKind.UPDATED, id, teamId))
		return ProjectDetail(updated, projects.docIds(id))
	}
```

Replace `delete` (currently lines 101–112) with the three verbs and their helpers:

```kotlin
	@Transactional
	fun archive(id: UUID, plan: DispositionPlan): ProjectDetail {
		val project = projects.findById(id) ?: throw NotFoundException("No project $id")
		disperseTickets(id, plan, destructive = false)
		return setArchived(project, true)
	}

	/** Only the project comes back: its tickets were disposed of by an explicit choice. */
	@Transactional
	fun unarchive(id: UUID): ProjectDetail {
		val project = projects.findById(id) ?: throw NotFoundException("No project $id")
		return setArchived(project, false)
	}

	/**
	 * Deleting locally still archives in Notion, and still asks whether the counts on
	 * screen are the ones being agreed to — see `TeamService.delete` for why only the
	 * destructive side pays for that.
	 */
	@Transactional
	fun delete(id: UUID, plan: DispositionPlan) {
		val project = projects.findById(id) ?: throw NotFoundException("No project $id")

		val declared = plan.counts
			?: throw BadRequestException("Deleting a project requires the counts the confirmation showed")
		val fresh = DispositionCounts(subTeams = 0, projects = 0, tickets = tickets.countByProject(id))
		if (declared != fresh) throw CountsChangedException(fresh)

		disperseTickets(id, plan, destructive = true)
		syncJobs.enqueue(
			SyncEntityType.PROJECT,
			id,
			SyncOperation.DELETE,
			payload = deletePayload(project.mirror.notionPageId),
		)
		projects.delete(id)
		events.publish(KansoEvent.project(ChangeKind.DELETED, id, project.teamId))
	}

	/**
	 * `ticketsTargetTeamId` plays no part here: a ticket already has a team of its own,
	 * so keeping one costs it only its `project_id`. That is the whole difference
	 * between a project and a team.
	 */
	private fun disperseTickets(projectId: UUID, plan: DispositionPlan, destructive: Boolean) {
		val held = tickets.search(projectId = projectId, includeArchived = true, limit = Int.MAX_VALUE)
		if (held.isEmpty()) return
		val byId = held.associateBy { it.id }

		when {
			plan.tickets == DispositionChoice.KEEP -> tickets.clearProject(projectId).forEach {
				syncJobs.enqueue(SyncEntityType.TICKET, it, SyncOperation.UPSERT)
				events.publish(KansoEvent.ticket(ChangeKind.UPDATED, it, byId[it]?.teamId, null))
			}

			destructive -> {
				tickets.deleteByProject(projectId)
				held.forEach {
					syncJobs.enqueue(
						SyncEntityType.TICKET,
						it.id,
						SyncOperation.DELETE,
						payload = deletePayload(it.mirror.notionPageId),
					)
					events.publish(KansoEvent.ticket(ChangeKind.DELETED, it.id, it.teamId, projectId))
				}
			}

			else -> tickets.setArchivedByProject(projectId, true).forEach {
				syncJobs.enqueue(SyncEntityType.TICKET, it, SyncOperation.ARCHIVE)
				events.publish(KansoEvent.ticket(ChangeKind.UPDATED, it, byId[it]?.teamId, projectId))
			}
		}
	}

	private fun setArchived(project: Project, archived: Boolean): ProjectDetail {
		val updated = projects.update(
			id = project.id,
			name = project.name,
			status = project.status,
			startDate = project.startDate,
			endDate = project.endDate,
			leadUserId = project.leadUserId,
			teamId = project.teamId,
			archived = archived,
		) ?: throw NotFoundException("No project ${project.id}")
		syncJobs.enqueue(
			SyncEntityType.PROJECT,
			project.id,
			if (archived) SyncOperation.ARCHIVE else SyncOperation.UPSERT,
		)
		events.publish(KansoEvent.project(ChangeKind.UPDATED, project.id, project.teamId))
		return ProjectDetail(updated, projects.docIds(project.id))
	}
```

- [ ] **Step 5: Drop `archived` from the wire and expose the verbs**

In `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt`, replace `ProjectRequest` with:

```kotlin
data class ProjectRequest(
	@field:NotBlank val name: String,
	val status: String = ProjectStatus.PLANNED.wire,
	val startDate: LocalDate? = null,
	val endDate: LocalDate? = null,
	val leadUserId: UUID? = null,
	val teamId: UUID? = null,
	val docIds: List<UUID>? = null,
)
```

In `apps/api/src/main/kotlin/dev/kanso/api/ProjectController.kt`, replace `update` and
`delete` (currently lines 40–58) with:

```kotlin
	@PutMapping("/{id}")
	fun update(@PathVariable id: UUID, @Valid @RequestBody request: ProjectRequest): ProjectResponse =
		ProjectResponse.of(
			projects.update(
				id = id,
				name = request.name,
				status = ProjectStatus.from(request.status),
				startDate = request.startDate,
				endDate = request.endDate,
				leadUserId = request.leadUserId,
				teamId = request.teamId,
				docIds = request.docIds,
			)
		)

	@PutMapping("/{id}/archive")
	fun archive(@PathVariable id: UUID, @RequestBody request: DispositionPlanRequest): ProjectResponse =
		ProjectResponse.of(projects.archive(id, request.toPlan()))

	@PostMapping("/{id}/unarchive")
	fun unarchive(@PathVariable id: UUID): ProjectResponse = ProjectResponse.of(projects.unarchive(id))

	/** Body optional so a missing one yields the service's message, not Spring's. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID, @RequestBody(required = false) request: DispositionPlanRequest?) =
		projects.delete(id, (request ?: DispositionPlanRequest()).toPlan())
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.ProjectDispositionTest"`

Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/repo/TicketRepository.kt \
        apps/api/src/main/kotlin/dev/kanso/service/ProjectService.kt \
        apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt \
        apps/api/src/main/kotlin/dev/kanso/api/ProjectController.kt \
        apps/api/src/test/kotlin/dev/kanso/service/ProjectDispositionTest.kt
git commit -m "feat(api): archive and delete projects under a disposition plan"
```

---

### Task 7: A ticket's project must belong to its team

`TicketPatchRequest` accepts a `teamId`, so a ticket created in Core against a Core project
can be moved to Growth and keep pointing at a project no view of its team shows. The
composer's project list cannot prevent this — it only bounds creation.

`TicketService.patch` therefore clears `project_id` when the new team is not the project's
team **and** the project has a team at all. Not a database constraint: making it one would
also forbid the team-less projects this spec deliberately allows.

**Files:**
- Create: `apps/api/src/test/kotlin/dev/kanso/service/TicketProjectCoherenceTest.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt:150-154`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TicketProjectCoherenceTest.kt`

**Interfaces:**
- Consumes: `TeamService.create(actor, …)` (task 1); `ProjectService.create(…)`;
  `TicketService.patch(id: UUID, patch: TicketPatch): TicketDetail`;
  `ProjectRepository.findById(id: UUID): Project?`.
- Produces: no new signature — `TicketService.patch` keeps its shape and gains the rule.

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/kotlin/dev/kanso/service/TicketProjectCoherenceTest.kt`:

```kotlin
package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Transactional
class TicketProjectCoherenceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "coh-${UUID.randomUUID()}@kanso.test",
			displayName = "Coherence admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun newTeam(name: String) =
		teams.create(admin, name, "X${UUID.randomUUID().toString().take(5).uppercase()}", null)

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		startDate = null,
		endDate = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun newTicket(teamId: UUID, projectId: UUID?) = tickets.create(
		teamId = teamId,
		title = "Ticket ${UUID.randomUUID().toString().take(4)}",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		startDate = null,
		dueDate = null,
		projectId = projectId,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `moving a ticket to another team drops a project that belonged to the old one`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val moved = tickets.patch(ticket.ticket.id, TicketPatch(teamId = growth.id))

		assertEquals(growth.id, moved.ticket.teamId)
		assertNull(moved.ticket.projectId, "no view of Growth would ever have shown that project")
	}

	@Test
	fun `a team-less project survives the move`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val transverse = newProject(null)
		val ticket = newTicket(core.id, transverse.id)

		val moved = tickets.patch(ticket.ticket.id, TicketPatch(teamId = growth.id))

		assertEquals(transverse.id, moved.ticket.projectId, "a transverse project belongs to no team to leave")
	}

	@Test
	fun `moving a ticket inside its own team leaves its project alone`() {
		val core = newTeam("Core")
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val renamed = tickets.patch(ticket.ticket.id, TicketPatch(title = "Still grouped"))

		assertEquals(coreProject.id, renamed.ticket.projectId)
	}

	@Test
	fun `setting a team and a matching project in one patch keeps the link`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val growthProject = newProject(growth.id)
		val ticket = newTicket(core.id, null)

		val moved = tickets.patch(
			ticket.ticket.id,
			TicketPatch(teamId = growth.id, projectId = growthProject.id),
		)

		assertEquals(growth.id, moved.ticket.teamId)
		assertEquals(growthProject.id, moved.ticket.projectId)
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.TicketProjectCoherenceTest"`

Expected: FAIL — the source compiles, and
`moving a ticket to another team drops a project that belonged to the old one` fails with
`AssertionError: Expected value to be null, but was <…>: no view of Growth would ever have shown that project`.
The other three tests already pass.

- [ ] **Step 3: Clear the project when the ticket leaves its team**

In `apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt`, inside `patch`, replace
the `projectId` block (currently lines 150–154) with:

```kotlin
		val requested = when {
			"projectId" in patch.unset -> null
			patch.projectId != null -> patch.projectId.also { requireProject(it) }
			else -> current.projectId
		}
		// A ticket's project must belong to its team. Not a database constraint: making
		// it one would also forbid the team-less projects the sidebar shows in their own
		// section, which are the transverse case on purpose.
		val projectId = requested?.takeIf { id ->
			val projectTeamId = projects.findById(id)?.teamId
			projectTeamId == null || projectTeamId == teamId
		}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.service.TicketProjectCoherenceTest"`

Expected: PASS, 4 tests.

- [ ] **Step 5: Run the whole API suite**

Run: `cd apps/api && ./gradlew test`

Expected: PASS. Nothing outside these files changed behaviour, and the six new test classes
run alongside the eleven that were already there.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt \
        apps/api/src/test/kotlin/dev/kanso/service/TicketProjectCoherenceTest.kt
git commit -m "fix(api): drop a ticket's project when it leaves that project's team"
```


## Part 2 — The web foundation (tasks 8–11)

Everything below runs in `apps/web`, which is the only place a `package.json` exists.
The package manager is **pnpm**, never npm. Every command is written with its `cd`
so it can be pasted as-is.

Read this before starting task 8:

- **Node must be `^20.19.0 || >=22.12.0`.** Vite 8 builds on rolldown, whose native
  binding is an *optional* dependency gated on that engine range. On an older Node
  (20.14, for instance) `pnpm add` silently installs nothing for it and the very
  first `vitest run` dies with `Cannot find native binding`. Check with `node -v`
  first; if it is too old, switch (`nvm use 24`) **before** installing, then run
  `pnpm install` again so pnpm re-evaluates the optional dependency.
- The config file is `vitest.config.mts`, not `.ts`. `apps/web/package.json` has no
  `"type": "module"`, so Vite loads a `.ts` config as CommonJS and prints a
  deprecation warning on every run about ESM syntax in a CJS file. `.mts` is also
  what `apps/web/node_modules/next/dist/docs/01-app/02-guides/testing/vitest.md`
  recommends, and `apps/web/tsconfig.json` already lists `**/*.mts` in `include`.
- Two additions to the interface contract, both forced by it:
  - `ActionContext` gains `unarchive: (target: { kind: "team" | "project"; id: string }) => void`.
    The contract makes `team.unarchive` and `project.unarchive` mandatory action ids,
    but gives their `run` nothing that could perform the write: `openDialog` only
    reaches a closed union of dialog kinds, and `patchTicket` is for tickets. One
    field, shaped like the `target` already in `Dialog`, closes the hole.
  - Task 11 creates `src/lib/use-action-ctx.ts`. It is not in the contract's file
    list but it is in the spec's (`lib/use-action-ctx.ts + assembles data, mutations,
    permissions`), and it is what makes `page.tsx` actually shrink.

---

### Task 8: Vitest and the action registry

**Files:**
- Create: `apps/web/vitest.config.mts`
- Create: `apps/web/src/lib/actions.ts`
- Modify: `apps/web/package.json:5-9,19-26` (add the `test` script and two devDependencies)
- Modify: `apps/web/src/store/ui.ts:3` (publish the `Scope` / `Overlay` / `Dialog` vocabulary; the store itself is untouched here and rewritten in task 9)
- Test: `apps/web/src/lib/actions.test.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks. It reads the types already in
  `apps/web/src/lib/api.ts`: `Team`, `Project`, `Ticket`, `TicketStatus`,
  `TicketPriority`.
- Produces:
  ```ts
  // apps/web/src/store/ui.ts
  export type Scope = { kind: "all" } | { kind: "team"; id: string } | { kind: "project"; id: string };
  export type Overlay = "none" | "composer" | "palette" | "detail" | "help" | "settings";
  export type Dialog =
    | { kind: "none" }
    | { kind: "team"; id?: string; parentTeamId?: string }
    | { kind: "project"; id?: string; teamId?: string }
    | { kind: "disposition"; target: { kind: "team" | "project"; id: string }; severity: "archive" | "delete" };

  // apps/web/src/lib/actions.ts
  export type ActionGroup = "ticket" | "team" | "project" | "view" | "app";
  export type ActionContext = {
    scope: Scope; teams: Team[]; projects: Project[]; tickets: Ticket[];
    selected?: Ticket; canConfigure: boolean;
    open: (overlay: Overlay) => void;
    close: () => void;
    openDialog: (dialog: Dialog) => void;
    setScope: (scope: Scope) => void;
    move: (delta: number) => void;
    focusFilter: () => void;
    startRename: (id: string) => void;
    patchTicket: (input: { id: string } & Record<string, unknown>) => void;
    unarchive: (target: { kind: "team" | "project"; id: string }) => void;
  };
  export type Action = {
    id: string; label: string; shortcut?: string; group: ActionGroup;
    when: (ctx: ActionContext) => boolean;
    run: (ctx: ActionContext) => void;
  };
  export const ACTIONS: readonly Action[];
  export function resolveShortcut(key: string): Action | undefined;
  export function availableActions(ctx: ActionContext): Action[];
  export function actionById(id: string): Action;
  export function shortcutRows(): { keys: string; label: string }[];
  ```

---

- [ ] **Step 1: Install the test runner**

Check the Node version first — see the note at the top of this file.

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web && node -v
```

If it does not satisfy `^20.19.0 || >=22.12.0`, switch to one that does (`nvm use 24`)
before continuing. Then:

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web && pnpm add -D vitest@4.1.10 vite@8.2.1
```

Verify the native binding actually landed — an empty result here means the Node
version was still wrong and the next steps will fail at startup:

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web && ls node_modules/.pnpm/rolldown@*/node_modules/@rolldown/
```

Expected: a `binding-<platform>-<arch>` entry alongside `pluginutils`.

- [ ] **Step 2: Configure Vitest and add the script**

Create `apps/web/vitest.config.mts`:

```ts
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    // tsconfig's `@/*` mapping is a compile-time contract that Vite never reads,
    // so the same alias has to be restated here or the test run cannot resolve it.
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  test: {
    // The registry is pure logic. A DOM would add a dependency and a second of
    // startup to tests that never touch one.
    environment: "node",
    include: ["src/**/*.{test,spec}.ts"],
  },
});
```

In `apps/web/package.json`, add one line to `"scripts"` so it reads:

```json
  "scripts": {
    "dev": "next dev",
    "build": "next build",
    "start": "next start",
    "lint": "eslint",
    "test": "vitest run"
  },
```

`vitest run` rather than bare `vitest`: the default is watch mode, and a script that
never exits is the wrong default for a command other tasks will run to check
themselves.

- [ ] **Step 3: Write the failing test**

Create `apps/web/src/lib/actions.test.ts`:

```ts
import { describe, expect, it, vi } from "vitest";
import {
  ACTIONS,
  actionById,
  availableActions,
  resolveShortcut,
  shortcutRows,
  type ActionContext,
} from "./actions";
import type { Project, Team, Ticket } from "./api";

const core: Team = {
  id: "team-core",
  name: "Core",
  key: "KAN",
  archived: false,
  ticketCount: 1,
  mirror: { state: "synced" },
};

const legacy: Team = { ...core, id: "team-legacy", name: "Legacy", key: "LEG", archived: true };

const refonte: Project = {
  id: "project-refonte",
  name: "Refonte",
  status: "active",
  teamId: "team-core",
  archived: false,
  mirror: { state: "synced" },
};

const audit: Project = { ...refonte, id: "project-audit", name: "Audit", archived: true };

const ticket: Ticket = {
  id: "ticket-1",
  identifier: "KAN-1",
  number: 1,
  teamId: "team-core",
  title: "Fix the OAuth login",
  status: "todo",
  priority: "medium",
  assigneeIds: [],
  docIds: [],
  archived: false,
  mirror: { state: "synced" },
  createdAt: "2026-08-07T09:00:00Z",
  updatedAt: "2026-08-07T09:00:00Z",
};

function context(overrides: Partial<ActionContext> = {}): ActionContext {
  return {
    scope: { kind: "all" },
    teams: [core, legacy],
    projects: [refonte, audit],
    tickets: [ticket],
    selected: undefined,
    canConfigure: true,
    open: vi.fn(),
    close: vi.fn(),
    openDialog: vi.fn(),
    setScope: vi.fn(),
    move: vi.fn(),
    focusFilter: vi.fn(),
    startRename: vi.fn(),
    patchTicket: vi.fn(),
    unarchive: vi.fn(),
    ...overrides,
  };
}

const ids = (ctx: ActionContext) => availableActions(ctx).map((action) => action.id);

/** Every id a menu, a button or a test is allowed to name. */
const REQUIRED_IDS = [
  "ticket.create",
  "ticket.open",
  "ticket.rename",
  "ticket.archive",
  "ticket.moveDown",
  "ticket.moveUp",
  "ticket.status.backlog",
  "ticket.status.todo",
  "ticket.status.in_progress",
  "ticket.status.in_review",
  "ticket.status.done",
  "ticket.status.canceled",
  "team.create",
  "team.createChild",
  "team.rename",
  "team.archive",
  "team.unarchive",
  "team.delete",
  "project.create",
  "project.createInTeam",
  "project.edit",
  "project.archive",
  "project.unarchive",
  "project.delete",
  "view.all",
  "view.filter",
  "app.palette",
  "app.settings",
  "app.help",
];

describe("the registry", () => {
  it("defines every action the menus reference by id", () => {
    for (const id of REQUIRED_IDS) {
      expect(actionById(id).id).toBe(id);
    }
  });

  it("throws on an unknown id rather than silently doing nothing", () => {
    expect(() => actionById("team.rename-v2")).toThrow(/Unknown action/);
  });
});

describe("resolveShortcut", () => {
  it("maps each key the inbox handles today to exactly one action", () => {
    expect(resolveShortcut("j")?.id).toBe("ticket.moveDown");
    expect(resolveShortcut("ArrowDown")?.id).toBe("ticket.moveDown");
    expect(resolveShortcut("k")?.id).toBe("ticket.moveUp");
    expect(resolveShortcut("ArrowUp")?.id).toBe("ticket.moveUp");
    expect(resolveShortcut("Enter")?.id).toBe("ticket.open");
    expect(resolveShortcut("c")?.id).toBe("ticket.create");
    expect(resolveShortcut("e")?.id).toBe("ticket.rename");
    expect(resolveShortcut("x")?.id).toBe("ticket.archive");
    expect(resolveShortcut("1")?.id).toBe("ticket.status.backlog");
    expect(resolveShortcut("2")?.id).toBe("ticket.status.todo");
    expect(resolveShortcut("3")?.id).toBe("ticket.status.in_progress");
    expect(resolveShortcut("4")?.id).toBe("ticket.status.in_review");
    expect(resolveShortcut("5")?.id).toBe("ticket.status.done");
    expect(resolveShortcut("6")?.id).toBe("ticket.status.canceled");
    expect(resolveShortcut("/")?.id).toBe("view.filter");
    expect(resolveShortcut(",")?.id).toBe("app.settings");
    expect(resolveShortcut("?")?.id).toBe("app.help");
  });

  it("lets no two actions claim the same key", () => {
    const claimed = new Map<string, string>();
    for (const action of ACTIONS) {
      for (const key of action.shortcut?.split(" ") ?? []) {
        expect(claimed.get(key)).toBeUndefined();
        claimed.set(key, action.id);
      }
    }
  });

  it("leaves an unbound key alone", () => {
    expect(resolveShortcut("z")).toBeUndefined();
    expect(resolveShortcut("Escape")).toBeUndefined();
  });

  it("keeps the palette off the bare keys, since it needs a modifier", () => {
    expect(actionById("app.palette").shortcut).toBeUndefined();
  });
});

describe("availableActions", () => {
  it("hides every team action from someone who cannot configure the instance", () => {
    const member = context({ canConfigure: false, scope: { kind: "team", id: core.id } });
    expect(availableActions(member).filter((action) => action.group === "team")).toEqual([]);
  });

  it("offers them to an admin on the same scope", () => {
    const admin = ids(context({ scope: { kind: "team", id: core.id } }));
    expect(admin).toContain("team.create");
    expect(admin).toContain("team.createChild");
    expect(admin).toContain("team.rename");
    expect(admin).toContain("team.delete");
  });

  it("offers archive on a live team and unarchive on an archived one, never both", () => {
    const live = ids(context({ scope: { kind: "team", id: core.id } }));
    expect(live).toContain("team.archive");
    expect(live).not.toContain("team.unarchive");

    const archived = ids(context({ scope: { kind: "team", id: legacy.id } }));
    expect(archived).toContain("team.unarchive");
    expect(archived).not.toContain("team.archive");
  });

  it("withholds ticket actions while nothing is selected", () => {
    const empty = ids(context());
    expect(empty).not.toContain("ticket.open");
    expect(empty).not.toContain("ticket.rename");
    expect(empty).not.toContain("ticket.archive");
    expect(empty).not.toContain("ticket.status.done");
    // Filing a new ticket needs no selection — that is the whole point of `c`.
    expect(empty).toContain("ticket.create");
  });

  it("offers them once a ticket is selected", () => {
    const selected = ids(context({ selected: ticket }));
    expect(selected).toContain("ticket.open");
    expect(selected).toContain("ticket.rename");
    expect(selected).toContain("ticket.archive");
    expect(selected).toContain("ticket.status.done");
  });

  it("withholds project actions outside a project scope", () => {
    const all = ids(context());
    expect(all).not.toContain("project.edit");
    expect(all).not.toContain("project.archive");
    expect(all).not.toContain("project.delete");
    // Creating one is always allowed: a project needs no team.
    expect(all).toContain("project.create");
  });

  it("offers them inside one, and unarchive on an archived project", () => {
    const live = ids(context({ scope: { kind: "project", id: refonte.id } }));
    expect(live).toContain("project.edit");
    expect(live).toContain("project.archive");
    expect(live).toContain("project.delete");
    expect(live).not.toContain("project.unarchive");

    const archived = ids(context({ scope: { kind: "project", id: audit.id } }));
    expect(archived).toContain("project.unarchive");
    expect(archived).not.toContain("project.archive");
  });

  it("drops the all-tickets view when it is already the scope", () => {
    expect(ids(context())).not.toContain("view.all");
    expect(ids(context({ scope: { kind: "team", id: core.id } }))).toContain("view.all");
  });
});

describe("running an action", () => {
  it("archives a ticket through the same patch the list uses", () => {
    const ctx = context({ selected: ticket });
    actionById("ticket.archive").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({ id: ticket.id, archived: true });
  });

  it("unarchives it back", () => {
    const ctx = context({ selected: { ...ticket, archived: true } });
    actionById("ticket.archive").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({ id: ticket.id, archived: false });
  });

  it("does nothing when a ticket action runs with no selection", () => {
    const ctx = context();
    actionById("ticket.rename").run(ctx);
    expect(ctx.startRename).not.toHaveBeenCalled();
  });

  it("opens the disposition dialog at the severity the action carries", () => {
    const ctx = context({ scope: { kind: "team", id: core.id } });
    actionById("team.delete").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({
      kind: "disposition",
      target: { kind: "team", id: core.id },
      severity: "delete",
    });
  });

  it("opens the team dialog with a parent when creating a sub-team", () => {
    const ctx = context({ scope: { kind: "team", id: core.id } });
    actionById("team.createChild").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({ kind: "team", parentTeamId: core.id });
  });

  it("closes the palette after a status change, so the list is visible again", () => {
    const ctx = context({ selected: ticket });
    actionById("ticket.status.in_review").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({ id: ticket.id, status: "in_review" });
    expect(ctx.close).toHaveBeenCalled();
  });
});

describe("shortcutRows", () => {
  it("is derived from the actions carrying a shortcut, not written by hand", () => {
    const bound = ACTIONS.filter((action) => action.shortcut !== undefined);
    const rows = shortcutRows();

    expect(rows).toHaveLength(bound.length);
    for (const action of bound) {
      expect(rows.some((row) => row.label === action.label)).toBe(true);
    }
  });

  it("prints the arrow keys as arrows rather than as DOM key names", () => {
    const rows = shortcutRows();
    expect(rows.find((row) => row.label === "Move down")?.keys).toBe("j / ↓");
    expect(rows.find((row) => row.label === "Move up")?.keys).toBe("k / ↑");
  });

  it("carries no row for an action the keyboard cannot reach", () => {
    expect(shortcutRows().some((row) => row.label === "New project")).toBe(false);
  });
});
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd /Users/elietreport/Projet/Perso/Kanso/apps/web && pnpm exec vitest run src/lib/actions.test.ts`

Expected: FAIL with

```
 FAIL  src/lib/actions.test.ts [ src/lib/actions.test.ts ]
Error: Cannot find module './actions' imported from /…/apps/web/src/lib/actions.test.ts

 Test Files  1 failed (1)
      Tests  no tests
```

If instead it dies with `Cannot find native binding`, go back to step 1: the Node
version is too old and rolldown's binding was never installed.

- [ ] **Step 5: Publish the scope vocabulary the registry types against**

`src/lib/actions.ts` imports `Scope`, `Overlay` and `Dialog` from the store, so those
three types have to exist before the registry can. Only the types move here; the
store's state and its setters are still the old ones and are rewritten in task 9.

In `apps/web/src/store/ui.ts`, replace the single line

```ts
type Overlay = "none" | "composer" | "palette" | "detail" | "help" | "settings";
```

with:

```ts
/** What the ticket list is showing — and, verbatim, part of the tickets query key. */
export type Scope =
  | { kind: "all" }
  | { kind: "team"; id: string }
  | { kind: "project"; id: string };

export type Overlay = "none" | "composer" | "palette" | "detail" | "help" | "settings";

/**
 * A dialog names the entity it is about, so opening one needs no second call to
 * seed it. `disposition` carries a severity rather than splitting into two kinds:
 * archiving and deleting ask the same questions and differ only in the answer.
 */
export type Dialog =
  | { kind: "none" }
  | { kind: "team"; id?: string; parentTeamId?: string }
  | { kind: "project"; id?: string; teamId?: string }
  | {
      kind: "disposition";
      target: { kind: "team" | "project"; id: string };
      severity: "archive" | "delete";
    };
```

- [ ] **Step 6: Write the registry**

Create `apps/web/src/lib/actions.ts`. Every action is spelled out — no map, no
factory. A registry whose entries are generated is a registry nobody can read in one
pass, and reading it in one pass is the whole point of having it.

Three things worth knowing before reading it:

- `shortcut` holds **space-separated `KeyboardEvent.key` values**, so `ticket.moveDown`
  can own both `j` and `ArrowDown` from one field. A second field for aliases could
  disagree with the first; a string that is split cannot.
- `app.palette` deliberately has **no** `shortcut`. `⌘K` is a modified key, resolved
  in the page before the registry is consulted; giving it a bare key here would steal
  `k` from `ticket.moveUp`.
- `ticket.priority.*` is not in the contract's mandatory list but is in the registry:
  the palette offers those five commands today, and task 11 deletes the array they
  live in. Dropping them would be a silent regression.

```ts
import type { Dialog, Overlay, Scope } from "@/store/ui";
import type { Project, Team, Ticket, TicketPriority, TicketStatus } from "./api";

export type ActionGroup = "ticket" | "team" | "project" | "view" | "app";

export type ActionContext = {
  scope: Scope;
  teams: Team[];
  projects: Project[];
  tickets: Ticket[];
  selected?: Ticket;
  canConfigure: boolean;

  open: (overlay: Overlay) => void;
  close: () => void;
  openDialog: (dialog: Dialog) => void;
  setScope: (scope: Scope) => void;
  move: (delta: number) => void;
  focusFilter: () => void;
  startRename: (id: string) => void;
  patchTicket: (input: { id: string } & Record<string, unknown>) => void;
  unarchive: (target: { kind: "team" | "project"; id: string }) => void;
};

export type Action = {
  id: string;
  label: string;
  /**
   * Space-separated `KeyboardEvent.key` values, so one action can own the two
   * spellings of the same intent (`j` and `ArrowDown`) without a second field
   * that could disagree with this one.
   */
  shortcut?: string;
  group: ActionGroup;
  when: (ctx: ActionContext) => boolean;
  run: (ctx: ActionContext) => void;
};

const hasSelection = (ctx: ActionContext) => ctx.selected !== undefined;

/**
 * `when` has already answered this, but the compiler cannot know that a predicate
 * run earlier constrains a field read later. Re-checking here is what keeps
 * `selected` narrowed without a non-null assertion.
 */
const onSelected =
  (run: (ctx: ActionContext, ticket: Ticket) => void) => (ctx: ActionContext) => {
    if (ctx.selected) run(ctx, ctx.selected);
  };

/** Same guard as [onSelected], for the two scopes that name an entity. */
const onTeam = (run: (ctx: ActionContext, id: string) => void) => (ctx: ActionContext) => {
  if (ctx.scope.kind === "team") run(ctx, ctx.scope.id);
};

const onProject = (run: (ctx: ActionContext, id: string) => void) => (ctx: ActionContext) => {
  if (ctx.scope.kind === "project") run(ctx, ctx.scope.id);
};

/**
 * Archive and unarchive are the same action seen from two sides, so both ask the
 * loaded row rather than assume. An entity absent from the cache — archived while
 * "Show archived" is off — offers neither: its state is not known here.
 */
const scopedTeam = (ctx: ActionContext): Team | undefined => {
  if (ctx.scope.kind !== "team") return undefined;
  const { id } = ctx.scope;
  return ctx.teams.find((team) => team.id === id);
};

const scopedProject = (ctx: ActionContext): Project | undefined => {
  if (ctx.scope.kind !== "project") return undefined;
  const { id } = ctx.scope;
  return ctx.projects.find((project) => project.id === id);
};

export const ACTIONS: readonly Action[] = [
  {
    id: "ticket.create",
    label: "New ticket",
    shortcut: "c",
    group: "ticket",
    when: () => true,
    run: (ctx) => ctx.open("composer"),
  },
  {
    id: "ticket.open",
    label: "Open ticket",
    shortcut: "Enter",
    group: "ticket",
    when: hasSelection,
    run: (ctx) => ctx.open("detail"),
  },
  {
    id: "ticket.rename",
    label: "Rename ticket",
    shortcut: "e",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => ctx.startRename(ticket.id)),
  },
  {
    id: "ticket.archive",
    label: "Archive / unarchive ticket",
    shortcut: "x",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => ctx.patchTicket({ id: ticket.id, archived: !ticket.archived })),
  },
  {
    id: "ticket.moveDown",
    label: "Move down",
    shortcut: "j ArrowDown",
    group: "ticket",
    when: (ctx) => ctx.tickets.length > 0,
    run: (ctx) => ctx.move(1),
  },
  {
    id: "ticket.moveUp",
    label: "Move up",
    shortcut: "k ArrowUp",
    group: "ticket",
    when: (ctx) => ctx.tickets.length > 0,
    run: (ctx) => ctx.move(-1),
  },

  // `patchTicket` takes `Record<string, unknown>`, so `satisfies` is the only thing
  // standing between a typo in a wire value and a 400 at runtime.
  {
    id: "ticket.status.backlog",
    label: "Set status: Backlog",
    shortcut: "1",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "backlog" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.todo",
    label: "Set status: Todo",
    shortcut: "2",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "todo" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.in_progress",
    label: "Set status: In progress",
    shortcut: "3",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "in_progress" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.in_review",
    label: "Set status: In review",
    shortcut: "4",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "in_review" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.done",
    label: "Set status: Done",
    shortcut: "5",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "done" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.canceled",
    label: "Set status: Canceled",
    shortcut: "6",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "canceled" satisfies TicketStatus });
      ctx.close();
    }),
  },

  {
    id: "ticket.priority.none",
    label: "Set priority: None",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "none" satisfies TicketPriority });
      ctx.close();
    }),
  },
  {
    id: "ticket.priority.low",
    label: "Set priority: Low",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "low" satisfies TicketPriority });
      ctx.close();
    }),
  },
  {
    id: "ticket.priority.medium",
    label: "Set priority: Medium",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "medium" satisfies TicketPriority });
      ctx.close();
    }),
  },
  {
    id: "ticket.priority.high",
    label: "Set priority: High",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "high" satisfies TicketPriority });
      ctx.close();
    }),
  },
  {
    id: "ticket.priority.urgent",
    label: "Set priority: Urgent",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "urgent" satisfies TicketPriority });
      ctx.close();
    }),
  },

  {
    id: "team.create",
    label: "New team",
    group: "team",
    when: (ctx) => ctx.canConfigure,
    run: (ctx) => ctx.openDialog({ kind: "team" }),
  },
  {
    id: "team.createChild",
    label: "New sub-team",
    group: "team",
    when: (ctx) => ctx.canConfigure && ctx.scope.kind === "team",
    run: onTeam((ctx, id) => ctx.openDialog({ kind: "team", parentTeamId: id })),
  },
  {
    id: "team.rename",
    label: "Rename team",
    group: "team",
    when: (ctx) => ctx.canConfigure && ctx.scope.kind === "team",
    run: onTeam((ctx, id) => ctx.openDialog({ kind: "team", id })),
  },
  {
    id: "team.archive",
    label: "Archive team",
    group: "team",
    when: (ctx) => ctx.canConfigure && scopedTeam(ctx)?.archived === false,
    run: onTeam((ctx, id) =>
      ctx.openDialog({
        kind: "disposition",
        target: { kind: "team", id },
        severity: "archive",
      }),
    ),
  },
  {
    id: "team.unarchive",
    label: "Unarchive team",
    group: "team",
    when: (ctx) => ctx.canConfigure && scopedTeam(ctx)?.archived === true,
    run: onTeam((ctx, id) => ctx.unarchive({ kind: "team", id })),
  },
  {
    id: "team.delete",
    label: "Delete team",
    group: "team",
    when: (ctx) => ctx.canConfigure && ctx.scope.kind === "team",
    run: onTeam((ctx, id) =>
      ctx.openDialog({
        kind: "disposition",
        target: { kind: "team", id },
        severity: "delete",
      }),
    ),
  },

  {
    id: "project.create",
    label: "New project",
    group: "project",
    when: () => true,
    run: (ctx) => ctx.openDialog({ kind: "project" }),
  },
  {
    id: "project.createInTeam",
    label: "New project in this team",
    group: "project",
    when: (ctx) => ctx.scope.kind === "team",
    run: onTeam((ctx, id) => ctx.openDialog({ kind: "project", teamId: id })),
  },
  {
    id: "project.edit",
    label: "Edit project",
    group: "project",
    when: (ctx) => ctx.scope.kind === "project",
    run: onProject((ctx, id) => ctx.openDialog({ kind: "project", id })),
  },
  {
    id: "project.archive",
    label: "Archive project",
    group: "project",
    when: (ctx) => scopedProject(ctx)?.archived === false,
    run: onProject((ctx, id) =>
      ctx.openDialog({
        kind: "disposition",
        target: { kind: "project", id },
        severity: "archive",
      }),
    ),
  },
  {
    id: "project.unarchive",
    label: "Unarchive project",
    group: "project",
    when: (ctx) => scopedProject(ctx)?.archived === true,
    run: onProject((ctx, id) => ctx.unarchive({ kind: "project", id })),
  },
  {
    id: "project.delete",
    label: "Delete project",
    group: "project",
    when: (ctx) => ctx.scope.kind === "project",
    run: onProject((ctx, id) =>
      ctx.openDialog({
        kind: "disposition",
        target: { kind: "project", id },
        severity: "delete",
      }),
    ),
  },

  {
    id: "view.all",
    label: "View: all tickets",
    group: "view",
    when: (ctx) => ctx.scope.kind !== "all",
    run: (ctx) => {
      ctx.setScope({ kind: "all" });
      ctx.close();
    },
  },
  {
    id: "view.filter",
    label: "Filter tickets",
    shortcut: "/",
    group: "view",
    when: () => true,
    run: (ctx) => {
      ctx.close();
      ctx.focusFilter();
    },
  },

  {
    id: "app.palette",
    label: "Command palette",
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("palette"),
  },
  {
    id: "app.settings",
    label: "Settings",
    shortcut: ",",
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("settings"),
  },
  {
    id: "app.help",
    label: "Keyboard shortcuts",
    shortcut: "?",
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("help"),
  },
];

const BY_ID = new Map<string, Action>();
const BY_KEY = new Map<string, Action>();

for (const action of ACTIONS) {
  if (BY_ID.has(action.id)) throw new Error(`Duplicate action id "${action.id}"`);
  BY_ID.set(action.id, action);

  for (const key of action.shortcut?.split(" ") ?? []) {
    const claimed = BY_KEY.get(key);
    if (claimed) {
      throw new Error(`Key "${key}" is claimed by both "${claimed.id}" and "${action.id}"`);
    }
    BY_KEY.set(key, action);
  }
}

/** The action a bare keypress means, before `when` is consulted. */
export function resolveShortcut(key: string): Action | undefined {
  return BY_KEY.get(key);
}

/** Everything currently permitted — what the palette lists and menus filter. */
export function availableActions(ctx: ActionContext): Action[] {
  return ACTIONS.filter((action) => action.when(ctx));
}

/** Throws on an unknown id: a menu referencing a dead action is a bug, not a no-op. */
export function actionById(id: string): Action {
  const action = BY_ID.get(id);
  if (!action) throw new Error(`Unknown action "${id}"`);
  return action;
}

const KEY_LABELS: Record<string, string> = {
  ArrowDown: "↓",
  ArrowUp: "↑",
};

/** Rows for the help overlay, generated from the shortcuts. */
export function shortcutRows(): { keys: string; label: string }[] {
  return ACTIONS.flatMap((action) =>
    action.shortcut === undefined
      ? []
      : [
          {
            keys: action.shortcut
              .split(" ")
              .map((key) => KEY_LABELS[key] ?? key)
              .join(" / "),
            label: action.label,
          },
        ],
  );
}
```

The two `Map`s are built at module load and **throw** on a duplicate id or a key
claimed twice. A collision there is not a test failure to be found later; it is a
module that refuses to load, which is the earliest anyone can be told.

- [ ] **Step 7: Verify**

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web && pnpm exec vitest run src/lib/actions.test.ts
```
Expected: `Test Files 1 passed (1)`, `Tests 23 passed (23)`.

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web && pnpm exec tsc --noEmit && pnpm lint
```
Expected: both silent. `tsc` covers `vitest.config.mts` and the test file too —
`apps/web/tsconfig.json` already includes `**/*.ts` and `**/*.mts`.

- [ ] **Step 8: Commit**

```bash
git add apps/web/package.json apps/web/pnpm-lock.yaml apps/web/vitest.config.mts apps/web/src/lib/actions.ts apps/web/src/lib/actions.test.ts apps/web/src/store/ui.ts
git commit -m "feat(web): add an action registry with Vitest covering it

One list the keyboard handler, the palette, the menus and the help overlay all
read from, so an action added once appears in all four. Vitest 4 runs it in a
plain node environment: the registry is pure logic."
```

---

### Task 9: The `ui` store moves to `scope`

Selection stops being "a team id or nothing" and becomes a three-way scope. This is
refactoring: nothing new is meant to be visible afterwards. The guard rails are
`pnpm exec tsc --noEmit`, `pnpm lint`, and task 8's tests staying green.

**Files:**
- Modify: `apps/web/src/store/ui.ts:12-34` (the `UiState` type and the store itself; the types added in task 8 stay as they are)
- Modify: `apps/web/src/app/page.tsx:47,230-245,249,276-281`
- Modify: `apps/web/src/components/sidebar.tsx:3,30-40,52-55,70-71`
- Test: none of its own — guarded by `tsc --noEmit`, `pnpm lint` and `src/lib/actions.test.ts` staying green.

**Interfaces:**
- Consumes (task 8): `Scope`, `Overlay`, `Dialog` from `@/store/ui`.
- Produces:
  ```ts
  // apps/web/src/store/ui.ts — the store's runtime surface
  useUi(): {
    scope: Scope;
    selectedId?: string;
    overlay: Overlay;
    dialog: Dialog;
    query: string;
    showArchived: boolean;
    setScope: (scope: Scope) => void;
    select: (id?: string) => void;
    open: (overlay: Overlay) => void;
    close: () => void;                 // closes overlay AND dialog
    openDialog: (dialog: Dialog) => void;
    setQuery: (query: string) => void;
    setShowArchived: (showArchived: boolean) => void;
  }

  // apps/web/src/components/sidebar.tsx
  function Sidebar(props: {
    teams: Team[];
    scope: Scope;
    onSelectScope: (scope: Scope) => void;
    syncSummary: string;
  }): JSX.Element
  ```

---

- [ ] **Step 1: Rewrite the store's state**

In `apps/web/src/store/ui.ts`, replace everything from `type UiState = {` to the end
of the file with:

```ts
type UiState = {
  scope: Scope;
  selectedId?: string;
  overlay: Overlay;
  dialog: Dialog;
  query: string;
  showArchived: boolean;

  setScope: (scope: Scope) => void;
  select: (id?: string) => void;
  open: (overlay: Overlay) => void;
  close: () => void;
  openDialog: (dialog: Dialog) => void;
  setQuery: (query: string) => void;
  setShowArchived: (showArchived: boolean) => void;
};

export const useUi = create<UiState>((set) => ({
  scope: { kind: "all" },
  overlay: "none",
  dialog: { kind: "none" },
  query: "",
  showArchived: false,

  // A new scope is a new list, so no cursor from the old one can survive it.
  setScope: (scope) => set({ scope, selectedId: undefined }),
  select: (selectedId) => set({ selectedId }),
  open: (overlay) => set({ overlay }),
  // Escape is one key and means one thing, whichever of the two is on screen.
  close: () => set({ overlay: "none", dialog: { kind: "none" } }),
  openDialog: (dialog) => set({ dialog }),
  setQuery: (query) => set({ query }),
  setShowArchived: (showArchived) => set({ showArchived }),
}));
```

Keep the existing `/** Purely local interface state… */` block comment above
`type UiState` — it still says the true thing about why this store exists at all.

- [ ] **Step 2: Teach the sidebar to speak scopes**

The real sidebar — tree, projects, row menus — is a later task. This is the minimum
that keeps it compiling and puts it on the vocabulary it will need anyway.

In `apps/web/src/components/sidebar.tsx`, after the existing `import type { Team }`
line, add:

```ts
import type { Scope } from "@/store/ui";
```

Replace the component's signature:

```tsx
export function Sidebar({
  teams,
  scope,
  onSelectScope,
  syncSummary,
}: {
  teams: Team[];
  scope: Scope;
  onSelectScope: (scope: Scope) => void;
  syncSummary: string;
}) {
```

Replace the two lines on the "All tickets" button:

```tsx
          aria-current={scope.kind === "all"}
          onClick={() => onSelectScope({ kind: "all" })}
```

Replace the two lines on the team button:

```tsx
            aria-current={scope.kind === "team" && scope.id === team.id}
            onClick={() => onSelectScope({ kind: "team", id: team.id })}
```

- [ ] **Step 3: Update the page's call sites**

In `apps/web/src/app/page.tsx`, replace the `useUi()` destructuring line with:

```tsx
  const { scope, selectedId, overlay, query, setScope, select, open, close, setQuery } = useUi();

  // The query layer still keys on a team; task 10 teaches it the whole scope.
  const teamId = scope.kind === "team" ? scope.id : undefined;
```

In the `commands` array, the two entries that changed the team become:

```tsx
      {
        id: "all-teams",
        label: "View: all tickets",
        run: () => {
          setScope({ kind: "all" });
          close();
        },
      },
      ...(teams.data ?? []).map((team) => ({
        id: `team-${team.id}`,
        label: `View team: ${team.name}`,
        run: () => {
          setScope({ kind: "team", id: team.id });
          close();
        },
      })),
```

and that `useMemo`'s dependency array ends `setPriority, setScope],` instead of
`setPriority, setTeam],`.

Finally the `<Sidebar …>` props:

```tsx
        <Sidebar
          teams={teams.data ?? []}
          scope={scope}
          onSelectScope={setScope}
          syncSummary={mirrorSummary}
        />
```

- [ ] **Step 4: Verify**

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web && pnpm exec tsc --noEmit && pnpm lint && pnpm test
```

Expected: `tsc` and `eslint` silent, `Tests 23 passed (23)`. A `setTeam is not
defined` or `Property 'teamId' does not exist` from `tsc` means a call site was
missed — search for it:

```bash
cd /Users/elietreport/Projet/Perso/Kanso && grep -rn "setTeam\|activeTeamId\|onSelectTeam" apps/web/src
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/store/ui.ts apps/web/src/app/page.tsx apps/web/src/components/sidebar.tsx
git commit -m "refactor(web): replace the ui store's teamId with a scope

A team id could not say 'this project' or 'everything'. The scope is a closed
union, so every consumer has to handle the three cases the sidebar will offer."
```

---

### Task 10: The API client and the query keys

The client learns the whole disposition surface, `ApiError` starts carrying the
response body so a 409 can hand the modal its fresh counts, and the per-team projects
query becomes one global query. Still refactoring: same guard rails as task 9.

**Files:**
- Modify: `apps/web/src/lib/api.ts:1,54-61,155-162,194-199,283-301`
- Modify: `apps/web/src/lib/queries.ts:1-30,101,119-123,145,180-209,211-220`
- Modify: `apps/web/src/app/page.tsx:47-58,98,124-131`
- Test: none of its own — guarded by `tsc --noEmit`, `pnpm lint` and `src/lib/actions.test.ts` staying green.

**Interfaces:**
- Consumes (task 9): `Scope` from `@/store/ui`, and the store's `scope` /
  `showArchived` fields.
- Produces:
  ```ts
  // apps/web/src/lib/api.ts
  export type DispositionChoice = "take" | "keep";
  export type DispositionCounts = { subTeams: number; projects: number; tickets: number };
  export type DispositionPlan = {
    subTeams: DispositionChoice; projects: DispositionChoice; tickets: DispositionChoice;
    ticketsTargetTeamId?: string; counts?: DispositionCounts;
  };
  export type ProjectBody = {
    name: string; status?: string; startDate?: string; endDate?: string;
    leadUserId?: string; teamId?: string;
  };
  class ApiError { readonly status: number; readonly detail: string; readonly body?: unknown }

  api.teams(includeArchived?: boolean): Promise<Team[]>
  api.createTeam(body: { name: string; key?: string; parentTeamId?: string }): Promise<Team>
  api.updateTeam(id: string, body: { name: string; key?: string; parentTeamId?: string }): Promise<Team>
  api.teamContents(id: string): Promise<DispositionCounts>
  api.archiveTeam(id: string, plan: DispositionPlan): Promise<Team>
  api.unarchiveTeam(id: string): Promise<Team>
  api.deleteTeam(id: string, plan: DispositionPlan): Promise<void>
  api.projects(opts?: { teamId?: string; includeArchived?: boolean }): Promise<Project[]>
  api.createProject(body: ProjectBody): Promise<Project>
  api.updateProject(id: string, body: ProjectBody): Promise<Project>
  api.projectContents(id: string): Promise<DispositionCounts>
  api.archiveProject(id: string, plan: DispositionPlan): Promise<Project>
  api.unarchiveProject(id: string): Promise<Project>
  api.deleteProject(id: string, plan: DispositionPlan): Promise<void>
  api.tickets(scope: Scope, includeArchived?: boolean): Promise<Ticket[]>
  api.createTicket(body: { teamId: string; title: string; status?: TicketStatus;
    priority?: TicketPriority; projectId?: string; assigneeIds?: string[] }): Promise<Ticket>

  // apps/web/src/lib/queries.ts
  keys.teams(includeArchived: boolean)
  keys.projects(includeArchived: boolean)
  keys.tickets(scope: Scope, includeArchived: boolean)
  keys.contents(kind: "team" | "project", id: string)
  useTeams(): UseQueryResult<Team[]>          // no argument any more
  useProjects(): UseQueryResult<Project[]>    // no argument any more
  useTickets(): UseQueryResult<Ticket[]>      // no argument any more
  useContents(kind: "team" | "project", id: string): UseQueryResult<DispositionCounts>
  usePatchTicket(): UseMutationResult          // no argument any more
  useCreateTicket(): UseMutationResult         // no argument any more
  useDeleteTicket(): UseMutationResult         // no argument any more
  useUnarchive(): UseMutationResult<Team | Project, Error, { kind: "team" | "project"; id: string }>
  ```

The team and project *write* hooks (create, update, archive, delete) land with the
dialogs that call them, in a later task. `useUnarchive` is here because `team.unarchive`
and `project.unarchive` are already reachable from the palette once task 11 lands.

---

- [ ] **Step 1: Extend the API client's types**

At the very top of `apps/web/src/lib/api.ts`, above `export const API_URL`:

```ts
// Type-only, so nothing of the store reaches the runtime bundle: the tickets
// endpoint is shaped by the scope, and restating that union here would let the
// two drift.
import type { Scope } from "@/store/ui";
```

Replace the `Project` type with the widened one and the new disposition types:

```ts
export type Project = {
  id: string;
  name: string;
  status: string;
  startDate?: string;
  endDate?: string;
  leadUserId?: string;
  teamId?: string;
  archived: boolean;
  mirror: Mirror;
};

export type ProjectBody = {
  name: string;
  status?: string;
  startDate?: string;
  endDate?: string;
  leadUserId?: string;
  teamId?: string;
};

/** What happens to what a team or a project holds when the container goes away. */
export type DispositionChoice = "take" | "keep";

export type DispositionCounts = { subTeams: number; projects: number; tickets: number };

/**
 * `counts` is what the modal displayed. Deleting sends it so the server can refuse
 * on drift; archiving may omit it, because archiving comes back.
 */
export type DispositionPlan = {
  subTeams: DispositionChoice;
  projects: DispositionChoice;
  tickets: DispositionChoice;
  ticketsTargetTeamId?: string;
  counts?: DispositionCounts;
};
```

- [ ] **Step 2: Let `ApiError` carry the response body**

Replace the `ApiError` class:

```ts
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
    /**
     * The whole problem document. A 409 on a disposition carries the fresh
     * `counts` there, and the modal has to reopen on them.
     */
    readonly body?: unknown,
  ) {
    super(detail);
  }
}
```

and, inside `request`, pass the parsed problem through:

```ts
    const problem = await response.json().catch(() => null);
    throw new ApiError(response.status, problem?.detail ?? response.statusText, problem);
```

- [ ] **Step 3: Add a query-string helper**

Immediately above `export const api = {`:

```ts
/** Drops absent parameters rather than sending `undefined` as a literal string. */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) search.set(key, String(value));
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : "";
}
```

Hand-concatenated `?a=…&b=…` was fine for one optional parameter. Three of them
turn into a chain of ternaries where a missing `&` is a silent 400.

- [ ] **Step 4: Replace the teams / projects / tickets block**

In `apps/web/src/lib/api.ts`, replace everything from `teams: () => request<Team[]>("/api/teams"),`
down to the end of the `createTicket` entry (the last five entries, `users` and
`syncStatus`, stay where they are):

```ts
  // --- teams ---------------------------------------------------------------

  teams: (includeArchived = false) => request<Team[]>(`/api/teams${query({ includeArchived })}`),

  createTeam: (body: { name: string; key?: string; parentTeamId?: string }) =>
    request<Team>("/api/teams", { method: "POST", body: JSON.stringify(body) }),

  /** Reparenting is this call too: the parent is just another field. */
  updateTeam: (id: string, body: { name: string; key?: string; parentTeamId?: string }) =>
    request<Team>(`/api/teams/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  teamContents: (id: string) => request<DispositionCounts>(`/api/teams/${id}/contents`),

  archiveTeam: (id: string, plan: DispositionPlan) =>
    request<Team>(`/api/teams/${id}/archive`, { method: "PUT", body: JSON.stringify(plan) }),

  unarchiveTeam: (id: string) => request<Team>(`/api/teams/${id}/unarchive`, { method: "POST" }),

  deleteTeam: (id: string, plan: DispositionPlan) =>
    request<void>(`/api/teams/${id}`, { method: "DELETE", body: JSON.stringify(plan) }),

  // --- projects ------------------------------------------------------------

  /**
   * Called once with no team: the sidebar draws every project to build its tree,
   * and a query per team would be one request per row to render it.
   */
  projects: (opts: { teamId?: string; includeArchived?: boolean } = {}) =>
    request<Project[]>(
      `/api/projects${query({
        teamId: opts.teamId,
        includeDescendants: opts.teamId === undefined ? undefined : true,
        includeArchived: opts.includeArchived,
      })}`,
    ),

  createProject: (body: ProjectBody) =>
    request<Project>("/api/projects", { method: "POST", body: JSON.stringify(body) }),

  /** Omitting `teamId` is how a project becomes transverse. */
  updateProject: (id: string, body: ProjectBody) =>
    request<Project>(`/api/projects/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  projectContents: (id: string) => request<DispositionCounts>(`/api/projects/${id}/contents`),

  archiveProject: (id: string, plan: DispositionPlan) =>
    request<Project>(`/api/projects/${id}/archive`, { method: "PUT", body: JSON.stringify(plan) }),

  unarchiveProject: (id: string) =>
    request<Project>(`/api/projects/${id}/unarchive`, { method: "POST" }),

  deleteProject: (id: string, plan: DispositionPlan) =>
    request<void>(`/api/projects/${id}`, { method: "DELETE", body: JSON.stringify(plan) }),

  // --- tickets -------------------------------------------------------------

  /**
   * A team scope includes its descendants, so a parent shows the work of its
   * sub-teams. A project scope needs no team: a project may span several, or none.
   */
  tickets: (scope: Scope, includeArchived = false) =>
    request<Ticket[]>(
      `/api/tickets${query({
        limit: 200,
        teamId: scope.kind === "team" ? scope.id : undefined,
        includeDescendants: scope.kind === "team" ? true : undefined,
        projectId: scope.kind === "project" ? scope.id : undefined,
        includeArchived,
      })}`,
    ),

  createTicket: (body: {
    teamId: string;
    title: string;
    status?: TicketStatus;
    priority?: TicketPriority;
    projectId?: string;
    assigneeIds?: string[];
  }) => request<Ticket>("/api/tickets", { method: "POST", body: JSON.stringify(body) }),
```

- [ ] **Step 5: Rewrite the query keys**

In `apps/web/src/lib/queries.ts`, add the store import above the `./api` import:

```ts
import { useUi, type Scope } from "@/store/ui";
```

and replace the whole `keys` object with:

```ts
export const keys = {
  authMode: ["authMode"] as const,
  me: ["me"] as const,
  setupState: ["setupState"] as const,
  users: ["users"] as const,
  people: ["people"] as const,
  invitations: ["invitations"] as const,
  sync: ["sync"] as const,

  // Everything below is keyed on what was asked for, so two different answers
  // never share one cache entry. `applyEvent` invalidates on the first segment,
  // which is what lets these keys grow without it having to know about them.
  teams: (includeArchived: boolean) => ["teams", includeArchived] as const,
  /** All projects, one query — the sidebar needs the whole set to draw its tree. */
  projects: (includeArchived: boolean) => ["projects", includeArchived] as const,
  tickets: (scope: Scope, includeArchived: boolean) =>
    ["tickets", scope.kind, scope.kind === "all" ? "" : scope.id, includeArchived] as const,
  contents: (kind: "team" | "project", id: string) => ["contents", kind, id] as const,
};

/**
 * The scope and the archived toggle live in the store, not in props, so every
 * caller of these hooks agrees on what is being shown without passing it down.
 */
const useTicketsKey = () =>
  keys.tickets(
    useUi((state) => state.scope),
    useUi((state) => state.showArchived),
  );
```

`keys.teams` stops being a plain array, so anything that used it as one has to
change — step 8 below is that change.

- [ ] **Step 6: Rewrite the read hooks**

Replace `useTeams`:

```ts
export const useTeams = () => {
  const showArchived = useUi((state) => state.showArchived);
  return useQuery({ queryKey: keys.teams(showArchived), queryFn: () => api.teams(showArchived) });
};
```

Replace `useProjects` and `useTickets`, and add `useContents` after them:

```ts
export const useProjects = () => {
  const showArchived = useUi((state) => state.showArchived);
  return useQuery({
    queryKey: keys.projects(showArchived),
    queryFn: () => api.projects({ includeArchived: showArchived }),
  });
};

export const useTickets = () => {
  const scope = useUi((state) => state.scope);
  const showArchived = useUi((state) => state.showArchived);
  return useQuery({
    queryKey: keys.tickets(scope, showArchived),
    queryFn: () => api.tickets(scope, showArchived),
  });
};

/**
 * What a team or a project holds. The disposition modal exists to say what is in
 * there *now*, so a cached count is the one answer it must never be given.
 */
export const useContents = (kind: "team" | "project", id: string) =>
  useQuery({
    queryKey: keys.contents(kind, id),
    queryFn: () => (kind === "team" ? api.teamContents(id) : api.projectContents(id)),
    staleTime: 0,
    gcTime: 0,
  });
```

- [ ] **Step 7: Drop the `teamId` parameter from the ticket mutations**

The three signatures lose their argument and take the key from the store instead:

```ts
export function usePatchTicket() {
  const queryClient = useQueryClient();
  const key = useTicketsKey();
```

```ts
export function useCreateTicket() {
  const queryClient = useQueryClient();
  const key = useTicketsKey();
  return useMutation({
    mutationFn: api.createTicket,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: key });
      // The team row carries a ticket count, so it is stale too.
      queryClient.invalidateQueries({ queryKey: ["teams"] });
    },
  });
}

export function useDeleteTicket() {
  const queryClient = useQueryClient();
  const key = useTicketsKey();
```

Everything inside those three functions below those lines is unchanged.

- [ ] **Step 8: Add `useUnarchive` and fix `applyEvent`**

Add `type Project,` and `type Team,` to the type imports from `./api` (alphabetically,
between `Preferences` and `Ticket`), then insert before `applyEvent`:

```ts
/**
 * One hook for both entities: unarchiving asks nothing, so the only thing that
 * differs between a team and a project is which endpoint is called. Unarchiving a
 * team also unarchives its ancestors, which is why the ticket lists go stale too.
 */
export function useUnarchive() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (target: { kind: "team" | "project"; id: string }): Promise<Team | Project> =>
      target.kind === "team" ? api.unarchiveTeam(target.id) : api.unarchiveProject(target.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
    },
  });
}
```

The explicit `: Promise<Team | Project>` return annotation is load-bearing. Without
it TypeScript infers `Promise<Team> | Promise<Project>` from the ternary, which
`useMutation` rejects with *`Type 'Promise<Project>' is not assignable to type
'Promise<Team>'`*.

Then replace `applyEvent`:

```ts
/**
 * Applies a realtime event to the cache. Same effect whoever caused it.
 *
 * Matched on the first key segment, so every variant of a list — archived shown
 * or not, whichever scope — is invalidated by one call.
 */
export function applyEvent(queryClient: QueryClient, entity: string) {
  if (entity === "tickets") {
    queryClient.invalidateQueries({ queryKey: ["tickets"] });
  } else if (entity === "projects") {
    queryClient.invalidateQueries({ queryKey: ["projects"] });
  } else if (entity === "teams") {
    queryClient.invalidateQueries({ queryKey: ["teams"] });
  }
}
```

- [ ] **Step 9: Update the page's call sites**

In `apps/web/src/app/page.tsx`, delete the `const teamId = …` line added in task 9
and its comment, then drop the arguments:

```tsx
  const teams = useTeams();
  const tickets = useTickets();
  const projects = useProjects();
  const sync = useSyncStatus();

  const patch = usePatchTicket();
  const create = useCreateTicket();
  const remove = useDeleteTicket();
```

`currentTeam` reads the scope directly:

```tsx
  const currentTeam =
    scope.kind === "team" ? teams.data?.find((team) => team.id === scope.id) : undefined;
```

and `createTicket` too — its wrong-team fallback is a known defect that the composer
task fixes; do not fix it here, it would be an unreviewed behaviour change hidden in
a refactor:

```tsx
  const createTicket = useCallback(
    (title: string) => {
      const target = scope.kind === "team" ? scope.id : teams.data?.[0]?.id;
      if (!target) return;
      create.mutate({ teamId: target, title }, { onSuccess: () => close() });
    },
    [scope, teams.data, create, close],
  );
```

- [ ] **Step 10: Verify**

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web && pnpm exec tsc --noEmit && pnpm lint && pnpm test
```

Expected: `tsc` and `eslint` silent, `Tests 23 passed (23)`.

- [ ] **Step 11: Commit**

```bash
git add apps/web/src/lib/api.ts apps/web/src/lib/queries.ts apps/web/src/app/page.tsx
git commit -m "feat(web): call the disposition endpoints and key queries by scope

ApiError now carries the response body, which is how a 409 hands the modal the
counts it has to reopen on. Projects are fetched once for the whole instance:
the sidebar needs every one of them to draw its tree, and a query per team would
be one request per row."
```

---

### Task 11: `page.tsx` consumes the registry

The `switch` over `event.key` and the `commands` array both disappear. What is left
of the keyboard path is: resolve the key, ask `when`, run. The help overlay stops
being a hand-written table.

This task also lands the spec's second error channel — *"outside a dialog (archiving
from a menu): a dismissible line in the topbar"*. `unarchive` is the first action
wired into the context that runs from a menu with no dialog around it and nothing
optimistic to snap back: without this line a 403, a 409 or a dropped connection would
leave the menu looking as if it had simply done nothing.

`page.tsx` goes from 403 lines to 347, and the part that decides what is possible
moves into `src/lib/use-action-ctx.ts` — the file the spec lists as *assembles data,
mutations, permissions*.

**Files:**
- Create: `apps/web/src/lib/errors.ts`
- Create: `apps/web/src/lib/use-action-ctx.ts`
- Modify: `apps/web/src/app/page.tsx` (whole file — the version below replaces it)
- Modify: `apps/web/src/components/overlays.tsx:3-12,258-294` (import `shortcutRows`, rewrite `HelpOverlay`)
- Modify: `apps/web/src/app/globals.css:644-646` (append the `.topbar-error` rules after `.error`)
- Test: `apps/web/src/lib/errors.test.ts`. The rest is guarded by `tsc --noEmit`, `pnpm lint` and `src/lib/actions.test.ts` staying green; Playwright scenario 5 (a later task) is what proves the keyboard behaviour survived.

**Interfaces:**
- Consumes (task 8): `availableActions`, `resolveShortcut`, `shortcutRows`, `ActionContext`.
  (task 9): the store's `scope` / `dialog` / `setScope` / `openDialog`, and the `Scope` type.
  (task 10): `useTeams()`, `useProjects()`, `usePatchTicket()`, `useUnarchive()`,
  and `ApiError`'s `detail` / `status`.
- Produces:
  ```ts
  // apps/web/src/lib/errors.ts
  export function actionErrorMessage(error: unknown): string;

  // apps/web/src/lib/use-action-ctx.ts
  export const FILTER_INPUT_ID = "ticket-filter";
  export function useActionContext(local: {
    tickets: Ticket[];
    selected?: Ticket;
    move: (delta: number) => void;
    startRename: (id: string) => void;
    /** Where a failure with no dialog to land in goes. `null` clears it. */
    reportError: (message: string | null) => void;
  }): ActionContext;
  ```
  Plus one CSS class the later dialog and sidebar tasks can report into:
  `.topbar-error` (a flex row: message, then a borderless close button).

**Where the error message lives, and why.** In `page.tsx`, as
`useState<{ scope: Scope; message: string } | null>` — not in the `ui` store. It
describes the last thing *this view* tried, not a fact about the application, and the
store is read by the sidebar and every dialog, none of which have any business writing
to it. It stays reachable from the hook because `page.tsx` hands `reportError` down
through `useActionContext`'s `local` slice, exactly as it already does for `move` and
`startRename`. The scope is stored *with* the message so a scope change makes it stop
applying by derivation rather than by an effect firing after the fact.

---

- [ ] **Step 1: Write the failing test**

Create `apps/web/src/lib/errors.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { ApiError } from "./api";
import { actionErrorMessage } from "./errors";

describe("actionErrorMessage", () => {
  it("prefers the problem document's detail, which is written for a person", () => {
    const error = new ApiError(403, "Only an admin may change teams");
    expect(actionErrorMessage(error)).toBe("Only an admin may change teams");
  });

  it("ignores the problem body, which only the disposition modal reads", () => {
    const error = new ApiError(409, "The contents changed since they were counted", {
      counts: { subTeams: 2, projects: 3, tickets: 50 },
    });
    expect(actionErrorMessage(error)).toBe("The contents changed since they were counted");
  });

  it("names the status when the server sent no detail at all", () => {
    expect(actionErrorMessage(new ApiError(409, ""))).toBe("Request failed (409)");
  });

  it("reports a transport failure rather than staying silent", () => {
    expect(actionErrorMessage(new TypeError("Failed to fetch"))).toBe("Failed to fetch");
  });

  it("still says something for a value that is not an error at all", () => {
    expect(actionErrorMessage("boom")).toBe("Something went wrong");
    expect(actionErrorMessage(undefined)).toBe("Something went wrong");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/elietreport/Projet/Perso/Kanso/apps/web && pnpm exec vitest run src/lib/errors.test.ts`

Expected: FAIL with

```
 FAIL  src/lib/errors.test.ts [ src/lib/errors.test.ts ]
Error: Cannot find module './errors' imported from /…/apps/web/src/lib/errors.test.ts

 Test Files  1 failed (1)
      Tests  no tests
```

- [ ] **Step 3: Turn a failure into a sentence**

Create `apps/web/src/lib/errors.ts`:

```ts
import { ApiError } from "./api";

/**
 * The sentence to put in front of someone when an action failed.
 *
 * The API answers with RFC 7807, and `ApiError.detail` is the field of that
 * document written for a person to read; `body` carries the rest of it, which
 * matters to the disposition modal and to nothing else. A failure that never
 * reached the server has only a technical message, and showing that is still
 * better than a menu that appears to do nothing.
 */
export function actionErrorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.detail || `Request failed (${error.status})`;
  if (error instanceof Error && error.message) return error.message;
  return "Something went wrong";
}
```

Its own module rather than a helper inside `use-action-ctx.ts`: that file is a
`"use client"` hook pulling in React and react-query, and this is a pure function that
should be testable without any of it.

- [ ] **Step 4: Assemble the context in its own hook**

Create `apps/web/src/lib/use-action-ctx.ts`:

```ts
"use client";

import { useCallback, useMemo } from "react";
import { useUi } from "@/store/ui";
import type { ActionContext } from "./actions";
import type { Ticket } from "./api";
import { actionErrorMessage } from "./errors";
import { useMe, usePatchTicket, useProjects, useTeams, useUnarchive } from "./queries";

/** The id the filter input carries, so focusing it needs no React ref. */
export const FILTER_INPUT_ID = "ticket-filter";

/**
 * Assembles the one object every action runs against: the loaded data, the
 * mutations, the store's setters, and the permission the server enforces anyway.
 *
 * What it cannot know is list-local — which rows survived the filter, which one the
 * cursor is on, how the cursor moves, where a failure is displayed — so the page
 * passes those in.
 */
export function useActionContext(local: {
  tickets: Ticket[];
  selected?: Ticket;
  move: (delta: number) => void;
  startRename: (id: string) => void;
  /** Where a failure with no dialog to land in goes. `null` clears it. */
  reportError: (message: string | null) => void;
}): ActionContext {
  const { scope, setScope, open, close, openDialog } = useUi();
  const me = useMe();
  const teams = useTeams();
  const projects = useProjects();
  const { mutate: patchTicket } = usePatchTicket();
  const { mutate: unarchiveEntity } = useUnarchive();

  // A ref would tie this hook to one component's tree, and a context holding one
  // cannot be read while rendering. The filter is a single element; its id is
  // what makes it reachable from a command.
  const focusFilter = useCallback(() => document.getElementById(FILTER_INPUT_ID)?.focus(), []);

  const role = me.data?.user.instanceRole;
  const canConfigure = role === "owner" || role === "admin";

  const { tickets, selected, move, startRename, reportError } = local;

  return useMemo(
    () => ({
      scope,
      teams: teams.data ?? [],
      projects: projects.data ?? [],
      tickets,
      selected,
      canConfigure,
      open,
      close,
      openDialog,
      setScope,
      move,
      focusFilter,
      startRename,
      // Left alone on purpose: a patch is optimistic, so a failure is already
      // visible as the row snapping back to what it was.
      patchTicket,
      // Unarchiving is run from a menu, with no dialog to report into and nothing
      // optimistic to snap back, so its 403s and 409s go to the top bar instead.
      unarchive: (target) =>
        unarchiveEntity(target, {
          onError: (error) => reportError(actionErrorMessage(error)),
          onSuccess: () => reportError(null),
        }),
    }),
    [
      scope,
      teams.data,
      projects.data,
      tickets,
      selected,
      canConfigure,
      open,
      close,
      openDialog,
      setScope,
      move,
      focusFilter,
      startRename,
      reportError,
      patchTicket,
      unarchiveEntity,
    ],
  );
}
```

Three things that are not obvious:

- `focusFilter` reaches the input by `id` rather than through a `useRef`. That is not
  a style preference: the palette calls `availableActions(ctx)` during render, and
  ESLint's `react-hooks/refs` rule fails the build with *"Cannot access refs during
  render — passing a ref to a function may read its value during render"* if the
  context closes over one. An id has no such problem, and there is exactly one filter
  input on the page.
- The two mutations are destructured to their `mutate` (`const { mutate: patchTicket }
  = usePatchTicket()`). Keeping the whole result and writing `unarchive.mutate(…)`
  inside the memo makes `react-hooks/exhaustive-deps` ask for `unarchive` itself,
  whose identity changes on every mutation state transition — the memo would rebuild
  constantly. `mutate` is stable across renders, so depending on it directly is both
  correct and quiet.
- `useUnarchive()`'s per-call callbacks carry the reporting, so `queries.ts` from task
  10 stays untouched. The same hook can therefore be reused by the sidebar's `⋯` menu
  later without inheriting this page's idea of where errors go.

`useTeams()` and `useProjects()` are called here as well as in the page; react-query
dedupes them by key, so that is one request, not two.

- [ ] **Step 5: Give the error line a place to render**

`.error` in `globals.css` is only `color: var(--urgent)` — the right colour, no
geometry. Keep it for the colour and add the row it sits in. In
`apps/web/src/app/globals.css`, immediately after the existing

```css
.error {
  color: var(--urgent);
}
```

append:

```css
/*
 * Where an action run from a menu reports its failure: there is no dialog to put
 * the message under, and no toast system to grow. Its own line rather than a cell
 * in the top bar, because sharing that row with the title, the filter and the New
 * button would truncate exactly the sentence that needs reading.
 */
.topbar-error {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: var(--bar-pad-sm) var(--gutter);
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}

.topbar-error span {
  flex: 1;
}

.topbar-error button {
  border: none;
  background: none;
  color: inherit;
  border-radius: var(--radius);
  padding: 2px 7px;
  line-height: 1;
}

.topbar-error button:hover {
  background: var(--surface-hover);
}
```

Every custom property used here already exists: `--bar-pad-sm` and `--gutter` are
what `.statusbar` is padded with, `--border` is the divider `.topbar` itself uses,
and `--radius` / `--surface-hover` are what `.button` uses.

- [ ] **Step 6: Generate the help overlay**

In `apps/web/src/components/overlays.tsx`, add the registry import above the
`@/lib/api` import:

```ts
import { shortcutRows } from "@/lib/actions";
```

and replace the whole `HelpOverlay` function with:

```tsx
export function HelpOverlay({ onClose }: { onClose: () => void }) {
  return (
    <Backdrop onClose={onClose}>
      <div className="panel-header">
        <strong style={{ flex: 1 }}>Keyboard</strong>
        <button className="button" onClick={onClose}>
          Close
        </button>
      </div>
      <div className="panel-body">
        <div className="shortcuts">
          {shortcutRows().map((row) => (
            <div key={row.keys} style={{ display: "contents" }}>
              <kbd>{row.keys}</kbd>
              <span>{row.label}</span>
            </div>
          ))}
          {/*
            The two keys the registry cannot own: the palette is a modified key,
            resolved before the registry is consulted, and Escape is not an action
            but the way out of whatever is on top of the list.
          */}
          <div style={{ display: "contents" }}>
            <kbd>⌘K / Ctrl+K</kbd>
            <span>Command palette</span>
          </div>
          <div style={{ display: "contents" }}>
            <kbd>Esc</kbd>
            <span>Close</span>
          </div>
        </div>
      </div>
    </Backdrop>
  );
}
```

Those two literal rows are the honest shape of the code: both keys really are handled
outside the registry, in the page's own `keydown` handler. Everything the registry
owns is generated, so nothing it owns can drift.

- [ ] **Step 7: Rewrite `page.tsx`**

Replace the whole of `apps/web/src/app/page.tsx` with:

```tsx
"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { LoginScreen } from "@/components/login";
import { CommandPalette, Composer, DetailPanel, HelpOverlay } from "@/components/overlays";
import { SettingsPanel } from "@/components/settings/panel";
import { Sidebar } from "@/components/sidebar";
import { TicketList } from "@/components/tickets";
import { availableActions, resolveShortcut } from "@/lib/actions";
import { ApiError, getDevUser, setDevUser, type Ticket } from "@/lib/api";
import {
  useAuthMode,
  useCreateTicket,
  useDeleteTicket,
  useMe,
  usePatchTicket,
  usePreferences,
  useProjects,
  useSetupState,
  useSyncStatus,
  useTeams,
  useTickets,
} from "@/lib/queries";
import { FILTER_INPUT_ID, useActionContext } from "@/lib/use-action-ctx";
import { useUi, type Scope } from "@/store/ui";

const isTypingTarget = (target: EventTarget | null) =>
  target instanceof HTMLElement &&
  (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.tagName === "SELECT");

export default function InboxPage() {
  const router = useRouter();
  const authMode = useAuthMode();
  const me = useMe();
  const setup = useSetupState();
  const preferences = usePreferences();

  const { scope, selectedId, overlay, dialog, query, setScope, select, open, close, setQuery } =
    useUi();
  const [editingId, setEditingId] = useState<string | undefined>();
  const [actionError, setActionError] = useState<{ scope: Scope; message: string } | null>(null);

  const teams = useTeams();
  const tickets = useTickets();
  const projects = useProjects();
  const sync = useSyncStatus();

  const patch = usePatchTicket();
  const create = useCreateTicket();
  const remove = useDeleteTicket();

  const visible = useMemo(() => {
    const rows = tickets.data ?? [];
    const needle = query.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter(
      (ticket) =>
        ticket.title.toLowerCase().includes(needle) ||
        ticket.identifier.toLowerCase().includes(needle),
    );
  }, [tickets.data, query]);

  // The cursor follows the list: when a filter or a realtime update removes the
  // selected row, land on something sensible rather than losing the selection.
  useEffect(() => {
    if (visible.length === 0) {
      if (selectedId) select(undefined);
      return;
    }
    if (!selectedId || !visible.some((ticket) => ticket.id === selectedId)) {
      select(visible[0].id);
    }
  }, [visible, selectedId, select]);

  /**
   * An instance without an owner has nothing to show, and someone who has never been
   * through the preferences step is sent to pick them once. Both answers come from
   * the setup endpoint: on a backend that predates the wizard it 404s, and pushing
   * anyone towards a route that does not exist there is worse than a working list.
   */
  const needsSetup =
    setup.data !== undefined &&
    (setup.data.needsOwner || (me.data !== undefined && !me.data.preferences.onboardedAt));

  useEffect(() => {
    if (needsSetup) router.replace("/setup");
  }, [needsSetup, router]);

  const selected: Ticket | undefined = visible.find((ticket) => ticket.id === selectedId);
  const currentTeam =
    scope.kind === "team" ? teams.data?.find((team) => team.id === scope.id) : undefined;

  const move = useCallback(
    (delta: number) => {
      if (visible.length === 0) return;
      const index = visible.findIndex((ticket) => ticket.id === selectedId);
      const next = Math.min(Math.max((index < 0 ? 0 : index) + delta, 0), visible.length - 1);
      select(visible[next].id);
    },
    [visible, selectedId, select],
  );

  const startRename = useCallback((id: string) => setEditingId(id), []);

  const createTicket = useCallback(
    (title: string) => {
      const target = scope.kind === "team" ? scope.id : teams.data?.[0]?.id;
      if (!target) return;
      create.mutate({ teamId: target, title }, { onSuccess: () => close() });
    },
    [scope, teams.data, create, close],
  );

  /**
   * A failure belongs to the view it happened in, so the scope it was reported
   * against is stored with it and a scope change simply stops it applying. Clearing
   * it from an effect instead would leave one render showing a sentence about a team
   * nobody is looking at any more.
   */
  const reportError = useCallback(
    (message: string | null) => setActionError(message === null ? null : { scope, message }),
    [scope],
  );
  const shownError = actionError?.scope === scope ? actionError.message : null;

  const ctx = useActionContext({ tickets: visible, selected, move, startRename, reportError });

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      // A modified key, so it never reaches the registry, which only owns bare ones.
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        open("palette");
        return;
      }

      // Overlays and dialogs own their own keys; the list must not react behind them.
      if (overlay !== "none" || dialog.kind !== "none" || editingId || isTypingTarget(event.target)) {
        if (event.key === "Escape") {
          close();
          setEditingId(undefined);
          (event.target as HTMLElement | null)?.blur?.();
        }
        return;
      }
      if (event.metaKey || event.ctrlKey || event.altKey) return;

      const action = resolveShortcut(event.key);
      // One predicate answers both "may I show this" and "may I run it", so a key
      // whose action is unavailable stays inert rather than half-firing.
      if (!action || !action.when(ctx)) return;
      event.preventDefault();
      action.run(ctx);
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [ctx, overlay, dialog, editingId, open, close]);

  const commands = useMemo(
    () => [
      ...availableActions(ctx).map((action) => ({
        id: action.id,
        label: action.label,
        hint: action.shortcut?.split(" ")[0],
        run: () => action.run(ctx),
      })),
      // Teams are rows from the server, so no static registry can enumerate them.
      ...(teams.data ?? []).map((team) => ({
        id: `view.team.${team.id}`,
        label: `View team: ${team.name}`,
        run: () => {
          setScope({ kind: "team", id: team.id });
          close();
        },
      })),
    ],
    [ctx, teams.data, setScope, close],
  );

  if (me.isLoading || authMode.isLoading || setup.isLoading) {
    return <div className="centered">Loading…</div>;
  }

  // Ahead of the sign-in screen: with no owner yet there is nobody to sign in as.
  if (needsSetup) {
    return <div className="centered">Opening setup…</div>;
  }

  if (me.error instanceof ApiError && me.error.status === 401) {
    return <LoginScreen mode={authMode.data} />;
  }

  const mirrorSummary = !sync.data
    ? ""
    : sync.data.mirrorEnabled
      ? `Notion: ${sync.data.bootstrapped ? "connected" : "not bootstrapped"}${
          sync.data.jobs.pending ? ` · ${sync.data.jobs.pending} queued` : ""
        }${sync.data.failed.length ? ` · ${sync.data.failed.length} failed` : ""}`
      : "Notion mirror off";

  return (
    <div className="shell" data-sidebar={preferences.sidebarVisible ? "shown" : "hidden"}>
      {preferences.sidebarVisible && (
        <Sidebar
          teams={teams.data ?? []}
          scope={scope}
          onSelectScope={setScope}
          syncSummary={mirrorSummary}
        />
      )}

      <div className="main">
        <div className="topbar">
          <h1>{currentTeam ? currentTeam.name : "All tickets"}</h1>
          <span style={{ color: "var(--text-faint)", fontSize: 11 }}>{visible.length}</span>
          <span className="spacer" />
          <input
            id={FILTER_INPUT_ID}
            className="filter-input"
            placeholder="Filter…  /"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                setQuery("");
                event.currentTarget.blur();
              }
            }}
          />
          <button className="button" onClick={() => open("composer")}>
            New <kbd>c</kbd>
          </button>
        </div>

        {shownError && (
          <div className="topbar-error error" role="alert">
            <span>{shownError}</span>
            <button
              type="button"
              aria-label="Dismiss this message"
              title="Dismiss"
              onClick={() => setActionError(null)}
            >
              ×
            </button>
          </div>
        )}

        {tickets.error ? (
          <div className="empty error">{(tickets.error as Error).message}</div>
        ) : (
          <TicketList
            tickets={visible}
            selectedId={selectedId}
            editingId={editingId}
            onSelect={select}
            onOpen={(id) => {
              select(id);
              open("detail");
            }}
            onRename={(id, title) => {
              patch.mutate({ id, title });
              setEditingId(undefined);
            }}
            onCancelEdit={() => setEditingId(undefined)}
          />
        )}

        {preferences.showStatusBar && (
          <div className="statusbar">
            <span>
              <kbd>j</kbd> <kbd>k</kbd> move
            </span>
            <span>
              <kbd>1</kbd>–<kbd>6</kbd> status
            </span>
            <span>
              <kbd>c</kbd> new
            </span>
            <span>
              <kbd>⌘K</kbd> commands
            </span>
            <span>
              <kbd>,</kbd> settings
            </span>
            <span>
              <kbd>?</kbd> help
            </span>
            <span style={{ flex: 1 }} />
            {me.data && <DevUserSwitcher email={me.data.user.email} />}
          </div>
        )}
      </div>

      {overlay === "composer" && (
        <Composer onCreate={createTicket} onClose={close} pending={create.isPending} />
      )}
      {overlay === "palette" && <CommandPalette commands={commands} onClose={close} />}
      {overlay === "help" && <HelpOverlay onClose={close} />}
      {overlay === "settings" && <SettingsPanel onClose={close} />}
      {overlay === "detail" && selected && (
        <DetailPanel
          ticket={selected}
          projects={projects.data ?? []}
          onPatch={(body) => patch.mutate({ id: selected.id, ...body })}
          onDelete={() => {
            remove.mutate(selected.id);
            close();
          }}
          onClose={close}
        />
      )}
    </div>
  );
}

/**
 * Dev-mode affordance only: act as somebody else without an OAuth round trip, so
 * two-user behaviour (realtime, assignment) can be exercised from one browser.
 */
function DevUserSwitcher({ email }: { email: string }) {
  const authMode = useAuthMode();
  const [value, setValue] = useState(getDevUser() ?? "");

  if (authMode.data?.mode !== "dev") return <span>{email}</span>;

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setDevUser(value.trim() || null);
        window.location.reload();
      }}
      style={{ display: "flex", gap: 6, alignItems: "center" }}
    >
      <span title="Dev auth: identity comes from a header, nothing is verified">dev as</span>
      <input
        style={{ width: 180, padding: "2px 6px", fontSize: 11 }}
        placeholder={email}
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
    </form>
  );
}
```

Four things that are not obvious from the diff:

- The keydown guard now also checks `dialog.kind !== "none"`. Dialogs are not
  overlays in the store, and without this a keystroke typed behind an open dialog
  would move the cursor in the list underneath it.
- The palette list is `availableActions(ctx)` **plus** one entry per team. A static
  registry cannot enumerate rows that come from the server, and dropping those entries
  would take away the only keyboard path to changing team.
- `shownError` is derived, not stored. Writing `useEffect(() => setActionError(null),
  [scope])` instead fails `pnpm lint` outright — *"Avoid calling setState() directly
  within an effect"*, `react-hooks/set-state-in-effect` — and would in any case paint
  one frame of the stale message before clearing it.
- The message clears three ways, and all three are required by the spec: the close
  button (`setActionError(null)`), a successful `unarchive` (`reportError(null)` in
  the hook), and a scope change (the `actionError.scope === scope` comparison, which
  makes silent carry-over impossible rather than merely unlikely).

- [ ] **Step 8: Verify**

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web && pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build
```

Expected: `tsc` and `eslint` silent, `Test Files 2 passed (2)` / `Tests 28 passed
(28)`, and the build listing the five routes (`/`, `/_not-found`, `/login`,
`/settings`, `/setup`).

Confirm the two things this task was for:

```bash
cd /Users/elietreport/Projet/Perso/Kanso && grep -c "" apps/web/src/app/page.tsx
```
Expected: `347` (it was 403).

```bash
cd /Users/elietreport/Projet/Perso/Kanso && grep -n "switch (event.key)\|statusLabel\|TICKET_PRIORITIES\|TICKET_STATUSES" apps/web/src/app/page.tsx
```
Expected: no output — the `switch` and the hand-built command list are gone.

- [ ] **Step 9: Commit**

```bash
git add apps/web/src/lib/errors.ts apps/web/src/lib/errors.test.ts apps/web/src/lib/use-action-ctx.ts apps/web/src/app/page.tsx apps/web/src/app/globals.css apps/web/src/components/overlays.tsx
git commit -m "refactor(web): drive the keyboard and the palette from the registry

page.tsx defined every action twice, once in a switch over event.key and once in
a commands array. Both are gone: a key resolves to an action, \`when\` decides, and
the same list feeds the palette and the help overlay. ⌘K stays in the page — it
is a modified key, and the registry only owns bare ones.

An action run from a menu has no dialog to report into, so unarchive's failures
land on a dismissible line in the top bar. The message carries the scope it was
reported against, which is what stops it outliving the view it describes."
```


## Part 3 — The interface, and the E2E net (tasks 12–17)

> **Interface language.** The spec's mockups write the disposition dialog in French
> (« Archiver », « Garder actives »…) and the section header `Projets`. The rest of
> the application is entirely in English: `sidebar.tsx` ("Views", "All tickets",
> "Teams", "No team yet"), `overlays.tsx` ("New ticket…", "Close"),
> `settings/panel.tsx` ("Settings", "Appearance"), `tickets.tsx` ("Nothing here. Press
> c…"). Shipping a French dialog inside an English application would be a defect, not
> fidelity. **The strings below are therefore in English, and the layout follows the
> mockup to the pixel.** The Playwright tests of task 17 target those strings.

> **Development loop** (needed for every manual verification in tasks 12 to 16). From
> the repository root:
>
> ```bash
> cd /Users/elietreport/Projet/Perso/Kanso
> KANSO_AUTH_MODE=dev docker compose up -d --wait db api
> cd apps/web && pnpm dev
> ```
>
> Then open <http://localhost:3000>. On the very first run the application redirects
> to `/setup`: complete the wizard once with the address `owner@kanso.test`, and that
> account becomes `owner`, which is what lets it administer teams. To act as someone
> else, open a private browsing window, run
> `localStorage.setItem("kanso.devUser", "member@kanso.test")` in the console and
> reload: `dev` mode creates the account on the spot with the `member` role.
> The docker compose `web` service is deliberately left down here — it holds the port
> 3000 that `pnpm dev` wants.

---

### Task 12: Shared dialog components — `Field`, `DialogFrame`, `Menu`

**Files:**
- Create: `apps/web/src/components/dialogs/field.tsx`
- Create: `apps/web/src/components/menu.tsx`
- Modify: `apps/web/src/app/globals.css:415-548` (adds a `--- dialogs ---` block and a
  `--- menu ---` block after the `--- overlays ---` block, plus two rules in the
  `--- misc ---` block)
- Test: no unit test — the contract only provides tooling for pure logic (`vitest`,
  `environment: "node"`). These components render React: their verification is `tsc`,
  `eslint` and the manual procedure in step 5.

**Interfaces:**
- Consumes: nothing from earlier tasks. The existing CSS classes reused are
  `.backdrop`, `.panel`, `.panel-header`, `.button`, `.button-primary`, `.empty`, and
  the variables `--border`, `--surface`, `--surface-hover`, `--text`, `--text-dim`,
  `--text-faint`, `--urgent`, `--accent`, `--radius`, `--mono` from `globals.css`.
- Produces:
  ```ts
  // apps/web/src/components/dialogs/field.tsx
  export function Field(props: {
    label: string; error?: string | null; hint?: string; children: React.ReactNode;
  }): React.ReactElement
  export function DialogFrame(props: {
    title: string; onClose: () => void; onSubmit: () => void; submitLabel: string;
    submitDanger?: boolean; pending?: boolean; error?: string | null;
    children: React.ReactNode;
  }): React.ReactElement

  // apps/web/src/components/menu.tsx
  export type MenuItem = { id: string; label: string; danger?: boolean; onSelect: () => void };
  export function Menu(props: { label: string; items: MenuItem[] }): React.ReactElement | null
  ```
  New CSS classes, available to tasks 13 through 16: `.dialog-body`,
  `.dialog-field`, `.dialog-field-label`, `.dialog-field-hint`, `.dialog-field-error`,
  `.dialog-footer`, `.dialog-footer-error`, `.dialog-footer-spacer`, `.button-danger`,
  `.menu`, `.menu-trigger`, `.menu-popover`, `.menu-item`.

> **A note on the return type.** The contract writes `JSX.Element`. Under React 19 the
> global `JSX` namespace no longer exists (it lives under `React.JSX`). The components
> below therefore leave their return type unannotated and let TypeScript infer it,
> which is what the rest of the `components/` folder already does.

- [ ] **Step 1: Create the folder and the `field.tsx` file**

```bash
mkdir -p /Users/elietreport/Projet/Perso/Kanso/apps/web/src/components/dialogs
```

Create `apps/web/src/components/dialogs/field.tsx`:

```tsx
"use client";

import { useEffect, useRef, type ReactNode } from "react";

/**
 * One dialog field: its label, its control, and a line of hint or error underneath.
 *
 * The label and the control are wrapped in a `<label>`, which associates them without
 * going through an `id`. The hint and the error are deliberately *outside* that
 * `<label>`: inside, they would enter the control's accessible name, and a `<select>`
 * would end up called "Parent team Left empty, the server derives it…". The error
 * carries `role="alert"` so it is announced when it appears.
 *
 * `aria-invalid` stays the caller's job, on the caller's own control: this component
 * does not reach into the children it is handed.
 */
export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string | null;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <div className="dialog-field">
      <label className="dialog-field-label">
        <span>{label}</span>
        {children}
      </label>
      {error ? (
        <span className="dialog-field-error" role="alert">
          {error}
        </span>
      ) : hint ? (
        <span className="dialog-field-hint">{hint}</span>
      ) : null}
    </div>
  );
}

/**
 * The wrapper the three dialogs share: the backdrop, the panel, the title, the body
 * and the two buttons.
 *
 * It rewrites the four lines of `.backdrop > .panel` rather than borrowing the
 * `Backdrop` from `overlays.tsx`, because it needs three things that one does not
 * take: `role="dialog"`, a `tabIndex` so it can own the focus, and a `keydown`
 * boundary. Five optional props on `Backdrop` would cost more than these four lines.
 *
 * `pending` disables both buttons. It covers the two cases where submitting makes no
 * sense: the request is in flight, or the data the dialog edits is not there yet.
 */
export function DialogFrame({
  title,
  onClose,
  onSubmit,
  submitLabel,
  submitDanger,
  pending,
  error,
  children,
}: {
  title: string;
  onClose: () => void;
  onSubmit: () => void;
  submitLabel: string;
  submitDanger?: boolean;
  pending?: boolean;
  error?: string | null;
  children: ReactNode;
}) {
  const panelRef = useRef<HTMLDivElement>(null);

  /**
   * The dialog takes the focus if it does not already hold it. Without this, a dialog
   * opened from a menu would leave the focus on the document body, and Escape would
   * go to the `window` handler in `page.tsx`, which does not read it as a dialog
   * keystroke. Fields carrying `autoFocus` win: they focused during the same commit,
   * before this effect ran.
   */
  useEffect(() => {
    const panel = panelRef.current;
    if (panel && !panel.contains(document.activeElement)) panel.focus();
  }, []);

  return (
    <div className="backdrop" onClick={onClose}>
      <div
        ref={panelRef}
        className="panel"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          event.stopPropagation();
          if (event.key === "Escape") {
            event.preventDefault();
            onClose();
          }
        }}
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!pending) onSubmit();
          }}
        >
          <div className="panel-header">
            <strong style={{ flex: 1 }}>{title}</strong>
          </div>

          <div className="dialog-body">{children}</div>

          <div className="dialog-footer">
            {error ? (
              <span className="dialog-footer-error" role="alert">
                {error}
              </span>
            ) : (
              <span className="dialog-footer-spacer" />
            )}
            <button type="button" className="button" onClick={onClose} disabled={pending}>
              Cancel
            </button>
            <button
              type="submit"
              className={submitDanger ? "button button-danger" : "button button-primary"}
              disabled={pending}
            >
              {submitLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create `menu.tsx`**

Create `apps/web/src/components/menu.tsx`:

```tsx
"use client";

import { useEffect, useId, useRef, useState } from "react";

export type MenuItem = {
  id: string;
  label: string;
  /** Last, detached, never the default choice. */
  danger?: boolean;
  onSelect: () => void;
};

/**
 * A row's `⋯` menu.
 *
 * Nothing like it exists in the application today — no dropdown, no context menu — so
 * everything is written here: the roving focus pattern (`tabindex` 0 on the current
 * entry, −1 on the others), closing on an outside click, and stopping key propagation
 * so the `window` handler in `page.tsx` does not move the list cursor while someone
 * is walking the menu.
 *
 * An empty list renders nothing: that is what makes a member see no `⋯` at all on a
 * team row, without the caller having to know about it.
 */
export function Menu({ label, items }: { label: string; items: MenuItem[] }) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const menuId = useId();

  /**
   * In the capture phase: a click on another row's `⋯` closes this one before that
   * one opens, rather than leaving two menus open.
   */
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => document.removeEventListener("pointerdown", onPointerDown, true);
  }, [open]);

  // The focus follows the highlight: it is what brings Escape and the arrows here.
  useEffect(() => {
    if (open) itemRefs.current[active]?.focus();
  }, [open, active]);

  if (items.length === 0) return null;

  const dismiss = () => {
    setOpen(false);
    triggerRef.current?.focus();
  };

  return (
    <div className="menu" ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className="menu-trigger"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={(event) => {
          // The row underneath changes the scope when it is clicked.
          event.stopPropagation();
          setActive(0);
          setOpen((current) => !current);
        }}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            event.stopPropagation();
            setActive(event.key === "ArrowDown" ? 0 : items.length - 1);
            setOpen(true);
          }
        }}
      >
        ⋯
      </button>

      {open && (
        <div
          id={menuId}
          className="menu-popover"
          role="menu"
          aria-label={label}
          onKeyDown={(event) => {
            // `page.tsx` listens on window: without this stop, every arrow would
            // also move the cursor in the ticket list.
            event.stopPropagation();
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setActive((index) => (index + 1) % items.length);
            } else if (event.key === "ArrowUp") {
              event.preventDefault();
              setActive((index) => (index - 1 + items.length) % items.length);
            } else if (event.key === "Home") {
              event.preventDefault();
              setActive(0);
            } else if (event.key === "End") {
              event.preventDefault();
              setActive(items.length - 1);
            } else if (event.key === "Escape") {
              event.preventDefault();
              dismiss();
            } else if (event.key === "Tab") {
              // Tabbing out closes the menu; the focus move itself is left alone.
              setOpen(false);
            }
            // Enter and Space are not intercepted: the entries are `<button>`s, and
            // the browser already fires them.
          }}
        >
          {items.map((item, index) => (
            <button
              key={item.id}
              ref={(node) => {
                itemRefs.current[index] = node;
              }}
              type="button"
              role="menuitem"
              className="menu-item"
              data-danger={item.danger ? "true" : undefined}
              tabIndex={index === active ? 0 : -1}
              onMouseEnter={() => setActive(index)}
              onClick={(event) => {
                event.stopPropagation();
                setOpen(false);
                item.onSelect();
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Add the `dialogs` and `menu` blocks to `globals.css`**

In `apps/web/src/app/globals.css`, find the end of the `--- overlays ---` block, that
is these lines (415 to 513 in the file as it stands):

```css
.palette-option .hint {
  margin-left: auto;
  color: var(--text-faint);
  font-size: 11px;
}

/* --- misc ---------------------------------------------------------------- */
```

Insert between the two, that is just before the `--- misc ---` comment:

```css
/* --- dialogs -------------------------------------------------------------- */

/*
 * Dialogs have a body of their own rather than `.panel-body`. That one uppercases
 * every `label` it contains, at a specificity (`.panel-body label`) higher than a
 * bare class: reused here, it would have to be contradicted field by field. A class
 * of one's own costs eight lines and closes the subject.
 */
.dialog-body {
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 60vh;
  overflow-y: auto;
}

/* The panel takes the focus to receive Escape; it has no need to announce it. */
.panel[tabindex]:focus {
  outline: none;
}

.dialog-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dialog-field-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dialog-field-label > span {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--text-faint);
}

.dialog-field-hint {
  font-size: 11px;
  color: var(--text-faint);
}

.dialog-field-error {
  font-size: 11px;
  color: var(--urgent);
}

.dialog-footer {
  padding: 10px 14px;
  border-top: 1px solid var(--border);
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Holds the error's place when there is none, so the two buttons stay pinned to the
   right edge either way. */
.dialog-footer-spacer {
  flex: 1;
}

.dialog-footer-error {
  flex: 1;
  min-width: 0;
  color: var(--urgent);
  font-size: 11px;
}

/* --- menu ----------------------------------------------------------------- */

.menu {
  position: relative;
  flex-shrink: 0;
}

.menu-trigger {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  line-height: 1;
  color: var(--text-faint);
}

.menu-trigger:hover,
.menu-trigger[aria-expanded="true"] {
  background: var(--surface-hover);
  color: var(--text);
}

.menu-popover {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  z-index: 30;
  min-width: 168px;
  padding: 4px;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: 0 10px 28px rgb(0 0 0 / 22%);
}

.menu-item {
  display: block;
  width: 100%;
  padding: 6px 8px;
  border-radius: 4px;
  text-align: left;
  white-space: nowrap;
  color: var(--text-dim);
}

.menu-item:hover,
.menu-item:focus-visible {
  background: var(--surface-hover);
  color: var(--text);
  outline: none;
}

/*
 * The destructive entry is set apart here rather than with an element of its own: an
 * `<hr>` between two `role="menuitem"` would break the relationship a menu is
 * expected to have with its entries.
 */
.menu-item[data-danger="true"] {
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
  color: var(--urgent);
}
```

- [ ] **Step 4: Add `.button-danger` and the disabled state to the `--- misc ---` block**

Still in `apps/web/src/app/globals.css`, find:

```css
.button-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}
```

and replace it with:

```css
.button-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}

/*
 * The red is written out rather than taken from the theme: `--urgent` is the same
 * value in both schemes, and white on top of it holds contrast on both sides, which
 * `--accent-contrast` would not guarantee here.
 */
.button-danger {
  background: var(--urgent);
  border-color: var(--urgent);
  color: #ffffff;
}

.button-danger:hover {
  background: color-mix(in srgb, var(--urgent) 85%, black);
}

.button:disabled {
  opacity: 0.55;
  cursor: default;
}

.button:disabled:hover {
  background: var(--surface);
}

.button-primary:disabled:hover {
  background: var(--accent);
}

.button-danger:disabled:hover {
  background: var(--urgent);
}
```

- [ ] **Step 5: Verify**

Types and style:

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web
pnpm exec tsc --noEmit
pnpm lint
```

Both must come back clean. `tsc` says nothing when it is happy; `pnpm lint` prints
`✔ No ESLint warnings or errors`, or nothing at all.

At this point neither component is mounted anywhere yet: there is nothing to click.
The visual check happens in task 13, where the `Menu` appears in the sidebar, and in
task 14, where `DialogFrame` shows up. Do not add a demonstration screen for the
occasion.

- [ ] **Step 6: Commit**

```bash
cd /Users/elietreport/Projet/Perso/Kanso
git add apps/web/src/components/dialogs/field.tsx apps/web/src/components/menu.tsx apps/web/src/app/globals.css
git commit -m "feat(web): add shared dialog frame, field and keyboard-navigable menu"
```

---

### Task 13: Tree sidebar — teams, projects, row menus and archived rows

**Files:**
- Modify: `apps/web/src/components/sidebar.tsx:1-85` (full rewrite of the file)
- Modify: `apps/web/src/app/globals.css:229-262` (replaces the `.nav-item` …
  `.nav-depth-2` block) plus rules appended after it
- Modify: `apps/web/src/app/page.tsx:276-282` (the `<Sidebar …/>` element)
- Test: no unit test (React rendering). Verified by `tsc`, `eslint` and the manual
  procedure in step 5; automated coverage comes from scenarios 1, 2 and 3 of task 17.

**Interfaces:**
- Consumes:
  - Task 12: `Menu`, `MenuItem` from `@/components/menu`.
  - `src/store/ui.ts`: `useUi()` exposing `scope: Scope`, `setScope: (scope: Scope) => void`,
    `showArchived: boolean`, `setShowArchived: (showArchived: boolean) => void`, and the
    type `Scope = { kind: "all" } | { kind: "team"; id: string } | { kind: "project"; id: string }`.
  - `src/lib/actions.ts`: `actionById(id: string): Action`,
    `type ActionContext`, `type Action` with `when(ctx): boolean` and `run(ctx): void`.
    **An explicit assumption about the registry:** team and project actions read their
    target from `ctx.scope`. `team.rename`, `team.archive`, `team.unarchive`,
    `team.delete`, `team.createChild` and `project.createInTeam` assume
    `ctx.scope.kind === "team"`; `project.edit`, `project.archive`,
    `project.unarchive` and `project.delete` assume `ctx.scope.kind === "project"`.
    That is why the sidebar does not hand the global context to its menus but a copy
    whose `scope` is the row in question.
  - `src/lib/queries.ts`: `keys.teams(includeArchived: boolean)`,
    `keys.projects(includeArchived: boolean)`.
  - `src/lib/api.ts`: `api.teams(includeArchived?: boolean): Promise<Team[]>`,
    `api.projects(opts?: { teamId?: string; includeArchived?: boolean }): Promise<Project[]>`,
    types `Team` and `Project`.
  - `apps/web/src/app/page.tsx` must already hold a value of type `ActionContext` to
    feed the keyboard handler and the palette. Check before starting:
    `cd /Users/elietreport/Projet/Perso/Kanso/apps/web && grep -n "ActionContext\|useActionCtx" src/app/page.tsx`
    must return at least one line. The name assumed below is `ctx`.
- Produces:
  ```ts
  // apps/web/src/components/sidebar.tsx
  export function Sidebar(props: { ctx: ActionContext; syncSummary: string }): React.ReactElement
  ```
  Stable handles for task 17:
  - the teams `+`: `<button aria-label="New team">`
  - the projects `+`: `<button aria-label="New project">`
  - a row's `⋯`: `<button aria-label={`Actions for ${name}`}>`
  - the row: `div.nav-item[data-kind="team"|"project"][data-current][data-archived]`
    also carrying `nav-depth-0`, `nav-depth-1` or `nav-depth-2`
  - the toggle: `<button aria-pressed>Show archived</button>`

- [ ] **Step 1: Rewrite `sidebar.tsx`**

Replace the whole content of `apps/web/src/components/sidebar.tsx` with:

```tsx
"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { actionById, type ActionContext } from "@/lib/actions";
import { api, type Project, type Team } from "@/lib/api";
import { keys } from "@/lib/queries";
import { useUi, type Scope } from "@/store/ui";
import { Menu, type MenuItem } from "./menu";

type Row =
  | { kind: "team"; team: Team; depth: number }
  | { kind: "project"; project: Project; depth: number };

/**
 * The tree, flattened in reading order: a team, its projects, then its sub-teams.
 * Depth is capped at two indents, as before: deeper than that the labels lose more in
 * width than they gain in clarity.
 */
function tree(teams: Team[], projects: Project[]): Row[] {
  const known = new Set(teams.map((team) => team.id));

  const byParent = new Map<string | undefined, Team[]>();
  for (const team of teams) {
    // A parent missing from the list — archived while archived rows are hidden —
    // would hide the whole branch under it. The team is drawn at the root instead.
    const parent = team.parentTeamId && known.has(team.parentTeamId) ? team.parentTeamId : undefined;
    byParent.set(parent, [...(byParent.get(parent) ?? []), team]);
  }

  const byTeam = new Map<string, Project[]>();
  for (const project of projects) {
    if (!project.teamId || !known.has(project.teamId)) continue;
    byTeam.set(project.teamId, [...(byTeam.get(project.teamId) ?? []), project]);
  }

  const rows: Row[] = [];
  const walk = (parent: string | undefined, depth: number) => {
    const children = [...(byParent.get(parent) ?? [])].sort((a, b) => a.name.localeCompare(b.name));
    for (const team of children) {
      rows.push({ kind: "team", team, depth: Math.min(depth, 2) });
      const owned = [...(byTeam.get(team.id) ?? [])].sort((a, b) => a.name.localeCompare(b.name));
      for (const project of owned) {
        rows.push({ kind: "project", project, depth: Math.min(depth + 1, 2) });
      }
      walk(team.id, depth + 1);
    }
  };
  walk(undefined, 0);
  return rows;
}

/**
 * The projects with no team, plus those whose team is not on screen. Together with
 * `tree` above, every project therefore appears exactly once.
 */
function rootProjects(teams: Team[], projects: Project[]): Project[] {
  const known = new Set(teams.map((team) => team.id));
  return projects
    .filter((project) => !project.teamId || !known.has(project.teamId))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * Turns registry ids into menu entries, dropping the ones this context forbids.
 * `actionById` throws on an unknown id: a menu pointing at a dead action is a bug,
 * not a missing entry.
 */
function menuItems(ctx: ActionContext, ids: string[]): MenuItem[] {
  return ids
    .map((id) => actionById(id))
    .filter((action) => action.when(ctx))
    .map((action) => ({
      id: action.id,
      label: action.label,
      danger: action.id === "team.delete" || action.id === "project.delete",
      onSelect: () => action.run(ctx),
    }));
}

function TeamRow({
  team,
  depth,
  current,
  ctx,
  onSelect,
}: {
  team: Team;
  depth: number;
  current: boolean;
  ctx: ActionContext;
  onSelect: () => void;
}) {
  const items = menuItems(ctx, [
    "project.createInTeam",
    "team.createChild",
    "team.rename",
    team.archived ? "team.unarchive" : "team.archive",
    "team.delete",
  ]);

  return (
    <div
      className={`nav-item nav-depth-${depth}`}
      data-kind="team"
      data-current={current}
      data-archived={team.archived}
    >
      <button
        className="nav-item-main"
        aria-current={current}
        onClick={onSelect}
        title={`${team.name} — prefix ${team.key}`}
      >
        <span className="nav-item-label">{team.name}</span>
        {/*
          Hidden from the accessibility tree: without this the button would be called
          "Core KAN" and the prefix would enter every team's name. It is still
          announced, as a description, through the `title` above.
        */}
        <span className="count" aria-hidden="true">
          {team.key}
        </span>
      </button>
      <Menu label={`Actions for ${team.name}`} items={items} />
    </div>
  );
}

function ProjectRow({
  project,
  depth,
  current,
  ctx,
  onSelect,
}: {
  project: Project;
  depth: number;
  current: boolean;
  ctx: ActionContext;
  onSelect: () => void;
}) {
  const items = menuItems(ctx, [
    "project.edit",
    project.archived ? "project.unarchive" : "project.archive",
    "project.delete",
  ]);

  return (
    <div
      className={`nav-item nav-depth-${depth}`}
      data-kind="project"
      data-current={current}
      data-archived={project.archived}
    >
      <button className="nav-item-main" aria-current={current} onClick={onSelect} title={project.name}>
        <span className="nav-item-label">{project.name}</span>
      </button>
      <Menu label={`Actions for ${project.name}`} items={items} />
    </div>
  );
}

export function Sidebar({ ctx, syncSummary }: { ctx: ActionContext; syncSummary: string }) {
  const { scope, setScope, showArchived, setShowArchived } = useUi();

  /**
   * One query for every project, with no `teamId`: the sidebar needs the whole tree
   * to draw itself, and a query per team would mean N requests for a single render.
   */
  const teams = useQuery({
    queryKey: keys.teams(showArchived),
    queryFn: () => api.teams(showArchived),
  });
  const projects = useQuery({
    queryKey: keys.projects(showArchived),
    queryFn: () => api.projects({ includeArchived: showArchived }),
  });

  const teamList = useMemo(() => teams.data ?? [], [teams.data]);
  const projectList = useMemo(() => projects.data ?? [], [projects.data]);
  const rows = useMemo(() => tree(teamList, projectList), [teamList, projectList]);
  const loose = useMemo(() => rootProjects(teamList, projectList), [teamList, projectList]);

  const at = (next: Scope): ActionContext => ({ ...ctx, scope: next });

  /**
   * The two header `+` buttons create at the root, explicitly: a team with no parent,
   * a project with no team. They therefore get a context scoped to "all" rather than
   * the current selection, which would make the result depend on whatever happens to
   * be open.
   */
  const rootCtx = at({ kind: "all" });
  const newTeam = actionById("team.create");
  const newProject = actionById("project.create");

  return (
    <aside className="sidebar">
      <div className="brand">
        <strong>Kanso</strong>
        <span>簡素</span>
      </div>

      <div>
        <div className="nav-label">Views</div>
        <div className="nav-item" data-kind="view" data-current={scope.kind === "all"}>
          <button
            className="nav-item-main"
            aria-current={scope.kind === "all"}
            onClick={() => setScope({ kind: "all" })}
          >
            <span className="nav-item-label">All tickets</span>
          </button>
        </div>
      </div>

      <div>
        <div className="nav-section">
          <span className="nav-label">Teams</span>
          {newTeam.when(rootCtx) && (
            <button
              className="nav-add"
              aria-label="New team"
              title={newTeam.label}
              onClick={() => newTeam.run(rootCtx)}
            >
              +
            </button>
          )}
        </div>

        {rows.length === 0 && <div className="nav-empty">No team yet</div>}

        {rows.map((row) =>
          row.kind === "team" ? (
            <TeamRow
              key={`team-${row.team.id}`}
              team={row.team}
              depth={row.depth}
              current={scope.kind === "team" && scope.id === row.team.id}
              ctx={at({ kind: "team", id: row.team.id })}
              onSelect={() => setScope({ kind: "team", id: row.team.id })}
            />
          ) : (
            <ProjectRow
              key={`project-${row.project.id}`}
              project={row.project}
              depth={row.depth}
              current={scope.kind === "project" && scope.id === row.project.id}
              ctx={at({ kind: "project", id: row.project.id })}
              onSelect={() => setScope({ kind: "project", id: row.project.id })}
            />
          ),
        )}
      </div>

      <div>
        <div className="nav-section">
          <span className="nav-label">Projects</span>
          {newProject.when(rootCtx) && (
            <button
              className="nav-add"
              aria-label="New project"
              title={newProject.label}
              onClick={() => newProject.run(rootCtx)}
            >
              +
            </button>
          )}
        </div>

        {loose.length === 0 && <div className="nav-empty">No project without a team</div>}

        {loose.map((project) => (
          <ProjectRow
            key={`root-${project.id}`}
            project={project}
            depth={0}
            current={scope.kind === "project" && scope.id === project.id}
            ctx={at({ kind: "project", id: project.id })}
            onSelect={() => setScope({ kind: "project", id: project.id })}
          />
        ))}
      </div>

      <div className="nav-foot">
        <button
          className="nav-toggle"
          aria-pressed={showArchived}
          onClick={() => setShowArchived(!showArchived)}
        >
          Show archived
        </button>
        <div className="nav-sync">{syncSummary}</div>
      </div>
    </aside>
  );
}
```

- [ ] **Step 2: Replace the navigation block in `globals.css`**

In `apps/web/src/app/globals.css`, find this exact block (lines 229 to 262):

```css
.nav-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: var(--nav-pad) 6px;
  border-radius: var(--radius);
  text-align: left;
  color: var(--text-dim);
}

.nav-item:hover {
  background: var(--surface-hover);
}

.nav-item[aria-current="true"] {
  background: var(--accent-soft);
  color: var(--text);
  font-weight: 500;
}

.nav-item .count {
  font: 11px/1 var(--mono);
  color: var(--text-faint);
}

.nav-depth-1 {
  padding-left: 18px;
}

.nav-depth-2 {
  padding-left: 30px;
}
```

and replace it entirely with:

```css
/*
 * A row is no longer a button but a container: it now carries a menu, and a
 * `<button>` cannot contain another one. The current state therefore travels through
 * `data-current` on the container, `aria-current` staying on the button that is
 * really the click target.
 */
.nav-item {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  padding-right: 6px;
  border-radius: var(--radius);
}

.nav-item:hover {
  background: var(--surface-hover);
}

.nav-item[data-current="true"] {
  background: var(--accent-soft);
}

.nav-item-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex: 1;
  min-width: 0;
  padding: var(--nav-pad) 0 var(--nav-pad) 6px;
  text-align: left;
  color: var(--text-dim);
}

.nav-item[data-current="true"] .nav-item-main {
  color: var(--text);
  font-weight: 500;
}

.nav-item-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * The bullet tells a project from a team. It is drawn rather than written: a
 * `content: "•"` would enter the button's accessible name, which would read
 * "• Refonte". An empty string does not.
 */
.nav-item[data-kind="project"] .nav-item-label::before {
  content: "";
  display: inline-block;
  width: 4px;
  height: 4px;
  margin-right: 8px;
  border-radius: 50%;
  background: var(--text-faint);
  vertical-align: middle;
}

.nav-item[data-archived="true"] .nav-item-main {
  opacity: 0.55;
}

/*
 * The menu only appears on hover, but stays in the tab order and becomes visible
 * again the moment it takes focus: a control that only exists on hover is a control
 * the keyboard cannot reach.
 */
.nav-item .menu-trigger {
  opacity: 0;
}

.nav-item:hover .menu-trigger,
.nav-item .menu-trigger:focus-visible,
.nav-item .menu-trigger[aria-expanded="true"] {
  opacity: 1;
}

.nav-item .count {
  font: 11px/1 var(--mono);
  color: var(--text-faint);
  flex-shrink: 0;
}

.nav-depth-1 .nav-item-main {
  padding-left: 18px;
}

.nav-depth-2 .nav-item-main {
  padding-left: 30px;
}

.nav-section {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 6px 6px;
}

.nav-section .nav-label {
  flex: 1;
  padding: 0;
}

.nav-add {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  line-height: 1;
  color: var(--text-faint);
}

.nav-add:hover {
  background: var(--surface-hover);
  color: var(--text);
}

.nav-empty {
  padding: 4px 6px;
  color: var(--text-faint);
  font-size: 12px;
}

.nav-foot {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
}

/*
 * A stateful button rather than a checkbox: `aria-pressed` says the same thing, the
 * click target is the whole row, and the square is drawn here rather than left to the
 * native theme, which does not follow `--accent`.
 */
.nav-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: var(--nav-pad) 6px;
  border-radius: var(--radius);
  text-align: left;
  color: var(--text-dim);
}

.nav-toggle:hover {
  background: var(--surface-hover);
}

.nav-toggle::before {
  content: "";
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  border: 1px solid var(--border);
  border-radius: 3px;
}

.nav-toggle[aria-pressed="true"] {
  color: var(--text);
}

.nav-toggle[aria-pressed="true"]::before {
  background: var(--accent);
  border-color: var(--accent);
}

.nav-sync {
  padding: 0 6px;
  color: var(--text-faint);
  font-size: 11px;
}
```

- [ ] **Step 3: Wire the new signature into `page.tsx`**

In `apps/web/src/app/page.tsx`, find the `<Sidebar …/>` element (lines 275 to 282 in
the file as it stands):

```tsx
      {preferences.sidebarVisible && (
        <Sidebar
          teams={teams.data ?? []}
          activeTeamId={teamId}
          onSelectTeam={setTeam}
          syncSummary={mirrorSummary}
        />
      )}
```

and replace it with:

```tsx
      {preferences.sidebarVisible && <Sidebar ctx={ctx} syncSummary={mirrorSummary} />}
```

`ctx` is the `ActionContext` value the page already builds for the keyboard handler
and the palette. If it goes by another name in the file, use that name. Then check
that no variable has been orphaned:

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web
pnpm lint
```

`eslint` reports orphaned imports and variables; delete what it points at.

- [ ] **Step 4: Verify the types and the style**

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web
pnpm exec tsc --noEmit
pnpm lint
```

- [ ] **Step 5: Manual verification**

Start the development loop described at the top of this document, then, in the
browser, as the owner (`owner@kanso.test`):

1. The sidebar shows three sections: `Views`, `Teams`, `Projects`.
2. Hover the `Teams` header: a `+` sits to the right of the label. Same for
   `Projects`. Both are visible at all times, not only on hover.
3. With no team yet, the line `No team yet` shows under `Teams` and
   `No project without a team` under `Projects`. Clicking the `+` buttons opens the
   task 14 dialogs — not written yet: at this step a click does nothing visible, which
   is expected.
4. The dialogs do not exist yet: build the tree by hand so there is something to look
   at. Three commands, one after the other, from any directory. The first one prints
   the id of `Core`; copy it into the second one in place of `<CORE ID>`.

```bash
curl -s -X POST http://localhost:8080/api/teams \
  -H 'Content-Type: application/json' -H 'X-Kanso-User: owner@kanso.test' \
  -d '{"name":"Core","key":"KAN"}'
```

```bash
curl -s -X POST http://localhost:8080/api/teams \
  -H 'Content-Type: application/json' -H 'X-Kanso-User: owner@kanso.test' \
  -d '{"name":"Mobile","key":"MOB","parentTeamId":"<CORE ID>"}'
```

```bash
curl -s -X POST http://localhost:8080/api/projects \
  -H 'Content-Type: application/json' -H 'X-Kanso-User: owner@kanso.test' \
  -d '{"name":"Audit 2026"}'
```

5. Reload the page. Expected: `Core` at the root, `Mobile` indented one step under it,
   `Audit 2026` in the `Projects` section at the bottom. `Core` shows `KAN` on the
   right, `Mobile` shows `MOB`.
6. Click `Core`: the row takes colour, the heading at the top of the screen becomes
   `Core`. Click `All tickets`: the heading goes back to `All tickets`.
7. Hover the `Core` row: a `⋯` appears on the right. Click it: a menu opens with, in
   this order, a "new project" entry, a "sub-team" entry, "Rename", "Archive", then
   "Delete" — that last one set apart by a rule and written in red.
8. With the menu open: `↓` and `↑` move the highlight and wrap around, `Home` and
   `End` jump to the ends, `Escape` closes and returns the focus to the `⋯`, and a
   click elsewhere on the page closes it too. Above all, check that `↓` does **not**
   also move the selection in the ticket list behind it.
9. With `Tab` alone, starting from the filter field: the focus must be able to reach a
   row's `⋯` without hovering it — it becomes visible the moment it takes focus.
10. Click `Show archived` at the bottom: the square fills with the accent colour. Both
    queries go out again with `includeArchived=true`; check it in the Network tab of
    the developer tools (`/api/teams?…` and `/api/projects?…`).
11. In a private window, as `member@kanso.test`: no `+` on the `Teams` header, no `⋯`
    on any team row. The `Projects` `+` and the project `⋯` menus are still there.

- [ ] **Step 6: Commit**

```bash
cd /Users/elietreport/Projet/Perso/Kanso
git add apps/web/src/components/sidebar.tsx apps/web/src/app/globals.css apps/web/src/app/page.tsx
git commit -m "feat(web): sidebar shows the team tree with projects, row menus and archived toggle"
```

---

### Task 14: Team and project dialogs

**Files:**
- Create: `apps/web/src/components/dialogs/team-dialog.tsx`
- Create: `apps/web/src/components/dialogs/project-dialog.tsx`
- Modify: `apps/web/src/app/page.tsx` (imports the two dialogs and renders them
  conditionally on `dialog.kind`)
- Test: no unit test (React rendering). Verified by `tsc`, `eslint` and the manual
  procedure in step 4; automated coverage comes from scenario 1 of task 17.

**Interfaces:**
- Consumes:
  - Task 12: `Field`, `DialogFrame` from `./field`.
  - `src/store/ui.ts`: `useUi()` exposing `dialog: Dialog` and `close: () => void`,
    with `Dialog = { kind: "none" } | { kind: "team"; id?: string; parentTeamId?: string } | { kind: "project"; id?: string; teamId?: string } | { kind: "disposition"; … }`.
  - `src/lib/api.ts`: `api.teams(includeArchived?)`, `api.createTeam`, `api.updateTeam`,
    `api.projects(opts?)`, `api.createProject`, `api.updateProject`, `api.users`,
    `ApiError` (with `status: number` and `detail: string`), types `Team`, `Project`, `User`.
  - `src/lib/queries.ts`: `keys.teams(includeArchived)`, `keys.projects(includeArchived)`,
    `keys.users`.
- Produces:
  ```ts
  // apps/web/src/components/dialogs/team-dialog.tsx
  export function TeamDialog(props: {
    id?: string; parentTeamId?: string; onClose: () => void;
  }): React.ReactElement

  // apps/web/src/components/dialogs/project-dialog.tsx
  export function ProjectDialog(props: {
    id?: string; teamId?: string; onClose: () => void;
  }): React.ReactElement
  ```
  Stable handles for task 17: `role="dialog"` with accessible name `New team`,
  `Edit <name>`, `New project`; fields `Name`, `Key`, `Parent team`, `Status`,
  `Lead`, `Start date`, `End date`, `Team`; buttons `Create`, `Save`, `Cancel`.

- [ ] **Step 1: Create `team-dialog.tsx`**

Create `apps/web/src/components/dialogs/team-dialog.tsx`:

```tsx
"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api, type Team } from "@/lib/api";
import { keys } from "@/lib/queries";
import { DialogFrame, Field } from "./field";

type TeamErrors = { key?: string; parent?: string; general?: string };

/**
 * The API names the offending input in the sentence it returns, not in a field map.
 * The sentence is therefore what decides where the message lands. Anything
 * unrecognised stays at the foot of the dialog rather than under a field that has
 * nothing to do with it.
 */
function route(error: unknown): TeamErrors {
  if (!(error instanceof ApiError)) {
    return { general: error instanceof Error ? error.message : "The team was not saved." };
  }
  const detail = error.detail.toLowerCase();
  if (error.status === 409 && detail.includes("key") && detail.includes("taken")) {
    return { key: error.detail };
  }
  if (error.status === 409 && detail.includes("cycle")) {
    return { parent: error.detail };
  }
  return { general: error.detail };
}

/**
 * A team's descendants, itself included. A parent taken from in there would make a
 * cycle; the server refuses it with a 409, and this only keeps the impossible choices
 * out of the list. The message under the field handles the case where the tree moved
 * since the list was drawn.
 */
function subtreeIds(teams: Team[], rootId: string): Set<string> {
  const inside = new Set<string>([rootId]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const team of teams) {
      if (team.parentTeamId && inside.has(team.parentTeamId) && !inside.has(team.id)) {
        inside.add(team.id);
        grew = true;
      }
    }
  }
  return inside;
}

/**
 * The form is a component of its own so its drafts start from the saved value at
 * mount, with no effect to put them back afterwards — the same pattern as
 * `TitleEditor` in `tickets.tsx`, and for the same reason: a team renamed by someone
 * else mid-typing does not wipe what is being written.
 */
function TeamForm({
  teams,
  team,
  defaultParentId,
  onClose,
}: {
  teams: Team[];
  team?: Team;
  defaultParentId: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(team?.name ?? "");
  const [key, setKey] = useState(team?.key ?? "");
  const [parent, setParent] = useState(defaultParentId);

  const save = useMutation({
    mutationFn: (body: { name: string; key?: string; parentTeamId?: string }) =>
      team ? api.updateTeam(team.id, body) : api.createTeam(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      // Reparenting: projects are drawn under the team, so their place changes.
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      onClose();
    },
  });

  const errors: TeamErrors = save.error ? route(save.error) : {};

  const candidates = useMemo(() => {
    const banned = team ? subtreeIds(teams, team.id) : new Set<string>();
    return teams
      .filter((row) => !banned.has(row.id) && !row.archived)
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [teams, team]);

  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    const prefix = key.trim().toUpperCase();
    save.mutate({
      name: trimmed,
      // Left empty, the server derives it from the name (`TeamService.resolveKey`).
      key: prefix ? prefix : undefined,
      parentTeamId: parent ? parent : undefined,
    });
  };

  return (
    <DialogFrame
      title={team ? `Edit ${team.name}` : "New team"}
      onClose={onClose}
      onSubmit={submit}
      submitLabel={team ? "Save" : "Create"}
      pending={save.isPending}
      error={errors.general}
    >
      <Field label="Name">
        <input
          autoFocus
          placeholder="Core"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
      </Field>

      <Field
        label="Key"
        error={errors.key}
        hint="Two to eight characters. It prefixes every ticket of the team: KAN-42. Left empty, the server derives it from the name."
      >
        <input
          placeholder="KAN"
          value={key}
          aria-invalid={errors.key ? true : undefined}
          onChange={(event) => setKey(event.target.value)}
        />
      </Field>

      <Field label="Parent team" error={errors.parent}>
        <select
          value={parent}
          aria-invalid={errors.parent ? true : undefined}
          onChange={(event) => setParent(event.target.value)}
        >
          <option value="">— none, a root team —</option>
          {candidates.map((row) => (
            <option key={row.id} value={row.id}>
              {row.name}
            </option>
          ))}
        </select>
      </Field>
    </DialogFrame>
  );
}

export function TeamDialog({
  id,
  parentTeamId,
  onClose,
}: {
  id?: string;
  parentTeamId?: string;
  onClose: () => void;
}) {
  // Archived teams are included: editing a team must not silently lose it a parent
  // that happens to be archived.
  const teams = useQuery({ queryKey: keys.teams(true), queryFn: () => api.teams(true) });

  if (!teams.data) {
    return (
      <DialogFrame
        title={id ? "Edit team" : "New team"}
        onClose={onClose}
        onSubmit={onClose}
        submitLabel={id ? "Save" : "Create"}
        pending
      >
        <div className="empty">Loading…</div>
      </DialogFrame>
    );
  }

  const team = id ? teams.data.find((row) => row.id === id) : undefined;

  if (id && !team) {
    return (
      <DialogFrame
        title="Edit team"
        onClose={onClose}
        onSubmit={onClose}
        submitLabel="Close"
        error="That team no longer exists."
      >
        <div className="empty">It was removed while this dialog was opening.</div>
      </DialogFrame>
    );
  }

  return (
    <TeamForm
      teams={teams.data}
      team={team}
      defaultParentId={team ? (team.parentTeamId ?? "") : (parentTeamId ?? "")}
      onClose={onClose}
    />
  );
}
```

- [ ] **Step 2: Create `project-dialog.tsx`**

Create `apps/web/src/components/dialogs/project-dialog.tsx`:

```tsx
"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api, type Project, type Team, type User } from "@/lib/api";
import { keys } from "@/lib/queries";
import { DialogFrame, Field } from "./field";

/**
 * `ProjectStatus`, server side (`domain/Model.kt`). Written here rather than in
 * `api.ts`: this dialog is the only place that offers the choice; everywhere else the
 * value is read back off the row.
 */
const PROJECT_STATUSES = ["planned", "in_progress", "paused", "completed", "canceled"] as const;

const PROJECT_STATUS_LABELS: Record<(typeof PROJECT_STATUSES)[number], string> = {
  planned: "Planned",
  in_progress: "In progress",
  paused: "Paused",
  completed: "Completed",
  canceled: "Canceled",
};

type ProjectErrors = { team?: string; general?: string };

function route(error: unknown): ProjectErrors {
  if (!(error instanceof ApiError)) {
    return { general: error instanceof Error ? error.message : "The project was not saved." };
  }
  if (error.detail.toLowerCase().includes("team")) return { team: error.detail };
  return { general: error.detail };
}

function ProjectForm({
  teams,
  users,
  project,
  defaultTeamId,
  onClose,
}: {
  teams: Team[];
  users: User[];
  project?: Project;
  defaultTeamId: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(project?.name ?? "");
  const [status, setStatus] = useState(project?.status ?? "planned");
  const [lead, setLead] = useState(project?.leadUserId ?? "");
  const [startDate, setStartDate] = useState(project?.startDate ?? "");
  const [endDate, setEndDate] = useState(project?.endDate ?? "");
  const [team, setTeam] = useState(defaultTeamId);

  const save = useMutation({
    mutationFn: (body: {
      name: string;
      status?: string;
      startDate?: string;
      endDate?: string;
      leadUserId?: string;
      teamId?: string;
    }) => (project ? api.updateProject(project.id, body) : api.createProject(body)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      onClose();
    },
  });

  const errors = save.error ? route(save.error) : {};

  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    save.mutate({
      name: trimmed,
      status,
      startDate: startDate ? startDate : undefined,
      endDate: endDate ? endDate : undefined,
      leadUserId: lead ? lead : undefined,
      // Clearing the team makes the project transverse: the PUT simply omits `teamId`.
      teamId: team ? team : undefined,
    });
  };

  return (
    <DialogFrame
      title={project ? `Edit ${project.name}` : "New project"}
      onClose={onClose}
      onSubmit={submit}
      submitLabel={project ? "Save" : "Create"}
      pending={save.isPending}
      error={errors.general}
    >
      <Field label="Name">
        <input
          autoFocus
          placeholder="Login rework"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
      </Field>

      <Field label="Status">
        <select value={status} onChange={(event) => setStatus(event.target.value)}>
          {PROJECT_STATUSES.map((value) => (
            <option key={value} value={value}>
              {PROJECT_STATUS_LABELS[value]}
            </option>
          ))}
        </select>
      </Field>

      <Field
        label="Team"
        error={errors.team}
        hint="No team makes the project transverse: it shows in the root Projects section and any team's tickets may point at it."
      >
        <select
          value={team}
          aria-invalid={errors.team ? true : undefined}
          onChange={(event) => setTeam(event.target.value)}
        >
          <option value="">— none, a transverse project —</option>
          {[...teams]
            .filter((row) => !row.archived)
            .sort((a, b) => a.name.localeCompare(b.name))
            .map((row) => (
              <option key={row.id} value={row.id}>
                {row.name}
              </option>
            ))}
        </select>
      </Field>

      <Field label="Lead">
        <select value={lead} onChange={(event) => setLead(event.target.value)}>
          <option value="">— nobody —</option>
          {[...users]
            .sort((a, b) => a.displayName.localeCompare(b.displayName))
            .map((user) => (
              <option key={user.id} value={user.id}>
                {user.displayName}
              </option>
            ))}
        </select>
      </Field>

      <Field label="Start date">
        <input type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} />
      </Field>

      <Field label="End date">
        <input type="date" value={endDate} onChange={(event) => setEndDate(event.target.value)} />
      </Field>
    </DialogFrame>
  );
}

export function ProjectDialog({
  id,
  teamId,
  onClose,
}: {
  id?: string;
  teamId?: string;
  onClose: () => void;
}) {
  const teams = useQuery({ queryKey: keys.teams(true), queryFn: () => api.teams(true) });
  const projects = useQuery({
    queryKey: keys.projects(true),
    queryFn: () => api.projects({ includeArchived: true }),
  });
  const users = useQuery({ queryKey: keys.users, queryFn: api.users });

  if (!teams.data || !projects.data) {
    return (
      <DialogFrame
        title={id ? "Edit project" : "New project"}
        onClose={onClose}
        onSubmit={onClose}
        submitLabel={id ? "Save" : "Create"}
        pending
      >
        <div className="empty">Loading…</div>
      </DialogFrame>
    );
  }

  const project = id ? projects.data.find((row) => row.id === id) : undefined;

  if (id && !project) {
    return (
      <DialogFrame
        title="Edit project"
        onClose={onClose}
        onSubmit={onClose}
        submitLabel="Close"
        error="That project no longer exists."
      >
        <div className="empty">It was removed while this dialog was opening.</div>
      </DialogFrame>
    );
  }

  return (
    <ProjectForm
      teams={teams.data}
      users={users.data ?? []}
      project={project}
      defaultTeamId={project ? (project.teamId ?? "") : (teamId ?? "")}
      onClose={onClose}
    />
  );
}
```

- [ ] **Step 3: Mount both dialogs in `page.tsx`**

In `apps/web/src/app/page.tsx`:

1. Add the two imports, after the component imports already at the top of the file:

```tsx
import { ProjectDialog } from "@/components/dialogs/project-dialog";
import { TeamDialog } from "@/components/dialogs/team-dialog";
```

2. Add `dialog` to the `useUi()` destructuring. The line
   `const { … } = useUi();` must include `dialog`:

```tsx
  const { scope, selectedId, overlay, dialog, query, setScope, select, open, close, setQuery } =
    useUi();
```

(Match whatever members the file actually destructures; the only required addition is
`dialog`.)

3. Just before the closing `</div>` of `<div className="shell" …>`, after the last
   `{overlay === "detail" && …}` block, add:

```tsx
      {dialog.kind === "team" && (
        <TeamDialog id={dialog.id} parentTeamId={dialog.parentTeamId} onClose={close} />
      )}
      {dialog.kind === "project" && (
        <ProjectDialog id={dialog.id} teamId={dialog.teamId} onClose={close} />
      )}
```

- [ ] **Step 4: Verify**

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web
pnpm exec tsc --noEmit
pnpm lint
```

Then, in the browser, with the development loop described at the top of this document,
as `owner@kanso.test`:

1. Click the `+` on the `Teams` header. Expected: a dialog titled `New team`, with
   three fields `Name`, `Key`, `Parent team`, a grey hint line under `Key`, and at the
   bottom `Cancel` then `Create`.
2. Press `Escape`: the dialog closes. Reopen it. Click the dark backdrop beside the
   panel: it closes too.
3. Type `Growth` and the key `GRW`, submit with `Enter` from the `Name` field — the
   form submits without going through the button. The team appears in the sidebar.
4. Create another team with the **same key** `GRW`. Expected: the dialog stays open,
   and the message `Team key 'GRW' is already taken` shows **under the Key field**, in
   red, not at the foot of the dialog. The field carries `aria-invalid="true"` (check
   it in the inspector).
5. Create `Core`, then, from the `⋯` on `Core`, pick "sub-team": the dialog opens with
   `Parent team` already set to `Core`. Create `Mobile`.
6. From the `⋯` on `Core`, pick "Rename": the dialog opens titled `Edit Core`, name and
   key filled in. Open the `Parent team` selector: `Core` and `Mobile` are **absent**
   from the list (a team cannot become its own descendant). Choose `Growth` and save:
   `Core` and `Mobile` move under `Growth` in the tree.
7. From the `⋯` on `Core`, pick "new project": a `New project` dialog, `Team` selector
   already on `Core`. Create `Refonte`: it appears indented under `Core`.
8. From the `+` on the `Projects` header: a `New project` dialog, `Team` selector on
   `— none, a transverse project —`. Create `Audit 2026`: it appears in the `Projects`
   section at the bottom.
9. From the `⋯` on `Refonte`, pick "Edit": clear the `Team` selector and save. The
   project leaves `Core` and joins the `Projects` section at the bottom.
10. Check the dialog stays usable from the keyboard alone: `Tab` walks the fields then
    `Cancel` then the submit button, and `Escape` closes from any of them.

- [ ] **Step 5: Commit**

```bash
cd /Users/elietreport/Projet/Perso/Kanso
git add apps/web/src/components/dialogs/team-dialog.tsx apps/web/src/components/dialogs/project-dialog.tsx apps/web/src/app/page.tsx
git commit -m "feat(web): team and project dialogs with server errors under the offending field"
```

---

### Task 15: Disposition dialog

**Files:**
- Create: `apps/web/src/components/dialogs/disposition-dialog.tsx`
- Modify: `apps/web/src/app/globals.css` (adds a `--- disposition ---` block after the
  `--- menu ---` block created in task 12)
- Modify: `apps/web/src/app/page.tsx` (import and conditional render on
  `dialog.kind === "disposition"`)
- Test: no unit test (React rendering). Verified by `tsc`, `eslint` and the manual
  procedure in step 4; automated coverage comes from scenario 4 of task 17.

**Interfaces:**
- Consumes:
  - Task 12: `Field`, `DialogFrame` from `./field`.
  - `src/store/ui.ts`: `useUi`, type `Scope`.
  - `src/lib/api.ts`: `api.teams(includeArchived?)`, `api.projects(opts?)`,
    `api.teamContents(id)`, `api.projectContents(id)`, `api.archiveTeam(id, plan)`,
    `api.deleteTeam(id, plan)`, `api.archiveProject(id, plan)`,
    `api.deleteProject(id, plan)`, types `DispositionChoice`, `DispositionCounts`,
    `DispositionPlan`, and `ApiError` with `readonly body?: unknown`.
  - `src/lib/queries.ts`: `keys.teams`, `keys.projects`,
    `keys.contents(kind: "team" | "project", id: string)`.
  - The API's exception handler answers a `CountsChangedException` with a `409` whose
    RFC 7807 body carries a `counts` property of shape
    `{ subTeams: number, projects: number, tickets: number }`.
- Produces:
  ```ts
  // apps/web/src/components/dialogs/disposition-dialog.tsx
  export function DispositionDialog(props: {
    target: { kind: "team" | "project"; id: string };
    severity: "archive" | "delete";
    onClose: () => void;
  }): React.ReactElement
  ```
  Stable handles for task 17: `role="dialog"` with accessible name
  `Archive “<name>”` or `Delete “<name>”`; `role="radiogroup"` named `sub-teams`,
  `projects`, `tickets`; field `Destination team`; field `Type <name> to confirm`;
  callout `.disposition-warning`; callout `.disposition-drift`.

- [ ] **Step 1: Create `disposition-dialog.tsx`**

Create `apps/web/src/components/dialogs/disposition-dialog.tsx`:

```tsx
"use client";

import { useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ApiError,
  api,
  type DispositionChoice,
  type DispositionCounts,
  type DispositionPlan,
  type Team,
} from "@/lib/api";
import { keys } from "@/lib/queries";
import { useUi } from "@/store/ui";
import { DialogFrame, Field } from "./field";

/**
 * The drift 409 carries the fresh counts in the problem body. They are read
 * defensively: a body without them is an ordinary 409, which must show as an error
 * rather than reopen the dialog on zeroes.
 */
function countsFrom(error: unknown): DispositionCounts | undefined {
  if (!(error instanceof ApiError) || error.status !== 409) return undefined;
  const body = error.body;
  if (typeof body !== "object" || body === null || !("counts" in body)) return undefined;
  const raw = (body as { counts: unknown }).counts;
  if (typeof raw !== "object" || raw === null) return undefined;
  const record = raw as Record<string, unknown>;
  const read = (field: string): number | undefined =>
    typeof record[field] === "number" ? (record[field] as number) : undefined;
  const subTeams = read("subTeams");
  const projects = read("projects");
  const tickets = read("tickets");
  if (subTeams === undefined || projects === undefined || tickets === undefined) return undefined;
  return { subTeams, projects, tickets };
}

/** A team's descendants, itself included: never a valid destination. */
function subtreeIds(teams: Team[], rootId: string): Set<string> {
  const inside = new Set<string>([rootId]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const team of teams) {
      if (team.parentTeamId && inside.has(team.parentTeamId) && !inside.has(team.id)) {
        inside.add(team.id);
        grew = true;
      }
    }
  }
  return inside;
}

/**
 * One category and its choice. Radio buttons rather than a segmented control: the
 * arrows walk the group for free, and the group's name is readable by a screen reader
 * without extra work.
 */
function ChoiceRow({
  group,
  count,
  noun,
  keepLabel,
  takeLabel,
  value,
  onChange,
  children,
}: {
  group: string;
  count: number;
  noun: string;
  keepLabel: string;
  takeLabel: string;
  value: DispositionChoice;
  onChange: (value: DispositionChoice) => void;
  children?: ReactNode;
}) {
  return (
    <div className="disposition-row">
      <div className="disposition-count">
        {count} {noun}
      </div>
      <div className="disposition-cell">
        <div className="disposition-choices" role="radiogroup" aria-label={noun}>
          <label className="disposition-choice">
            <input
              type="radio"
              name={group}
              checked={value === "keep"}
              onChange={() => onChange("keep")}
            />
            <span>{keepLabel}</span>
          </label>
          <label className="disposition-choice">
            <input
              type="radio"
              name={group}
              checked={value === "take"}
              onChange={() => onChange("take")}
            />
            <span>{takeLabel}</span>
          </label>
        </div>
        {value === "keep" && children}
      </div>
    </div>
  );
}

/**
 * One component for both verbs.
 *
 * Archiving and deleting ask exactly the same questions — what becomes of what this
 * holds? — and part only on what confirms them: one button on one side, the name
 * retyped on the other, and a 409 on counts that moved only on the side that does not
 * come back.
 *
 * The renumbering warning, on the other hand, shows in both: moving a ticket renames
 * it for good, whether the team it left was archived or deleted.
 */
export function DispositionDialog({
  target,
  severity,
  onClose,
}: {
  target: { kind: "team" | "project"; id: string };
  severity: "archive" | "delete";
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const scope = useUi((state) => state.scope);
  const setScope = useUi((state) => state.setScope);

  const teams = useQuery({ queryKey: keys.teams(true), queryFn: () => api.teams(true) });
  const projects = useQuery({
    queryKey: keys.projects(true),
    queryFn: () => api.projects({ includeArchived: true }),
    enabled: target.kind === "project",
  });
  const contents = useQuery({
    queryKey: keys.contents(target.kind, target.id),
    queryFn: () =>
      target.kind === "team" ? api.teamContents(target.id) : api.projectContents(target.id),
    // The whole point of this dialog is a fresh count; the client-wide 30s
    // `staleTime` would open it on a number read half a minute ago.
    staleTime: 0,
  });

  const [subTeams, setSubTeams] = useState<DispositionChoice>("keep");
  const [projectChoice, setProjectChoice] = useState<DispositionChoice>("keep");
  const [ticketChoice, setTicketChoice] = useState<DispositionChoice>("keep");
  const [destination, setDestination] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [problem, setProblem] = useState<"destination" | "confirmation" | null>(null);

  const isTeam = target.kind === "team";
  const subject = isTeam
    ? teams.data?.find((row) => row.id === target.id)
    : projects.data?.find((row) => row.id === target.id);
  const name = subject?.name;
  const sourceKey = isTeam ? teams.data?.find((row) => row.id === target.id)?.key : undefined;

  const destinations = useMemo(() => {
    if (!isTeam || !teams.data) return [];
    const banned = subtreeIds(teams.data, target.id);
    return teams.data
      .filter((row) => !banned.has(row.id) && !row.archived)
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [isTeam, teams.data, target.id]);

  const run = useMutation({
    mutationFn: async (plan: DispositionPlan): Promise<void> => {
      if (target.kind === "team") {
        if (severity === "delete") await api.deleteTeam(target.id, plan);
        else await api.archiveTeam(target.id, plan);
        return;
      }
      if (severity === "delete") await api.deleteProject(target.id, plan);
      else await api.archiveProject(target.id, plan);
    },
    onSuccess: () => {
      // The list would otherwise keep filtering on something that is no longer there.
      if (scope.kind !== "all" && scope.kind === target.kind && scope.id === target.id) {
        setScope({ kind: "all" });
      }
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
      onClose();
    },
    onError: (error) => {
      const fresh = countsFrom(error);
      if (!fresh) return;
      // The dialog reopens on the truth, and consent starts over: whoever agreed to
      // destroy 47 tickets did not agree to destroy 50.
      queryClient.setQueryData(keys.contents(target.kind, target.id), fresh);
      setConfirmation("");
      setProblem(null);
    },
  });

  const counts = contents.data;
  const drift = run.error ? countsFrom(run.error) : undefined;
  const footerError = run.error && !drift ? (run.error as Error).message : null;

  if (!counts || name === undefined) {
    return (
      <DialogFrame
        title={severity === "delete" ? "Delete" : "Archive"}
        onClose={onClose}
        onSubmit={onClose}
        submitLabel={severity === "delete" ? "Delete" : "Archive"}
        submitDanger={severity === "delete"}
        pending
        error={contents.error ? (contents.error as Error).message : null}
      >
        <div className="empty">Counting what this holds…</div>
      </DialogFrame>
    );
  }

  const verb = severity === "delete" ? "Delete" : "Archive";
  const takeLabel = severity === "delete" ? "Delete with it" : "Archive with it";
  const empty = counts.subTeams === 0 && counts.projects === 0 && counts.tickets === 0;
  const needsDestination = isTeam && counts.tickets > 0 && ticketChoice === "keep";
  const movingTickets = needsDestination && destination !== "";
  const destinationKey = destinations.find((row) => row.id === destination)?.key;

  const submit = () => {
    if (needsDestination && !destination) {
      setProblem("destination");
      return;
    }
    if (severity === "delete" && confirmation.trim() !== name) {
      setProblem("confirmation");
      return;
    }
    setProblem(null);
    run.mutate({
      subTeams,
      projects: projectChoice,
      tickets: ticketChoice,
      ticketsTargetTeamId: needsDestination ? destination : undefined,
      // Deletion compares; archiving ignores, because it comes back.
      counts: severity === "delete" ? counts : undefined,
    });
  };

  return (
    <DialogFrame
      title={`${verb} “${name}”`}
      onClose={onClose}
      onSubmit={submit}
      submitLabel={verb}
      submitDanger={severity === "delete"}
      pending={run.isPending}
      error={footerError}
    >
      {drift && (
        <div className="disposition-drift" role="alert">
          The contents changed while this was open, so nothing was deleted. The numbers below are
          the current ones — read them again before confirming.
        </div>
      )}

      {empty ? (
        <p className="disposition-lede">
          This {isTeam ? "team" : "project"} holds nothing.{" "}
          {severity === "delete"
            ? "Deleting it removes it for good."
            : "Archiving it only hides it; Show archived brings it back."}
        </p>
      ) : (
        <>
          <p className="disposition-lede">This {isTeam ? "team" : "project"} contains:</p>

          {isTeam && counts.subTeams > 0 && (
            <ChoiceRow
              group="disposition-sub-teams"
              count={counts.subTeams}
              noun="sub-teams"
              keepLabel="Keep active, under the grandparent"
              takeLabel={takeLabel}
              value={subTeams}
              onChange={setSubTeams}
            />
          )}

          {isTeam && counts.projects > 0 && (
            <ChoiceRow
              group="disposition-projects"
              count={counts.projects}
              noun="projects"
              keepLabel="Keep active, under the parent team"
              takeLabel={takeLabel}
              value={projectChoice}
              onChange={setProjectChoice}
            />
          )}

          {counts.tickets > 0 && (
            <ChoiceRow
              group="disposition-tickets"
              count={counts.tickets}
              noun="tickets"
              keepLabel={isTeam ? "Keep active, move to another team" : "Keep active, without a project"}
              takeLabel={takeLabel}
              value={ticketChoice}
              onChange={setTicketChoice}
            >
              {isTeam && (
                <Field
                  label="Destination team"
                  error={problem === "destination" ? "Choose the team these tickets move to." : null}
                >
                  <select
                    value={destination}
                    aria-invalid={problem === "destination" ? true : undefined}
                    onChange={(event) => {
                      setDestination(event.target.value);
                      setProblem(null);
                    }}
                  >
                    <option value="">— pick a team —</option>
                    {destinations.map((row) => (
                      <option key={row.id} value={row.id}>
                        {row.name}
                      </option>
                    ))}
                  </select>
                </Field>
              )}
            </ChoiceRow>
          )}

          {movingTickets && (
            <div className="disposition-warning" role="note">
              <strong>
                ⚠ The {counts.tickets} tickets will be renumbered.
              </strong>
              <span>
                Every <code>{sourceKey}-…</code> identifier becomes a <code>{destinationKey}-…</code>{" "}
                one. Existing links stop resolving.
              </span>
            </div>
          )}
        </>
      )}

      {severity === "delete" ? (
        <Field
          label={`Type ${name} to confirm`}
          error={problem === "confirmation" ? `Type the name exactly: ${name}` : null}
        >
          <input
            autoFocus
            value={confirmation}
            aria-invalid={problem === "confirmation" ? true : undefined}
            onChange={(event) => {
              setConfirmation(event.target.value);
              setProblem(null);
            }}
          />
        </Field>
      ) : (
        <p className="disposition-note">Reversible from Show archived, at the foot of the sidebar.</p>
      )}
    </DialogFrame>
  );
}
```

- [ ] **Step 2: Add the `--- disposition ---` block to `globals.css`**

In `apps/web/src/app/globals.css`, find the end of the `--- menu ---` block added in
task 12, that is:

```css
.menu-item[data-danger="true"] {
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
  color: var(--urgent);
}
```

and insert just after it:

```css
/* --- disposition ---------------------------------------------------------- */

.disposition-lede {
  margin: 0;
  color: var(--text-dim);
}

.disposition-row {
  display: grid;
  grid-template-columns: 116px 1fr;
  gap: 12px;
  align-items: start;
}

.disposition-count {
  font-weight: 500;
}

.disposition-cell {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.disposition-choices {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.disposition-choice {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

/* The global `input` rules put a background and a border on everything, including a
   radio button, which wants neither. */
.disposition-choice input {
  width: auto;
  margin: 0;
  padding: 0;
  background: none;
  border: none;
  border-radius: 0;
}

/*
 * Amber, not red: this is not an error but a permanent consequence of a legitimate
 * choice. Red is kept for what failed and for what destroys.
 */
.disposition-warning {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--high) 45%, transparent);
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--high) 10%, transparent);
  font-size: 12px;
}

.disposition-warning code {
  font: 11px/1.5 var(--mono);
}

.disposition-drift {
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--urgent) 45%, transparent);
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--urgent) 10%, transparent);
  font-size: 12px;
}

.disposition-note {
  margin: 0;
  color: var(--text-faint);
  font-size: 12px;
}
```

- [ ] **Step 3: Mount the dialog in `page.tsx`**

In `apps/web/src/app/page.tsx`:

1. Add the import, next to the two added in task 14:

```tsx
import { DispositionDialog } from "@/components/dialogs/disposition-dialog";
```

2. Under the two blocks `{dialog.kind === "team" && …}` and
   `{dialog.kind === "project" && …}` added in task 14:

```tsx
      {dialog.kind === "disposition" && (
        <DispositionDialog target={dialog.target} severity={dialog.severity} onClose={close} />
      )}
```

- [ ] **Step 4: Verify**

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web
pnpm exec tsc --noEmit
pnpm lint
```

Then, in the browser, as `owner@kanso.test`. Set up a tree: with the task 14 dialogs
create a team `Growth` (key `GRW`), a team `Core` (key `KAN`), a sub-team `Mobile`
under `Core`, and a project `Refonte` inside `Core`. Create three tickets in `Core` by
selecting `Core` in the sidebar and pressing `c` three times. Note their identifiers:
`KAN-1`, `KAN-2`, `KAN-3`.

1. `⋯` on `Core` → "Archive". Expected: a dialog titled `Archive “Core”`, the line
   `This team contains:`, then three rows — `1 sub-teams`, `1 projects`,
   `3 tickets` — each with two radio buttons, **the first one checked** in all three
   cases.
2. Under the tickets row, a `Destination team` selector is visible as long as the
   first choice is checked. It offers neither `Core` nor `Mobile` (a team cannot move
   its tickets into itself or into its own descendants) — only `Growth`.
3. Choose `Growth`: an amber callout appears, reading
   `⚠ The 3 tickets will be renumbered.` and `Every KAN-… identifier becomes a GRW-…
   one. Existing links stop resolving.` **Check that this callout does appear in the
   "archive" severity too**, not only in deletion — that is the point of the mockup.
4. Switch the tickets choice to "Archive with it": the selector and the callout
   disappear. Switch it back: they return, with the selector cleared.
5. At the foot of the dialog: the sentence `Reversible from Show archived…` and **no
   name field at all**. Close with `Cancel`.
6. `⋯` on `Core` → "Delete". Expected: the same dialog, titled `Delete “Core”`, the
   second options labelled `Delete with it`, a `Type Core to confirm` field at the
   bottom, and a **red** submit button labelled `Delete`.
7. Click `Delete` without typing anything: nothing is sent, and the message
   `Type the name exactly: Core` shows under the field. Confirm it in the Network tab:
   no `DELETE` request went out.
8. Choose `Growth` as the destination, type `Core`, click `Delete`. Expected: the
   dialog closes, `Core` disappears from the sidebar, `Mobile` moves up one level,
   `Refonte` lands in the `Projects` section at the bottom (the team was at the root),
   and selecting `Growth` shows the three tickets with `GRW-…` identifiers.
9. The 409 replay: create a team `Core` again, with two tickets. Open
   `⋯` → "Delete". **Without closing the dialog**, in another tab or with `curl`,
   create a third ticket in `Core`:

```bash
curl -s -X POST http://localhost:8080/api/tickets \
  -H 'Content-Type: application/json' -H 'X-Kanso-User: owner@kanso.test' \
  -d '{"teamId":"<CORE ID>","title":"Arrived while the dialog was open"}'
```

   Go back to the dialog, type `Core`, choose the destination, click `Delete`.
   Expected: the dialog **stays open**, a red callout appears at the top
   (`The contents changed while this was open, so nothing was deleted…`), the tickets
   count has gone from 2 to 3, and the confirmation field has been **cleared**. Retype
   `Core` and click `Delete`: this time the deletion goes through.
10. Repeat the experiment with "Archive" in place of "Delete": archiving **goes
    through** despite the drift, with no red callout. That is the difference between
    the two severities.
11. A project: `⋯` on a project → "Delete". Expected: a single category row, the
    tickets one, with `Keep active, without a project` as its first choice and **no**
    destination selector (the tickets already have a team).
12. An empty team: `⋯` → "Archive". Expected: no radios at all, only
    `This team holds nothing. Archiving it only hides it; Show archived brings it back.`

- [ ] **Step 5: Commit**

```bash
cd /Users/elietreport/Projet/Perso/Kanso
git add apps/web/src/components/dialogs/disposition-dialog.tsx apps/web/src/app/globals.css apps/web/src/app/page.tsx
git commit -m "feat(web): disposition dialog for archiving and deleting teams and projects"
```

---

### Task 16: Composer with a context bar

**Files:**
- Create: `apps/web/src/components/composer.tsx`
- Modify: `apps/web/src/components/overlays.tsx:14-61` (exports `Backdrop`, removes
  `Composer`)
- Modify: `apps/web/src/app/globals.css` (adds `.composer-context` after
  `.composer-input:focus`)
- Modify: `apps/web/src/app/page.tsx` (import, render, and **removal of the
  `teams.data[0]` fallback on line 126**)
- Test: no unit test (React rendering). Verified by `tsc`, `eslint` and the manual
  procedure in step 5; automated coverage comes from scenarios 1 and 5 of task 17.

**Interfaces:**
- Consumes:
  - `src/store/ui.ts`: type `Scope`.
  - `src/lib/api.ts`: `api.teams(includeArchived?)`, `api.projects(opts?)`,
    `api.users()`, `api.createTicket(body)`, `TICKET_PRIORITIES`, types `Project`,
    `Team`, `TicketPriority`, `User`.
  - `src/lib/queries.ts`: `keys.teams`, `keys.projects`, `keys.users`, `useMe()`.
- Produces:
  ```ts
  // apps/web/src/components/composer.tsx
  export function Composer(props: { scope: Scope; onClose: () => void }): React.ReactElement

  // apps/web/src/components/overlays.tsx
  export function Backdrop(props: { onClose: () => void; children: React.ReactNode }): React.ReactElement
  ```
  Stable handles for task 17: the title field carries
  `placeholder="New ticket…"`; the four selectors carry `aria-label="Team"`,
  `"Project"`, `"Priority"`, `"Assignee"`.

- [ ] **Step 1: Create `composer.tsx`**

Create `apps/web/src/components/composer.tsx`:

```tsx
"use client";

import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  TICKET_PRIORITIES,
  api,
  type Project,
  type Team,
  type TicketPriority,
  type User,
} from "@/lib/api";
import { keys, useMe } from "@/lib/queries";
import type { Scope } from "@/store/ui";
import { Backdrop } from "./overlays";

const PRIORITY_LABELS: Record<TicketPriority, string> = {
  none: "No priority",
  low: "Low",
  medium: "Medium",
  high: "High",
  urgent: "Urgent",
};

/**
 * One title field, Enter creates — the speed that made this worth building. Below it,
 * the target: team, project, priority and assignee, prefilled from the current scope,
 * clickable and reachable with Tab.
 */
function ComposerForm({
  teams,
  projects,
  users,
  meId,
  scope,
  onClose,
}: {
  teams: Team[];
  projects: Project[];
  users: User[];
  meId?: string;
  scope: Scope;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const teamRef = useRef<HTMLSelectElement>(null);

  const scopeProject = scope.kind === "project" ? projects.find((row) => row.id === scope.id) : undefined;

  const [title, setTitle] = useState("");
  const [teamId, setTeamId] = useState(
    scope.kind === "team" ? scope.id : (scopeProject?.teamId ?? ""),
  );
  const [projectId, setProjectId] = useState(scopeProject?.id ?? "");
  const [priority, setPriority] = useState<TicketPriority>("none");
  const [assigneeId, setAssigneeId] = useState(meId ?? "");
  const [blocked, setBlocked] = useState(false);

  /**
   * A ticket always belongs to a team; a project does not. The list offered is
   * therefore the chosen team's, plus every team-less project — the only two places a
   * project a ticket may point at can live. No SQL constraint ties
   * `tickets.project_id` to `tickets.team_id`, and this spec adds none: this bounds
   * what the interface offers, nothing more.
   */
  const projectOptions = useMemo(
    () =>
      projects
        .filter((project) => !project.archived && (!project.teamId || project.teamId === teamId))
        .sort((a, b) => a.name.localeCompare(b.name)),
    [projects, teamId],
  );

  const create = useMutation({
    mutationFn: api.createTicket,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
      // The team's counter moved, and it is on display in the sidebar.
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      onClose();
    },
  });

  /**
   * The defect this replaces: `page.tsx` fell back to `teams.data[0]` when the view
   * was "All tickets", so a ticket filed from there landed in whichever team sorted
   * first, with nobody having chosen it. With no team resolved, creation stops and
   * points at the selector.
   */
  const submit = () => {
    if (create.isPending) return;
    const trimmed = title.trim();
    if (!trimmed) return;
    if (!teamId) {
      setBlocked(true);
      teamRef.current?.focus();
      return;
    }
    create.mutate({
      teamId,
      title: trimmed,
      priority,
      projectId: projectId ? projectId : undefined,
      assigneeIds: assigneeId ? [assigneeId] : undefined,
    });
  };

  return (
    <Backdrop onClose={onClose}>
      <input
        className="composer-input"
        autoFocus
        placeholder="New ticket…"
        value={title}
        disabled={create.isPending}
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          event.stopPropagation();
          if (event.key === "Enter") {
            event.preventDefault();
            submit();
          }
          if (event.key === "Escape") onClose();
        }}
      />

      <div className="composer-context">
        <select
          ref={teamRef}
          aria-label="Team"
          aria-invalid={blocked && !teamId ? true : undefined}
          value={teamId}
          disabled={create.isPending}
          onChange={(event) => {
            const next = event.target.value;
            setTeamId(next);
            setBlocked(false);
            // A project of the team just left is no longer a legal home; a team-less
            // project always is.
            const chosen = projects.find((row) => row.id === projectId);
            if (chosen?.teamId && chosen.teamId !== next) setProjectId("");
          }}
        >
          <option value="">Team…</option>
          {[...teams]
            .filter((team) => !team.archived)
            .sort((a, b) => a.name.localeCompare(b.name))
            .map((team) => (
              <option key={team.id} value={team.id}>
                {team.name}
              </option>
            ))}
        </select>

        <select
          aria-label="Project"
          value={projectId}
          disabled={create.isPending}
          onChange={(event) => setProjectId(event.target.value)}
        >
          <option value="">No project</option>
          {projectOptions.map((project) => (
            <option key={project.id} value={project.id}>
              {project.name}
            </option>
          ))}
        </select>

        <select
          aria-label="Priority"
          value={priority}
          disabled={create.isPending}
          onChange={(event) => setPriority(event.target.value as TicketPriority)}
        >
          {TICKET_PRIORITIES.map((value) => (
            <option key={value} value={value}>
              {PRIORITY_LABELS[value]}
            </option>
          ))}
        </select>

        <select
          aria-label="Assignee"
          value={assigneeId}
          disabled={create.isPending}
          onChange={(event) => setAssigneeId(event.target.value)}
        >
          <option value="">Unassigned</option>
          {[...users]
            .sort((a, b) => a.displayName.localeCompare(b.displayName))
            .map((user) => (
              <option key={user.id} value={user.id}>
                {user.displayName}
              </option>
            ))}
        </select>
      </div>

      <div className="composer-footer">
        <kbd>↵</kbd> create <kbd>esc</kbd> cancel
        {blocked && !teamId && (
          <span className="error" role="alert">
            Pick a team first.
          </span>
        )}
        {create.isError && (
          <span className="error" role="alert">
            {(create.error as Error).message}
          </span>
        )}
        {create.isPending && <span style={{ marginLeft: "auto" }}>saving…</span>}
      </div>
    </Backdrop>
  );
}

export function Composer({ scope, onClose }: { scope: Scope; onClose: () => void }) {
  const teams = useQuery({ queryKey: keys.teams(false), queryFn: () => api.teams(false) });
  const projects = useQuery({ queryKey: keys.projects(false), queryFn: () => api.projects() });
  const users = useQuery({ queryKey: keys.users, queryFn: api.users });
  const me = useMe();

  /**
   * The form is only mounted once the lists are in hand, so its initial values are
   * the right ones on the very first render — the same pattern as `TitleEditor` in
   * `tickets.tsx`. Prefilling them afterwards would take an effect, which would
   * overwrite whatever somebody had already changed.
   */
  if (!teams.data || !projects.data) {
    return (
      <Backdrop onClose={onClose}>
        <div className="empty">Loading…</div>
      </Backdrop>
    );
  }

  return (
    <ComposerForm
      teams={teams.data}
      projects={projects.data}
      users={users.data ?? []}
      meId={me.data?.user.id}
      scope={scope}
      onClose={onClose}
    />
  );
}
```

- [ ] **Step 2: Move `Composer` out of `overlays.tsx` and export `Backdrop`**

In `apps/web/src/components/overlays.tsx`, replace this block (lines 14 to 61):

```tsx
function Backdrop({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="backdrop" onClick={onClose}>
      <div className="panel" onClick={(event) => event.stopPropagation()}>
        {children}
      </div>
    </div>
  );
}

/**
 * One input, Enter to create. Anything else about the ticket is a keystroke away
 * once it exists — asking for a status and a project up front is what makes other
 * trackers slow to file into.
 */
export function Composer({
  onCreate,
  onClose,
  pending,
}: {
  onCreate: (title: string) => void;
  onClose: () => void;
  pending: boolean;
}) {
  const [title, setTitle] = useState("");

  return (
    <Backdrop onClose={onClose}>
      <input
        className="composer-input"
        autoFocus
        placeholder="New ticket…"
        value={title}
        disabled={pending}
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          event.stopPropagation();
          if (event.key === "Enter" && title.trim()) onCreate(title.trim());
          if (event.key === "Escape") onClose();
        }}
      />
      <div className="composer-footer">
        <kbd>↵</kbd> create <kbd>esc</kbd> cancel
        {pending && <span style={{ marginLeft: "auto" }}>saving…</span>}
      </div>
    </Backdrop>
  );
}
```

with:

```tsx
/**
 * Exported since the composer moved into a file of its own. `DialogFrame`, for its
 * part, rewrites these four lines: it needs `role="dialog"`, a `tabIndex` and a
 * `keydown` boundary, none of which this wrapper takes.
 */
export function Backdrop({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="backdrop" onClick={onClose}>
      <div className="panel" onClick={(event) => event.stopPropagation()}>
        {children}
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Add `.composer-context` to `globals.css`**

In `apps/web/src/app/globals.css`, find:

```css
.composer-input:focus {
  outline: none;
}
```

and insert just after it:

```css
/*
 * The composer's context bar. The four selectors share the width evenly and wrap
 * rather than squeeze themselves down to an unreadable size.
 */
.composer-context {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 12px;
  border-top: 1px solid var(--border);
}

.composer-context select {
  flex: 1;
  min-width: 108px;
  padding: 4px 6px;
  font-size: 12px;
}

.composer-footer .error {
  margin-left: auto;
}
```

- [ ] **Step 4: Wire `page.tsx` and remove the `teams.data[0]` fallback**

In `apps/web/src/app/page.tsx`:

1. Take `Composer` out of the `overlays` import and import it from its new file. The
   line:

```tsx
import { CommandPalette, Composer, DetailPanel, HelpOverlay } from "@/components/overlays";
```

becomes:

```tsx
import { Composer } from "@/components/composer";
import { CommandPalette, DetailPanel, HelpOverlay } from "@/components/overlays";
```

2. **Delete** this block, which is the defect this task fixes (lines 124 to 131 of the
   original file):

```tsx
  const createTicket = useCallback(
    (title: string) => {
      const target = teamId ?? teams.data?.[0]?.id;
      if (!target) return;
      create.mutate({ teamId: target, title }, { onSuccess: () => close() });
    },
    [teamId, teams.data, create, close],
  );
```

3. Delete the mutation that has become pointless — the composer owns its own. Remove
   the line:

```tsx
  const create = useCreateTicket(teamId);
```

and take `useCreateTicket` out of the `@/lib/queries` import.

4. Replace the composer's render:

```tsx
      {overlay === "composer" && (
        <Composer onCreate={createTicket} onClose={close} pending={create.isPending} />
      )}
```

with:

```tsx
      {overlay === "composer" && <Composer scope={scope} onClose={close} />}
```

5. Run `eslint` to find what has been orphaned (`useCallback` may no longer be used,
   for instance):

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web
pnpm lint
```

and delete what it points at.

- [ ] **Step 5: Verify**

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web
pnpm exec tsc --noEmit
pnpm lint
```

Check that no trace of the fallback is left:

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web
grep -n "teams.data?.\[0\]\|teams.data\[0\]" src/app/page.tsx
```

This command must return **nothing**.

Then, in the browser, as `owner@kanso.test`, with at least two teams (`Core` and
`Growth`), a project `Refonte` inside `Core` and a team-less project `Audit 2026`:

1. Select `Core` in the sidebar and press `c`. Expected: the composer opens, the
   cursor sits in the title field, and four selectors sit underneath it. The first
   shows `Core`, the third `No priority`, the fourth your own name.
2. Open the `Project` selector: it offers `No project`, `Refonte` **and**
   `Audit 2026` — the chosen team's project plus the team-less ones. It offers no
   project of `Growth`.
3. Choose `Refonte`, then change the team to `Growth`. Expected: the project selector
   **clears itself** and now offers only `No project` and `Audit 2026`.
4. Choose `Audit 2026`, then change the team back to `Core`: `Audit 2026` **stays
   selected**, since a team-less project is legal everywhere.
5. Type a title and press `Enter`: the ticket is created in the team on display, with
   the chosen project, priority and assignee. Check it by opening the ticket with
   `Enter`: the detail panel shows the project and the priority.
6. **The fix.** Click `All tickets` and press `c`. Expected: the `Team` selector shows
   `Team…`, that is, empty. Type a title and press `Enter`. Expected: **nothing is
   created**, the message `Pick a team first.` appears in the composer's footer, and
   the focus jumps to the team selector, which carries `aria-invalid="true"`. Confirm
   in the Network tab that no `POST /api/tickets` went out.
7. Choose a team from the keyboard (arrows then `Tab`), go back to the title with
   `Shift+Tab`, press `Enter`: the ticket is created, in the chosen team.
8. Select the team-less project `Audit 2026` in the sidebar and press `c`. Expected:
   the `Project` selector is prefilled with `Audit 2026`, and the `Team` selector is
   **empty** — the one path where the context bar blocks, exactly as the spec
   describes it.
9. `Escape` closes the composer from the title field as well as from any of the four
   selectors.

- [ ] **Step 6: Commit**

```bash
cd /Users/elietreport/Projet/Perso/Kanso
git add apps/web/src/components/composer.tsx apps/web/src/components/overlays.tsx apps/web/src/app/globals.css apps/web/src/app/page.tsx
git commit -m "feat(web): composer picks team, project, priority and assignee instead of guessing the team"
```

---

### Task 17: Playwright — the five scenarios against the real stack

**Files:**
- Create: `package.json` (repository root)
- Create: `tsconfig.json` (repository root)
- Create: `playwright.config.ts` (repository root)
- Create: `e2e/README.md`
- Create: `e2e/support.ts`
- Create: `e2e/crud.spec.ts` (scenarios 1 and 2)
- Create: `e2e/permissions.spec.ts` (scenario 3)
- Create: `e2e/disposition.spec.ts` (scenario 4)
- Create: `e2e/keyboard.spec.ts` (scenario 5)
- Modify: `.gitignore` (root, adds the Playwright outputs)
- Test: these files **are** the tests. This is the automated coverage for tasks 12
  through 16.

**Interfaces:**
- Consumes: the stable handles produced by tasks 13 to 16 —
  `aria-label="New team"` and `aria-label="New project"` on the `+` buttons,
  `aria-label="Actions for <name>"` on the `⋯` buttons, `role="menuitem"`, a named
  `role="dialog"`, the fields `Name` / `Key` / `Parent team` / `Team` /
  `Destination team` / `Type <name> to confirm`, the composer's `Team` / `Project` /
  `Priority` / `Assignee` selectors, and the classes `.nav-item`, `.nav-depth-1`,
  `.nav-depth-2`, `.row`, `.row-id`, `.status`, `.row-title-input`, `.shortcuts`,
  `.disposition-warning`. On the registry side, menu entry labels are matched with a
  case-insensitive regular expression, so the tests do not depend on their exact
  wording.
- Produces: `pnpm exec playwright test` at the repository root.

- [ ] **Step 1: Create the root `package.json`**

Create `/Users/elietreport/Projet/Perso/Kanso/package.json`:

```json
{
  "name": "kanso-e2e",
  "version": "0.0.0",
  "private": true,
  "description": "End-to-end suite. Drives the docker compose stack; not a workspace root.",
  "scripts": {
    "test:e2e": "playwright test",
    "test:e2e:ui": "playwright test --ui",
    "typecheck": "tsc --noEmit"
  },
  "devDependencies": {
    "@playwright/test": "1.62.1",
    "typescript": "5.9.3"
  },
  "packageManager": "pnpm@10.30.0"
}
```

There is deliberately **no** `pnpm-workspace.yaml` at the root: `apps/web` carries its
own and stays an independent project, with its own `node_modules`. This
`package.json` installs Playwright and nothing else.

- [ ] **Step 2: Create the root `tsconfig.json`**

Create `/Users/elietreport/Projet/Perso/Kanso/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "noEmit": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "types": ["node"]
  },
  "include": ["playwright.config.ts", "e2e/**/*.ts"]
}
```

Playwright transpiles without type-checking; this file exists so that
`pnpm typecheck` and the editor do it. `"types": ["node"]` covers `process.platform`
and `process.env`, pulled from the `@types/node` dependency `@playwright/test` brings
along.

- [ ] **Step 3: Create `playwright.config.ts`**

Create `/Users/elietreport/Projet/Perso/Kanso/playwright.config.ts`:

```ts
import { defineConfig, devices } from "@playwright/test";

/**
 * No `webServer`.
 *
 * What these tests check is mostly server behaviour — the disposition plans, the
 * renumbering, the 403s — so the stack under test is the `docker compose` one,
 * Postgres included. Starting Next on its own would test a client against nothing.
 * `e2e/README.md` gives the single command that brings it up.
 *
 * One worker, no parallelism: the tests share a database. Each creates its own
 * entities under a unique name, which makes them independent of one another, but two
 * concurrent archives on the same tree would see each other.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.KANSO_WEB_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
```

- [ ] **Step 4: Create `e2e/support.ts`**

```bash
mkdir -p /Users/elietreport/Projet/Perso/Kanso/e2e
```

Create `/Users/elietreport/Projet/Perso/Kanso/e2e/support.ts`:

```ts
import {
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Browser,
  type Locator,
  type Page,
} from "@playwright/test";

export const API_URL = process.env.KANSO_API_URL ?? "http://localhost:8080";
export const WEB_URL = process.env.KANSO_WEB_URL ?? "http://localhost:3000";

export const ADMIN = "owner@kanso.test";
export const MEMBER = "member@kanso.test";

/** The owner's password. It only ever serves to claim a fresh instance. */
const OWNER_PASSWORD = "kanso-e2e-owner-password";

/** Unique per call, so re-running the suite against the same database stays safe. */
export function unique(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
}

/** `@Size(min = 2, max = 8)` on `TeamRequest.key`: five characters fit. */
export function uniqueKey(): string {
  return `E${Math.random().toString(36).slice(2, 6).toUpperCase()}`;
}

/**
 * An HTTP context whose identity comes from the header alone. Every caller gets its
 * own: a login response sets a session cookie, and the `dev` mode filter only kicks
 * in when there is no authentication at all — sharing one context would silently make
 * everybody act as the first identity used.
 */
export async function apiAs(email: string): Promise<APIRequestContext> {
  return playwrightRequest.newContext({
    baseURL: API_URL,
    extraHTTPHeaders: { "X-Kanso-User": email },
  });
}

/**
 * Brings the instance to the minimum state in which the application agrees to show a
 * list: an owner exists, and both test accounts have been through the preferences
 * step. Without the second point, every test would land on `/setup`. Idempotent: the
 * stack is long-lived and the suite is replayed against it.
 */
export async function seedInstance(): Promise<void> {
  const anonymous = await playwrightRequest.newContext({ baseURL: API_URL });
  try {
    const state = await anonymous.get("/api/setup/state");
    expect(state.ok(), `No answer from the API at ${API_URL}. Is the stack up?`).toBeTruthy();
    const body = (await state.json()) as { needsOwner: boolean };
    if (body.needsOwner) {
      const claimed = await anonymous.post("/api/setup/owner", {
        data: { email: ADMIN, displayName: "E2E owner", password: OWNER_PASSWORD },
      });
      expect(claimed.ok(), "Could not claim the instance").toBeTruthy();
    }
  } finally {
    await anonymous.dispose();
  }

  for (const email of [ADMIN, MEMBER]) {
    const api = await apiAs(email);
    try {
      const saved = await api.put("/api/me/preferences", { data: { onboarded: true } });
      expect(saved.ok(), `Could not onboard ${email}`).toBeTruthy();
    } finally {
      await api.dispose();
    }
  }
}

/**
 * A page that acts as somebody.
 *
 * The client reads its identity out of `localStorage` on every request, so it has to
 * be there before the first script runs. `addInitScript` also replays on every
 * navigation, which a one-off `evaluate` would not. `baseURL` is repeated here: a
 * context created by hand does not inherit the configuration's `use` options.
 */
export async function openAs(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: WEB_URL });
  await context.addInitScript((who: string) => {
    window.localStorage.setItem("kanso.devUser", who);
  }, email);
  const page = await context.newPage();
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  return page;
}

// --- API seeding shortcuts ---------------------------------------------------
// Scenarios 2 to 5 only need a stage; scenario 1 alone has to build it with the
// mouse, since it is the one testing the creation paths.

export type SeededTeam = { id: string; name: string; key: string };
export type SeededProject = { id: string; name: string; teamId?: string };
export type SeededTicket = { id: string; identifier: string; title: string };

export async function seedTeam(
  api: APIRequestContext,
  body: { name: string; key: string; parentTeamId?: string },
): Promise<SeededTeam> {
  const response = await api.post("/api/teams", { data: body });
  expect(response.ok(), `Could not create the team ${body.name}`).toBeTruthy();
  return (await response.json()) as SeededTeam;
}

export async function seedProject(
  api: APIRequestContext,
  body: { name: string; teamId?: string },
): Promise<SeededProject> {
  const response = await api.post("/api/projects", { data: body });
  expect(response.ok(), `Could not create the project ${body.name}`).toBeTruthy();
  return (await response.json()) as SeededProject;
}

export async function seedTicket(
  api: APIRequestContext,
  body: { teamId: string; title: string; projectId?: string },
): Promise<SeededTicket> {
  const response = await api.post("/api/tickets", { data: body });
  expect(response.ok(), `Could not create the ticket ${body.title}`).toBeTruthy();
  return (await response.json()) as SeededTicket;
}

// --- interface handles -------------------------------------------------------

/** The sidebar row carrying this name, container included. */
export function sidebarRow(page: Page, name: string): Locator {
  return page.locator(".nav-item").filter({ has: page.getByRole("button", { name, exact: true }) });
}

/** Opens a row's `⋯` menu and returns the open menu. */
export async function openRowMenu(page: Page, name: string): Promise<Locator> {
  await page.getByRole("button", { name: `Actions for ${name}`, exact: true }).click();
  const menu = page.getByRole("menu", { name: `Actions for ${name}` });
  await expect(menu).toBeVisible();
  return menu;
}

/** The ticket row carrying this title. */
export function ticketRow(page: Page, title: string): Locator {
  return page.locator(".row").filter({ hasText: title });
}
```

- [ ] **Step 5: Create `e2e/crud.spec.ts` — scenarios 1 and 2**

Create `/Users/elietreport/Projet/Perso/Kanso/e2e/crud.spec.ts`:

```ts
import { expect, test } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  openAs,
  openRowMenu,
  seedInstance,
  seedProject,
  seedTeam,
  seedTicket,
  sidebarRow,
  ticketRow,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 1. All by mouse, end to end: the only test that builds its stage through
 * the interface, because those paths are exactly what it checks.
 */
test("scenario 1 — a team, a sub-team, a project inside it, a team-less project and a ticket", async ({
  browser,
}) => {
  const page = await openAs(browser, ADMIN);

  const team = unique("Core");
  const subTeam = unique("Mobile");
  const teamProject = unique("Rework");
  const looseProject = unique("Audit");
  const ticket = unique("Fix the OAuth login");

  // A root team, from the + on the Teams header.
  await page.getByRole("button", { name: "New team", exact: true }).click();
  const teamDialog = page.getByRole("dialog");
  await expect(teamDialog).toBeVisible();
  await teamDialog.getByLabel("Name").fill(team);
  await teamDialog.getByLabel("Key").fill(uniqueKey());
  await teamDialog.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("button", { name: team, exact: true })).toBeVisible();

  // A sub-team, from the row's menu.
  await openRowMenu(page, team);
  await page.getByRole("menuitem", { name: /sub-team/i }).click();
  const childDialog = page.getByRole("dialog");
  // The parent is prefilled by the registry action, not retyped by hand.
  await expect(childDialog.getByLabel("Parent team")).not.toHaveValue("");
  await childDialog.getByLabel("Name").fill(subTeam);
  await childDialog.getByLabel("Key").fill(uniqueKey());
  await childDialog.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("button", { name: subTeam, exact: true })).toBeVisible();

  // The sub-team is drawn under its parent, not at the root.
  await expect(sidebarRow(page, subTeam)).toHaveClass(/nav-depth-1/);

  // A project inside the sub-team.
  await openRowMenu(page, subTeam);
  await page.getByRole("menuitem", { name: /project/i }).click();
  const projectDialog = page.getByRole("dialog");
  await expect(projectDialog.getByLabel("Team")).not.toHaveValue("");
  await projectDialog.getByLabel("Name").fill(teamProject);
  await projectDialog.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("button", { name: teamProject, exact: true })).toBeVisible();

  // A team-less project, from the + on the Projects header.
  await page.getByRole("button", { name: "New project", exact: true }).click();
  const looseDialog = page.getByRole("dialog");
  await expect(looseDialog.getByLabel("Team")).toHaveValue("");
  await looseDialog.getByLabel("Name").fill(looseProject);
  await looseDialog.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("button", { name: looseProject, exact: true })).toBeVisible();
  // A team-less project lives at the root, not indented under anything.
  await expect(sidebarRow(page, looseProject)).toHaveClass(/nav-depth-0/);

  // A ticket, in the sub-team, through the composer.
  await page.getByRole("button", { name: subTeam, exact: true }).click();
  await page.keyboard.press("c");
  const title = page.getByPlaceholder("New ticket…");
  await expect(title).toBeFocused();
  // The scope resolved the team: this is the `teams.data[0]` fallback, fixed.
  await expect(page.getByLabel("Team")).not.toHaveValue("");
  await title.fill(ticket);
  await title.press("Enter");
  await expect(ticketRow(page, ticket)).toBeVisible();

  // And everything is findable in the sidebar.
  for (const name of [team, subTeam, teamProject, looseProject]) {
    await expect(page.getByRole("button", { name, exact: true })).toBeVisible();
  }
});

/**
 * Scenario 2. A parent team shows the work of its sub-teams; a project filters to
 * itself alone.
 */
test("scenario 2 — a parent team shows its sub-teams' tickets, a project filters to itself", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);

  const parent = await seedTeam(api, { name: unique("Parent"), key: uniqueKey() });
  const child = await seedTeam(api, {
    name: unique("Child"),
    key: uniqueKey(),
    parentTeamId: parent.id,
  });
  const project = await seedProject(api, { name: unique("Stream"), teamId: parent.id });

  const parentTicket = unique("Parent work");
  const childTicket = unique("Child work");
  const projectTicket = unique("Project work");

  await seedTicket(api, { teamId: parent.id, title: parentTicket });
  await seedTicket(api, { teamId: child.id, title: childTicket });
  await seedTicket(api, { teamId: parent.id, title: projectTicket, projectId: project.id });
  await api.dispose();

  const page = await openAs(browser, ADMIN);

  // The parent team: its own tickets and its sub-team's.
  await page.getByRole("button", { name: parent.name, exact: true }).click();
  await expect(ticketRow(page, parentTicket)).toBeVisible();
  await expect(ticketRow(page, childTicket)).toBeVisible();
  await expect(ticketRow(page, projectTicket)).toBeVisible();

  // The sub-team: its own only.
  await page.getByRole("button", { name: child.name, exact: true }).click();
  await expect(ticketRow(page, childTicket)).toBeVisible();
  await expect(ticketRow(page, parentTicket)).toHaveCount(0);

  // The project: its own only.
  await page.getByRole("button", { name: project.name, exact: true }).click();
  await expect(ticketRow(page, projectTicket)).toBeVisible();
  await expect(ticketRow(page, parentTicket)).toHaveCount(0);
  await expect(ticketRow(page, childTicket)).toHaveCount(0);
});
```

- [ ] **Step 6: Create `e2e/permissions.spec.ts` — scenario 3**

Create `/Users/elietreport/Projet/Perso/Kanso/e2e/permissions.spec.ts`:

```ts
import { expect, test } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  apiAs,
  openAs,
  seedInstance,
  seedProject,
  seedTeam,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 3. Two identities in a single test, which `dev` mode makes possible with
 * no OAuth to simulate: the same page, two headers.
 */
test("scenario 3 — a member sees no team writes, an admin sees them all", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Shape"), key: uniqueKey() });
  const project = await seedProject(api, { name: unique("Shared"), teamId: team.id });
  await api.dispose();

  const admin = await openAs(browser, ADMIN);
  const member = await openAs(browser, MEMBER);

  // The admin: the teams + and the team row's ⋯.
  await expect(admin.getByRole("button", { name: "New team", exact: true })).toBeVisible();
  await expect(
    admin.getByRole("button", { name: `Actions for ${team.name}`, exact: true }),
  ).toHaveCount(1);

  // The member: neither.
  await expect(member.getByRole("button", { name: team.name, exact: true })).toBeVisible();
  await expect(member.getByRole("button", { name: "New team", exact: true })).toHaveCount(0);
  await expect(
    member.getByRole("button", { name: `Actions for ${team.name}`, exact: true }),
  ).toHaveCount(0);

  // The shape of the organisation is an admin decision; the daily work is not.
  // Projects stay open to the member.
  await expect(member.getByRole("button", { name: "New project", exact: true })).toBeVisible();
  await expect(
    member.getByRole("button", { name: `Actions for ${project.name}`, exact: true }),
  ).toHaveCount(1);
});
```

- [ ] **Step 7: Create `e2e/disposition.spec.ts` — scenario 4**

Create `/Users/elietreport/Projet/Perso/Kanso/e2e/disposition.spec.ts`:

```ts
import { expect, test } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  openAs,
  openRowMenu,
  seedInstance,
  seedProject,
  seedTeam,
  seedTicket,
  sidebarRow,
  ticketRow,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 4. Deleting a team while keeping everything.
 *
 * The tree is grandparent → parent → sub-team so that the difference counts: the
 * sub-team must move up to the grandparent, not to the root, which is exactly where
 * the `ON DELETE SET NULL` cascade would have got it wrong.
 */
test("scenario 4 — deleting a team keeping everything re-homes and renumbers what it held", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);

  const grandParent = await seedTeam(api, { name: unique("Root"), key: uniqueKey() });
  const parent = await seedTeam(api, {
    name: unique("Doomed"),
    key: uniqueKey(),
    parentTeamId: grandParent.id,
  });
  const subTeam = await seedTeam(api, {
    name: unique("Survivor"),
    key: uniqueKey(),
    parentTeamId: parent.id,
  });
  const project = await seedProject(api, { name: unique("Kept"), teamId: parent.id });

  const movedTitle = unique("Moves and is renumbered");
  const moved = await seedTicket(api, { teamId: parent.id, title: movedTitle });
  const untouchedTitle = unique("Stays where it is");
  const untouched = await seedTicket(api, { teamId: subTeam.id, title: untouchedTitle });
  await api.dispose();

  expect(moved.identifier.startsWith(`${parent.key}-`)).toBeTruthy();

  const page = await openAs(browser, ADMIN);

  // The starting state, as the sidebar draws it.
  await expect(sidebarRow(page, parent.name)).toHaveClass(/nav-depth-1/);
  await expect(sidebarRow(page, subTeam.name)).toHaveClass(/nav-depth-2/);
  await expect(sidebarRow(page, project.name)).toHaveClass(/nav-depth-2/);

  await openRowMenu(page, parent.name);
  await page.getByRole("menuitem", { name: /^delete$/i }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();

  // Everything defaults to "keep", in all three categories.
  for (const noun of ["sub-teams", "projects", "tickets"]) {
    const group = dialog.getByRole("radiogroup", { name: noun });
    await expect(group.getByRole("radio").first()).toBeChecked();
  }

  // Kept tickets need a destination.
  await dialog.getByLabel("Destination team").selectOption({ label: grandParent.name });

  // The warning announces the prefixes, in this severity as in the other.
  const warning = page.locator(".disposition-warning");
  await expect(warning).toContainText(`${parent.key}-`);
  await expect(warning).toContainText(`${grandParent.key}-`);

  // Only the destructive side asks for the name, and it really asks: clicking without
  // having typed goes nowhere, and says so under the field.
  await dialog.getByRole("button", { name: "Delete" }).click();
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(`Type the name exactly: ${parent.name}`);
  await expect(page.getByRole("button", { name: parent.name, exact: true })).toBeVisible();

  await dialog.getByLabel(/to confirm$/).fill(parent.name);
  await dialog.getByRole("button", { name: "Delete" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // The team is gone; what it held is still reachable, at its new place.
  await expect(page.getByRole("button", { name: parent.name, exact: true })).toHaveCount(0);
  await expect(sidebarRow(page, subTeam.name)).toHaveClass(/nav-depth-1/);
  await expect(sidebarRow(page, project.name)).toHaveClass(/nav-depth-1/);

  // The moved tickets carry the prefix the dialog announced.
  await page.getByRole("button", { name: grandParent.name, exact: true }).click();
  const movedRow = ticketRow(page, movedTitle);
  await expect(movedRow).toBeVisible();
  await expect(movedRow.locator(".row-id")).toContainText(`${grandParent.key}-`);
  await expect(movedRow.locator(".row-id")).not.toContainText(moved.identifier);

  // The ticket of a kept sub-team has not moved at all: no renumbering, identifier
  // untouched. This is the common case, and it has to stay free.
  const untouchedRow = ticketRow(page, untouchedTitle);
  await expect(untouchedRow).toBeVisible();
  await expect(untouchedRow.locator(".row-id")).toHaveText(untouched.identifier);
});
```

- [ ] **Step 8: Create `e2e/keyboard.spec.ts` — scenario 5**

Create `/Users/elietreport/Projet/Perso/Kanso/e2e/keyboard.spec.ts`:

```ts
import { expect, test } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  openAs,
  seedInstance,
  seedTeam,
  seedTicket,
  ticketRow,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 5, and the point of the whole suite.
 *
 * The registry rewrites the keyboard path: this test is what says whether behaviour
 * moved with it. Every assertion describes what the key does *today*, before the
 * switch — not what one would like it to do.
 */
test("scenario 5 — the keyboard does exactly what it did before the registry", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Keys"), key: uniqueKey() });
  const first = unique("Alpha ticket");
  const second = unique("Beta ticket");
  const alpha = await seedTicket(api, { teamId: team.id, title: first });
  await seedTicket(api, { teamId: team.id, title: second });
  await api.dispose();

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: team.name, exact: true }).click();

  const rows = page.locator(".row");
  await expect(rows).toHaveCount(2);
  const selected = page.locator('.row[data-selected="true"]');

  // j / k and ↓ / ↑ move the cursor.
  await ticketRow(page, first).click();
  await expect(selected).toContainText(first);
  await page.keyboard.press("j");
  await expect(selected).toContainText(second);
  await page.keyboard.press("k");
  await expect(selected).toContainText(first);
  await page.keyboard.press("ArrowDown");
  await expect(selected).toContainText(second);
  await page.keyboard.press("ArrowUp");
  await expect(selected).toContainText(first);

  // 1..6 walk the status vocabulary in its natural order.
  await page.keyboard.press("2");
  await expect(selected.locator(".status")).toHaveText("Todo");
  await page.keyboard.press("5");
  await expect(selected.locator(".status")).toHaveText("Done");
  await page.keyboard.press("1");
  await expect(selected.locator(".status")).toHaveText("Backlog");

  // Enter opens the selected ticket.
  await page.keyboard.press("Enter");
  await expect(page.locator(".panel-header")).toContainText(alpha.identifier);
  await page.keyboard.press("Escape");
  await expect(page.locator(".panel-header")).toHaveCount(0);

  // e renames in place.
  await page.keyboard.press("e");
  const editor = page.locator(".row-title-input");
  await expect(editor).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(editor).toHaveCount(0);

  // c opens the composer.
  await page.keyboard.press("c");
  await expect(page.getByPlaceholder("New ticket…")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByPlaceholder("New ticket…")).toHaveCount(0);

  // / puts the focus in the filter, and the filter filters.
  await page.keyboard.press("/");
  const filter = page.getByPlaceholder(/Filter/);
  await expect(filter).toBeFocused();
  await filter.fill(first);
  await expect(rows).toHaveCount(1);
  // Escape in the filter clears it and gives the focus back.
  await page.keyboard.press("Escape");
  await expect(rows).toHaveCount(2);

  // ⌘K / Ctrl+K opens the palette.
  await page.keyboard.press("ControlOrMeta+k");
  await expect(page.getByPlaceholder("Type a command…")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByPlaceholder("Type a command…")).toHaveCount(0);

  // , opens the settings.
  await page.keyboard.press(",");
  await expect(page.getByRole("link", { name: "All settings" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("link", { name: "All settings" })).toHaveCount(0);

  // ? opens the shortcut list.
  await page.keyboard.press("?");
  await expect(page.locator(".shortcuts")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator(".shortcuts")).toHaveCount(0);

  // x archives: the row leaves the list, which does not show archived tickets.
  // Last, because it is the only key that takes away something to work with.
  await ticketRow(page, first).click();
  await expect(selected).toContainText(first);
  await page.keyboard.press("x");
  await expect(ticketRow(page, first)).toHaveCount(0);
  await expect(ticketRow(page, second)).toBeVisible();
});
```

- [ ] **Step 9: Create `e2e/README.md`**

Create `/Users/elietreport/Projet/Perso/Kanso/e2e/README.md`:

````markdown
# End-to-end tests

Playwright, against the real `docker compose` stack: Postgres, the API and the web
client. What these tests check is largely server behaviour — the disposition plans,
the renumbering, the 403s — so there is nothing to simulate. The configuration has
**no** `webServer`: the stack is assumed to be up already.

## Bringing the stack up

From the repository root:

```bash
KANSO_AUTH_MODE=dev docker compose up -d --build --wait
```

`KANSO_AUTH_MODE=dev` is required. In that mode identity comes from the
`X-Kanso-User` header and nothing is verified: two people are playable in one test,
which the permission scenarios need. Under `oidc`, the default mode, there is no
automatable sign-in path.

The web listens on <http://localhost:3000>, the API on <http://localhost:8080>. Both
addresses are overridable with `KANSO_WEB_URL` and `KANSO_API_URL`.

## Installing and running

```bash
pnpm install
pnpm exec playwright install chromium
pnpm test:e2e
```

One file:

```bash
pnpm exec playwright test e2e/crud.spec.ts
```

One test, watching the browser work:

```bash
pnpm exec playwright test e2e/keyboard.spec.ts --headed --debug
```

The HTML report from the last run:

```bash
pnpm exec playwright show-report
```

## Identities

Two accounts, created on the spot by `dev` mode:

| Address             | Role    | What it is there to show                        |
| ------------------- | ------- | ----------------------------------------------- |
| `owner@kanso.test`  | owner   | Team writes, which are admin-only               |
| `member@kanso.test` | member  | What a member does not see                      |

`seedInstance()`, called in every file's `beforeAll`, claims the instance for the
owner if it is brand new and marks both accounts as having been through the
preferences step. Without that second point the application redirects to `/setup` and
no test ever sees a list. The operation is idempotent: the stack is long-lived, and
the suite is replayed against it without being reset.

Every page sets its identity with `context.addInitScript`, which writes
`localStorage["kanso.devUser"]` before the page's first script runs and replays on
every navigation.

## Isolation

One worker, no parallelism: the tests share a database. Each test creates its entities
under a unique name (`unique()`, `uniqueKey()`), so re-running the suite against an
already-populated database works — it accumulates, it does not break. To start from
scratch:

```bash
docker compose down -v && KANSO_AUTH_MODE=dev docker compose up -d --build --wait
```

## The five scenarios

| File                     | What it holds                                                               |
| ------------------------ | --------------------------------------------------------------------------- |
| `crud.spec.ts`           | 1. Create team, sub-team, project, team-less project, ticket. 2. Scopes.      |
| `permissions.spec.ts`    | 3. A member sees neither the teams `+` nor any team `⋯`.                     |
| `disposition.spec.ts`    | 4. Delete a team keeping everything: re-homing and renumbering.               |
| `keyboard.spec.ts`       | 5. Keyboard non-regression.                                                  |

Scenario 5 is the point of the whole thing. The action registry rewrites the keyboard
path; this test is what says whether behaviour moved with it. When an assertion is in
doubt, the reference is what the key did before the switch, not what one would like it
to do.
````

- [ ] **Step 10: Ignore the Playwright outputs**

In `/Users/elietreport/Projet/Perso/Kanso/.gitignore`, find:

```gitignore
# node / next
node_modules/
apps/web/.next/
apps/web/out/
apps/web/next-env.d.ts
.pnpm-store/
```

and replace it with:

```gitignore
# node / next
node_modules/
apps/web/.next/
apps/web/out/
apps/web/next-env.d.ts
.pnpm-store/

# playwright
/test-results/
/playwright-report/
/blob-report/
/playwright/.cache/
```

- [ ] **Step 11: Install and verify**

```bash
cd /Users/elietreport/Projet/Perso/Kanso
pnpm install
pnpm exec playwright install chromium
pnpm typecheck
```

`pnpm typecheck` must come back saying nothing.

Bring the full stack up and run the suite:

```bash
cd /Users/elietreport/Projet/Perso/Kanso
KANSO_AUTH_MODE=dev docker compose up -d --build --wait
pnpm test:e2e
```

Expected: `5 passed`. On a failure, the trace of the offending run sits in
`playwright-report/` (`pnpm exec playwright show-report`) and holds the screenshots
and the film of the actions.

Then check the suite really is replayable against an already-populated database —
which is what `unique()` promises:

```bash
cd /Users/elietreport/Projet/Perso/Kanso
pnpm test:e2e
```

Expected: `5 passed` again, with nothing reset.

Finally, check nothing was left behind in `apps/web`:

```bash
cd /Users/elietreport/Projet/Perso/Kanso/apps/web
pnpm exec tsc --noEmit
pnpm lint
```

- [ ] **Step 12: Commit**

```bash
cd /Users/elietreport/Projet/Perso/Kanso
git add package.json tsconfig.json playwright.config.ts pnpm-lock.yaml .gitignore e2e/
git commit -m "test(e2e): playwright suite covering CRUD, scopes, permissions, disposition and the keyboard"
```
