# Team Statuses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A team defines its own list of statuses — add, rename, reorder, remove — each declaring one of the five categories, with everything that reasons about work reading the category.

**Architecture:** A `team_statuses` catalogue holds one row per status per team, seeded with six rows at team creation. `tickets.status` keeps its text and its wire string; a composite foreign key `(team_id, status) → team_statuses(team_id, key)` holds the invariant in the database and lets drafts through under `MATCH SIMPLE`. The key is derived from the label so two spellings of one word are one row. A scope of one team groups by that team's positions; a scope spanning teams groups by category.

**Tech Stack:** Kotlin + Spring Boot + Exposed v1 + Flyway + Postgres 16; Next.js + React Query + zustand; Vitest (node and happy-dom projects) and Playwright.

**Spec:** `docs/superpowers/specs/2026-09-07-team-statuses-design.md`

## Global Constraints

- **The wire string never changes.** `TicketResponse.status` stays the status key as text. No response shape may lose a key; `TeamResponse.statuses` is added as always-present, never optional.
- **Categories are exactly five, closed:** `backlog`, `unstarted`, `started`, `completed`, `canceled`. Their order, everywhere, is that order.
- **The key is derived, never accepted from a client:** lowercase, accents folded (NFD, combining marks stripped), every run of non-alphanumeric characters to a single `_`, leading and trailing `_` trimmed. A label with no letter or digit is refused.
- **The key is immutable.** A rename writes `label` only.
- **A team always has at least one status.** The last one cannot be removed.
- **Removing a status names its destination.** Tickets move there, each move writing a `status_changed` activity row.
- **`position` carries no unique index.** Readers sort by `(position, key)`.
- **Migration number:** `V41__team_statuses.sql` — re-check the highest `V*` on `main` immediately before committing, per the repo's history of numbers moving mid-branch.
- **Tests:** every task is TDD. Server tests are `class …Test : PostgresTest()` with `@Transactional`, `kotlin.test` assertions, `@Autowired` services. Web logic tests are `.ts` (node), component tests `.tsx` (happy-dom).

---

## File Structure

**Server — created**

- `apps/api/src/main/resources/db/migration/V41__team_statuses.sql` — the table, the seed, the constraint swap.
- `apps/api/src/main/kotlin/dev/kanso/domain/TeamStatus.kt` — the `TeamStatus` row type, `statusKeyOf` (the derivation), and `DefaultStatus`'s seed list.
- `apps/api/src/main/kotlin/dev/kanso/repo/TeamStatusRepository.kt` — reads and writes on the catalogue.
- `apps/api/src/main/kotlin/dev/kanso/service/TeamStatusService.kt` — the four moves and their refusals.
- `apps/api/src/main/kotlin/dev/kanso/api/TeamStatusController.kt` — `/api/teams/{teamId}/statuses`.
- Tests: `domain/TeamStatusKeyTest.kt`, `repo/TeamStatusRepositoryTest.kt`, `service/TeamStatusServiceTest.kt`, `service/TicketStatusRebaseTest.kt`, `api/TeamStatusControllerTest.kt`.

**Server — modified**

- `domain/Model.kt` — `TicketStatus` renamed `DefaultStatus`; its `category` and `label` stay as the seed's source.
- `domain/StatusOrder.kt` — `WORKFLOW` becomes the seed order; gains `CATEGORY_ORDER`.
- `db/Tables.kt` — `object TeamStatuses`.
- `repo/TicketQueryRepository.kt:353` — `statusRank` becomes a function of a rank map.
- `service/TicketService.kt` — validation against the catalogue, the rebase on a team move, the grouped rank map.
- `service/TeamService.kt:338` — `create` seeds the six rows.
- `api/Dtos.kt:107` — `TeamResponse.statuses`.
- `mcp/McpErrors.kt` — a refusal that names the team's vocabulary.
- `notion/…` mirror push and inbound label resolution.

**Web — created**

- `apps/web/src/lib/statuses.ts` — the scope's vocabulary: labels, order, category lookup, from a catalogue.
- `apps/web/src/components/settings/team-statuses.tsx` — the editor.
- Tests: `lib/statuses.test.ts`, `components/settings/team-statuses.test.tsx`.

**Web — modified**

- `lib/api/core.ts` — `TeamStatus` type, `TICKET_STATUSES` demoted to `DEFAULT_STATUSES`, `Team.statuses`.
- `lib/status.ts`, `lib/status-order.ts` — category-keyed labels and colours, the category order.
- `lib/actions/core.ts` — keys `1`–`9` against the scope's list.
- `components/pills.tsx` — a pill drawn from a catalogue entry.

**e2e — modified**

- `e2e/29-team-statuses.spec.ts` (new), `e2e/README.md` (the table).

---

## Task 1: The key is derived from the label

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/domain/TeamStatus.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/domain/TeamStatusKeyTest.kt`

**Interfaces:**
- Consumes: `StatusCategory` from `domain/Model.kt`.
- Produces: `data class TeamStatus(val teamId: UUID, val key: String, val label: String, val category: StatusCategory, val position: Int)`; `fun statusKeyOf(label: String): String` (throws `BadRequestException` when the label yields no key).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.kanso.domain

import dev.kanso.api.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeamStatusKeyTest {

	@Test
	fun `two spellings of one word are one key`() {
		// The point of deriving rather than accepting: `UNIQUE (team_id, key)` then
		// refuses the second spelling, with no rule for a screen to enforce.
		assertEquals("in_progress", statusKeyOf("In Progress"))
		assertEquals("in_progress", statusKeyOf("in progress"))
		assertEquals("in_progress", statusKeyOf("IN  PROGRESS"))
		assertEquals("in_progress", statusKeyOf("  in-progress  "))
	}

	@Test
	fun `accents fold, because two teams' keyboards are not the argument`() {
		assertEquals("livre", statusKeyOf("Livré"))
		assertEquals("en_cours", statusKeyOf("En cours"))
	}

	@Test
	fun `a label with no letter or digit has no key`() {
		assertEquals(
			"A status needs a letter or a digit in its name",
			assertFailsWith<BadRequestException> { statusKeyOf("…") }.message,
		)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.domain.TeamStatusKeyTest'`
Expected: FAIL — `Unresolved reference: statusKeyOf`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dev.kanso.domain

import dev.kanso.api.BadRequestException
import java.text.Normalizer
import java.util.UUID

/**
 * One row of a team's catalogue — `KAN-28`.
 *
 * [key] is what `tickets.status` holds and what every saved view and filter addresses,
 * so it is derived once at creation and never written again. [label] is the word the
 * team reads, and the only field a rename touches.
 */
data class TeamStatus(
	val teamId: UUID,
	val key: String,
	val label: String,
	val category: StatusCategory,
	val position: Int,
)

/**
 * The key a label produces, and the reason a client never sends one.
 *
 * Accents fold and case is dropped, so `In Progress` and `in progress` are the same
 * key and the primary key refuses the second — two spellings of one word are one row
 * rather than a rule an interface has to enforce. A label with nothing alphanumeric in
 * it cannot produce a key, and is refused here rather than stored as `_`.
 */
fun statusKeyOf(label: String): String {
	val folded = Normalizer.normalize(label, Normalizer.Form.NFD)
		.replace(Regex("\\p{Mn}+"), "")
		.lowercase()
	val key = folded.replace(Regex("[^a-z0-9]+"), "_").trim('_')
	if (key.isEmpty()) throw BadRequestException("A status needs a letter or a digit in its name")
	return key
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.domain.TeamStatusKeyTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/domain/TeamStatus.kt apps/api/src/test/kotlin/dev/kanso/domain/TeamStatusKeyTest.kt
git commit -m "feat(statuses): a status key is derived from its label, never typed"
```

---

## Task 2: The catalogue on disk

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V41__team_statuses.sql`
- Modify: `apps/api/src/main/kotlin/dev/kanso/db/Tables.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/repo/TeamStatusRepository.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/repo/TeamStatusRepositoryTest.kt`

**Interfaces:**
- Consumes: `TeamStatus`, `statusKeyOf` (Task 1).
- Produces: `TeamStatusRepository.forTeam(teamId): List<TeamStatus>`, `.forTeams(ids: Collection<UUID>): Map<UUID, List<TeamStatus>>`, `.insert(TeamStatus)`, `.rename(teamId, key, label): Boolean`, `.reposition(teamId, keysInOrder: List<String>)`, `.delete(teamId, key): Boolean`, `.seed(teamId)`.

- [ ] **Step 1: Write the migration**

```sql
-- KAN-28. A team's own words for its work.
--
-- The catalogue is per team and copied at creation rather than inherited from a default
-- that lives elsewhere: a team's list is then always its own rows, and every reader has
-- one case to handle instead of "its rows, or else the enum".
--
-- `category` is a CHECK and not a table, for the reason `activity_kind_chk` is one — the
-- vocabulary is closed, `StatusCategory` mirrors it, and a sixth category should be a
-- migration somebody writes on purpose rather than a row somebody inserts.
--
-- `position` gets no unique index on purpose. A reorder is a swap, and a unique index is
-- checked as an UPDATE walks its rows: swapping two positions in one statement would
-- raise a violation halfway through unless the constraint were deferred. Readers sort by
-- (position, key), so a gap or a tie is invisible and a reorder cannot half-fail.
CREATE TABLE team_statuses (
  team_id   uuid NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
  key       text NOT NULL,
  label     text NOT NULL,
  category  text NOT NULL,
  position  integer NOT NULL,
  PRIMARY KEY (team_id, key),
  CONSTRAINT team_statuses_category_chk
    CHECK (category IN ('backlog', 'unstarted', 'started', 'completed', 'canceled'))
);

-- The second half of the duplicate guard, and it exists for the sentence: without it a
-- second "In Progress" is refused as a conflict on a key nobody typed. `users_email_lower_uniq`
-- is in this schema for the same reason.
CREATE UNIQUE INDEX team_statuses_label_uniq ON team_statuses (team_id, lower(label));

-- Every existing team gets the six it already had, in `StatusOrder.WORKFLOW`'s order.
INSERT INTO team_statuses (team_id, key, label, category, position)
SELECT t.id, s.key, s.label, s.category, s.position
FROM teams t
CROSS JOIN (VALUES
  ('backlog',     'Backlog',     'backlog',   0),
  ('todo',        'Todo',        'unstarted', 1),
  ('in_progress', 'In progress', 'started',   2),
  ('in_review',   'In review',   'started',   3),
  ('done',        'Done',        'completed', 4),
  ('canceled',    'Canceled',    'canceled',  5)
) AS s (key, label, category, position);

-- The seed first, or the foreign key below has nothing to point at.
--
-- MATCH SIMPLE is what makes this work for drafts: a row with NULL in any referencing
-- column satisfies the constraint without a lookup, so a ticket with no team passes
-- untouched while a ticket that has one cannot hold a status its team never defined.
ALTER TABLE tickets DROP CONSTRAINT tickets_status_chk;
ALTER TABLE tickets ADD CONSTRAINT tickets_status_fk
  FOREIGN KEY (team_id, status) REFERENCES team_statuses (team_id, key);
```

- [ ] **Step 2: Add the Exposed table**

In `apps/api/src/main/kotlin/dev/kanso/db/Tables.kt`, after `object Teams`:

```kotlin
object TeamStatuses : Table("team_statuses") {
	val teamId = javaUUID("team_id")
	val key = text("key")
	val label = text("label")
	val category = text("category")
	val position = integer("position")
	override val primaryKey = PrimaryKey(teamId, key)
}
```

- [ ] **Step 3: Write the failing repository test**

```kotlin
package dev.kanso.repo

import dev.kanso.PostgresTest
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TeamStatus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class TeamStatusRepositoryTest : PostgresTest() {

	@Autowired lateinit var statuses: TeamStatusRepository
	@Autowired lateinit var teams: TeamRepository

	private fun team() = teams.insert("Statuses ${UUID.randomUUID()}", "S${(1..9999).random()}", null)

	@Test
	fun `a seeded team reads the six it always had, in order`() {
		val team = team()
		statuses.seed(team.id)

		assertEquals(
			listOf("backlog", "todo", "in_progress", "in_review", "done", "canceled"),
			statuses.forTeam(team.id).map { it.key },
		)
		assertEquals(StatusCategory.STARTED, statuses.forTeam(team.id)[3].category)
	}

	@Test
	fun `a reorder is one write and cannot half-fail on a swap`() {
		val team = team()
		statuses.seed(team.id)

		// The two positions trade places. With a unique index on (team_id, position) this
		// is the statement that would raise mid-walk.
		statuses.reposition(team.id, listOf("todo", "backlog", "in_progress", "in_review", "done", "canceled"))

		assertEquals(listOf("todo", "backlog"), statuses.forTeam(team.id).take(2).map { it.key })
	}

	@Test
	fun `a rename leaves the key alone`() {
		val team = team()
		statuses.seed(team.id)

		assertTrue(statuses.rename(team.id, "done", "Livré"))

		val row = statuses.forTeam(team.id).single { it.key == "done" }
		assertEquals("Livré", row.label)
	}

	@Test
	fun `reading many teams at once keeps them apart`() {
		val one = team()
		val other = team()
		statuses.seed(one.id)
		statuses.insert(TeamStatus(other.id, "nouveau", "Nouveau", StatusCategory.UNSTARTED, 0))

		val both = statuses.forTeams(listOf(one.id, other.id))

		assertEquals(6, both.getValue(one.id).size)
		assertEquals(listOf("nouveau"), both.getValue(other.id).map { it.key })
	}

	@Test
	fun `a deleted status is gone`() {
		val team = team()
		statuses.seed(team.id)

		assertTrue(statuses.delete(team.id, "in_review"))

		assertNull(statuses.forTeam(team.id).firstOrNull { it.key == "in_review" })
	}
}
```

- [ ] **Step 4: Run it to see it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.repo.TeamStatusRepositoryTest'`
Expected: FAIL — `Unresolved reference: TeamStatusRepository`.

- [ ] **Step 5: Write the repository**

```kotlin
package dev.kanso.repo

import dev.kanso.db.TeamStatuses
import dev.kanso.domain.DEFAULT_STATUS_SEED
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TeamStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TeamStatusRepository {

	/** Sorted by `(position, key)` — the key breaks a tie no screen should be able to see. */
	fun forTeam(teamId: UUID): List<TeamStatus> =
		TeamStatuses.selectAll().where { TeamStatuses.teamId eq teamId }
			.orderBy(TeamStatuses.position to SortOrder.ASC, TeamStatuses.key to SortOrder.ASC)
			.map { it.toTeamStatus() }

	/** One query for a list of teams, because the list screen draws several at once. */
	fun forTeams(ids: Collection<UUID>): Map<UUID, List<TeamStatus>> =
		if (ids.isEmpty()) emptyMap()
		else TeamStatuses.selectAll().where { TeamStatuses.teamId inList ids }
			.orderBy(TeamStatuses.position to SortOrder.ASC, TeamStatuses.key to SortOrder.ASC)
			.map { it.toTeamStatus() }
			.groupBy { it.teamId }

	fun insert(status: TeamStatus) {
		TeamStatuses.insert {
			it[teamId] = status.teamId
			it[key] = status.key
			it[label] = status.label
			it[category] = status.category.wire
			it[position] = status.position
		}
	}

	/** The six a team starts with. `DEFAULT_STATUS_SEED` is the one place they are written. */
	fun seed(teamId: UUID) {
		DEFAULT_STATUS_SEED.forEachIndexed { index, seed ->
			insert(TeamStatus(teamId, seed.key, seed.label, seed.category, index))
		}
	}

	fun rename(teamId: UUID, key: String, label: String): Boolean =
		TeamStatuses.update({ (TeamStatuses.teamId eq teamId) and (TeamStatuses.key eq key) }) {
			it[TeamStatuses.label] = label
		} > 0

	/**
	 * The whole order in one call. Positions are rewritten from the list's indices rather
	 * than swapped pairwise: a caller that disagrees about how many statuses exist is
	 * refused by the service above, and this cannot leave two rows on one position in a
	 * way any reader would notice.
	 */
	fun reposition(teamId: UUID, keysInOrder: List<String>) {
		keysInOrder.forEachIndexed { index, key ->
			TeamStatuses.update({ (TeamStatuses.teamId eq teamId) and (TeamStatuses.key eq key) }) {
				it[position] = index
			}
		}
	}

	fun delete(teamId: UUID, key: String): Boolean =
		TeamStatuses.deleteWhere { (TeamStatuses.teamId eq teamId) and (TeamStatuses.key eq key) } > 0

	private fun ResultRow.toTeamStatus() = TeamStatus(
		teamId = this[TeamStatuses.teamId],
		key = this[TeamStatuses.key],
		label = this[TeamStatuses.label],
		category = StatusCategory.from(this[TeamStatuses.category]),
		position = this[TeamStatuses.position],
	)
}
```

Add to `domain/TeamStatus.kt`:

```kotlin
/** What a team starts with: the six `DefaultStatus` names, in `StatusOrder.WORKFLOW` order. */
data class StatusSeed(val key: String, val label: String, val category: StatusCategory)

val DEFAULT_STATUS_SEED: List<StatusSeed>
	get() = StatusOrder.WORKFLOW.map { StatusSeed(it.wire, it.label, it.category) }
```

- [ ] **Step 6: Run it to see it pass**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.repo.TeamStatusRepositoryTest'`
Expected: PASS. Flyway applies `V41` against the test container automatically.

- [ ] **Step 7: Prove the foreign key holds, and lets a draft through**

Append to the same test file:

```kotlin
	@Test
	fun `a ticket cannot hold a status its team never defined`() {
		val team = team()
		statuses.seed(team.id)
		statuses.delete(team.id, "in_review")

		// The database's answer, not a service's: this is the invariant a new write path
		// cannot bypass.
		assertFailsWith<ExposedSQLException> {
			tickets.insert(teamId = team.id, title = "Refused", status = "in_review")
		}
	}
```

Adapt the call to `TicketRepository`'s actual `insert` signature; the assertion is the
`ExposedSQLException` from `tickets_status_fk`. Then:

```kotlin
	@Test
	fun `a draft holds whatever the default vocabulary says, with no team to ask`() {
		val draft = tickets.insert(teamId = null, title = "Draft", status = "in_review")

		assertEquals("in_review", draft.status.wire)
	}
```

- [ ] **Step 8: Run both, then commit**

```bash
cd apps/api && ./gradlew test --tests 'dev.kanso.repo.TeamStatusRepositoryTest'
git add apps/api/src/main/resources/db/migration/V41__team_statuses.sql apps/api/src/main/kotlin/dev/kanso/db/Tables.kt apps/api/src/main/kotlin/dev/kanso/repo/TeamStatusRepository.kt apps/api/src/main/kotlin/dev/kanso/domain/TeamStatus.kt apps/api/src/test/kotlin/dev/kanso/repo/TeamStatusRepositoryTest.kt
git commit -m "feat(statuses): V41 gives every team its own six, and Postgres holds the invariant"
```

---

## Task 3: `TicketStatus` becomes `DefaultStatus`

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/domain/Model.kt:41-72`
- Modify: `apps/api/src/main/kotlin/dev/kanso/domain/StatusOrder.kt`
- Modify: every file the compiler names (35 today)
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TeamService.kt:338`
- Test: `apps/api/src/test/kotlin/dev/kanso/domain/StatusOrderTest.kt`

**Interfaces:**
- Consumes: `TeamStatusRepository.seed` (Task 2).
- Produces: `enum class DefaultStatus`, `StatusOrder.CATEGORY_ORDER: List<StatusCategory>`, `StatusOrder.rankOfCategory(StatusCategory): Int`.

**Why a rename:** `TicketStatus.entries` used as "the statuses of a team" is the bug this
ticket is about, and no test finds it. Renaming turns every such site into a compile
error somebody answers one at a time. Sites that legitimately mean *the default six* —
the seeder, the draft vocabulary, the Notion import's column mapping — keep using it
under its new name.

- [ ] **Step 1: Write the failing test**

```kotlin
	@Test
	fun `the category order is the one both sides agree on`() {
		assertEquals(
			listOf(
				StatusCategory.BACKLOG,
				StatusCategory.UNSTARTED,
				StatusCategory.STARTED,
				StatusCategory.COMPLETED,
				StatusCategory.CANCELED,
			),
			StatusOrder.CATEGORY_ORDER,
		)
	}

	@Test
	fun `every category has a rank, and none shares one`() {
		val ranks = StatusCategory.entries.map { StatusOrder.rankOfCategory(it) }
		assertEquals(ranks.distinct(), ranks)
		assertEquals(StatusCategory.entries.size, ranks.size)
	}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.domain.StatusOrderTest'`
Expected: FAIL — `Unresolved reference: CATEGORY_ORDER`.

- [ ] **Step 3: Add the category order and rename the enum**

In `StatusOrder.kt`, keep `WORKFLOW` and add:

```kotlin
	/**
	 * The order a scope spanning teams reads in, and the one ordering left that is still a
	 * constant on both sides of the wire.
	 *
	 * `WORKFLOW` stopped being that the moment a team could reorder its own list — it is
	 * now the seed a new team starts with, and nothing else. This is what replaced it as
	 * the two-sided agreement, and it can be one because the categories are closed:
	 * `lib/status-order.ts` pins the same sequence, and the pair of tests fails on
	 * whichever side drifts.
	 */
	val CATEGORY_ORDER: List<StatusCategory> = listOf(
		StatusCategory.BACKLOG,
		StatusCategory.UNSTARTED,
		StatusCategory.STARTED,
		StatusCategory.COMPLETED,
		StatusCategory.CANCELED,
	)

	/** One-based, for the same reason [rankOf] is: the rank is rendered as a SQL `CASE`. */
	fun rankOfCategory(category: StatusCategory): Int = CATEGORY_ORDER.indexOf(category) + 1
```

Then rename `TicketStatus` to `DefaultStatus` (an IDE rename, or
`grep -rl 'TicketStatus' apps/api/src | xargs sed -i '' 's/TicketStatus/DefaultStatus/g'`
followed by reading every hunk). Update `WORKFLOW`'s docstring: it opens by calling
itself "the one status order the server has an opinion about", which is about to be
false — it is now the order a team is seeded in.

- [ ] **Step 4: Seed on team creation**

In `TeamService.create`, after `teams.insert(...)`:

```kotlin
		val team = teams.insert(name, resolveKey(name, key), parentTeamId)
		// Before anything can hold a status: a team with an empty catalogue could hold no
		// tickets at all, and `tickets_status_fk` would say so as a constraint violation
		// rather than as a sentence.
		statuses.seed(team.id)
```

Add `private val statuses: TeamStatusRepository` to the constructor.

- [ ] **Step 5: Run the whole server suite**

Run: `cd apps/api && ./gradlew test`
Expected: PASS. Every compile error from the rename is answered; a site that meant "the
team's statuses" is left failing to compile until Task 5 or 6 gives it the catalogue.

- [ ] **Step 6: Commit**

```bash
git add -A apps/api/src
git commit -m "refactor(statuses): TicketStatus is DefaultStatus, and a new team is seeded with it"
```

---

## Task 4: The four moves

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/service/TeamStatusService.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/api/TeamStatusController.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TeamStatusServiceTest.kt`

**Interfaces:**
- Consumes: `TeamStatusRepository` (Task 2), `statusKeyOf` (Task 1), `TeamService.requireConfigurator`.
- Produces: `TeamStatusService.list(actor, teamId)`, `.add(actor, teamId, label, category)`, `.rename(actor, teamId, key, label)`, `.reorder(actor, teamId, keys)`, `.remove(actor, teamId, key, moveTo)`; DTO `TeamStatusDto(key, label, category, position)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Transactional
class TeamStatusServiceTest : PostgresTest() {

	@Autowired lateinit var service: TeamStatusService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var activity: ActivityService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun owner() = users.createLocalUser(
		email = "statuses-${UUID.randomUUID()}@kanso.test",
		displayName = "Owner",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.OWNER,
	)

	@Test
	fun `a second spelling of one word is refused by name`() {
		val actor = owner()
		val team = teams.create(actor, "Support", null, null)
		service.add(actor, team.id, "Attente client", StatusCategory.STARTED)

		assertEquals(
			"""This team already has a status called "attente client"""",
			assertFailsWith<ConflictException> {
				service.add(actor, team.id, "attente client", StatusCategory.STARTED)
			}.message,
		)
	}

	@Test
	fun `removing a status moves its tickets and says so in the activity`() {
		val actor = owner()
		val team = teams.create(actor, "Support", null, null)
		val ticket = tickets.create(actor = actor, teamId = team.id, title = "Ouvert", status = DefaultStatus.IN_REVIEW)

		service.remove(actor, team.id, "in_review", moveTo = "in_progress")

		assertEquals("in_progress", tickets.get(actor, ticket.id).status.wire)
		// The ticket did change status. A number that moved with no line explaining it is
		// a burndown nobody trusts.
		assertTrue(
			activity.forTicket(ticket.id).any {
				it.kind == ActivityKind.STATUS_CHANGED && it.payload["to"] == "in_progress"
			},
		)
	}

	@Test
	fun `a team keeps at least one status`() {
		val actor = owner()
		val team = teams.create(actor, "Support", null, null)
		for (key in listOf("backlog", "todo", "in_progress", "in_review", "done")) {
			service.remove(actor, team.id, key, moveTo = "canceled")
		}

		assertEquals(
			"A team needs somewhere to put work — this is its last status",
			assertFailsWith<ConflictException> {
				service.remove(actor, team.id, "canceled", moveTo = "canceled")
			}.message,
		)
	}

	@Test
	fun `a reorder that disagrees about how many statuses exist is refused`() {
		val actor = owner()
		val team = teams.create(actor, "Support", null, null)

		assertFailsWith<BadRequestException> {
			service.reorder(actor, team.id, listOf("done", "backlog"))
		}
	}

	@Test
	fun `a rename keeps the key, because saved views hold keys`() {
		val actor = owner()
		val team = teams.create(actor, "Support", null, null)

		service.rename(actor, team.id, "done", "Livré")

		val row = service.list(actor, team.id).single { it.key == "done" }
		assertEquals("Livré", row.label)
	}

	@Test
	fun `a member may read the list and not change it`() {
		val actor = owner()
		val team = teams.create(actor, "Support", null, null)
		val member = users.createLocalUser(
			email = "member-${UUID.randomUUID()}@kanso.test",
			displayName = "Member",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.MEMBER,
		)

		assertEquals(6, service.list(member, team.id).size)
		assertFailsWith<AccessDeniedException> { service.rename(member, team.id, "done", "Livré") }
	}
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.service.TeamStatusServiceTest'`
Expected: FAIL — `Unresolved reference: TeamStatusService`.

- [ ] **Step 3: Write the service**

```kotlin
package dev.kanso.service

import dev.kanso.api.BadRequestException
import dev.kanso.api.ConflictException
import dev.kanso.domain.*
import dev.kanso.repo.TeamStatusRepository
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A team's catalogue, and the four things somebody may do to it — `KAN-28`.
 *
 * Reading is open to anybody who can see the team, because a status is a word every
 * screen prints. Changing it is a configurator's, like renaming the team itself: the
 * vocabulary is the team's shape rather than its content.
 */
@Service
class TeamStatusService(
	private val statuses: TeamStatusRepository,
	private val tickets: TicketRepository,
	private val teams: TeamService,
	private val activity: ActivityService,
) {

	fun list(actor: User, teamId: UUID): List<TeamStatus> {
		teams.get(teamId)
		return statuses.forTeam(teamId)
	}

	@Transactional
	fun add(actor: User, teamId: UUID, label: String, category: StatusCategory): TeamStatus {
		teams.requireConfigurator(actor)
		val existing = statuses.forTeam(teamId)
		val key = statusKeyOf(label)
		// Both halves of the duplicate guard are checked here so the refusal is a
		// sentence; the primary key and `team_statuses_label_uniq` are what make the
		// check unmissable rather than what reports it.
		if (existing.any { it.key == key } || existing.any { it.label.equals(label, ignoreCase = true) }) {
			throw ConflictException("""This team already has a status called "${label.lowercase()}"""")
		}
		val row = TeamStatus(teamId, key, label, category, existing.size)
		statuses.insert(row)
		return row
	}

	@Transactional
	fun rename(actor: User, teamId: UUID, key: String, label: String): TeamStatus {
		teams.requireConfigurator(actor)
		val existing = statuses.forTeam(teamId)
		val row = existing.firstOrNull { it.key == key }
			?: throw BadRequestException("This team has no status $key")
		if (existing.any { it.key != key && it.label.equals(label, ignoreCase = true) }) {
			throw ConflictException("""This team already has a status called "${label.lowercase()}"""")
		}
		statuses.rename(teamId, key, label)
		return row.copy(label = label)
	}

	@Transactional
	fun reorder(actor: User, teamId: UUID, keys: List<String>): List<TeamStatus> {
		teams.requireConfigurator(actor)
		val existing = statuses.forTeam(teamId).map { it.key }
		// A partial order is refused rather than merged: a client sending five keys for a
		// six-status team is a client that has not seen the sixth, and merging would put
		// it somewhere nobody chose.
		if (keys.sorted() != existing.sorted()) {
			throw BadRequestException("A reorder has to name every status of the team, once")
		}
		statuses.reposition(teamId, keys)
		return statuses.forTeam(teamId)
	}

	@Transactional
	fun remove(actor: User, teamId: UUID, key: String, moveTo: String) {
		teams.requireConfigurator(actor)
		val existing = statuses.forTeam(teamId)
		if (existing.none { it.key == key }) throw BadRequestException("This team has no status $key")
		if (existing.size == 1) {
			throw ConflictException("A team needs somewhere to put work — this is its last status")
		}
		if (moveTo == key || existing.none { it.key == moveTo }) {
			throw BadRequestException("Removing a status has to name another of this team's statuses to move its tickets to")
		}
		// The tickets first: `tickets_status_fk` would refuse the delete otherwise, and the
		// refusal would be a constraint violation instead of the move the caller asked for.
		for (ticket in tickets.forTeamWithStatus(teamId, key)) {
			tickets.updateStatus(ticket.id, moveTo)
			activity.statusChanged(actor, ticket.id, from = key, to = moveTo)
		}
		statuses.delete(teamId, key)
	}
}
```

Add `TicketRepository.forTeamWithStatus(teamId, status): List<Ticket>` and
`.updateStatus(id, status)` if absent, mirroring `LabelRepository`'s shape. Reuse
`ActivityService`'s existing status-changed writer; if its signature differs, adapt the
call rather than adding a second writer.

- [ ] **Step 4: Run it to see it pass**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.service.TeamStatusServiceTest'`
Expected: PASS.

- [ ] **Step 5: The endpoints**

```kotlin
package dev.kanso.api

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TeamStatus
import dev.kanso.service.TeamStatusService
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** `KAN-28`. Beside `LabelController`, which is the same shape for the same reason. */
@RestController
@RequestMapping("/api/teams/{teamId}/statuses")
class TeamStatusController(
	private val statuses: TeamStatusService,
	private val currentUser: CurrentUser,
) {

	@GetMapping
	fun list(@PathVariable teamId: UUID): List<TeamStatusDto> =
		statuses.list(currentUser.require(), teamId).map(TeamStatusDto::of)

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun add(@PathVariable teamId: UUID, @RequestBody request: TeamStatusCreateRequest): TeamStatusDto =
		TeamStatusDto.of(
			statuses.add(currentUser.require(), teamId, request.label, StatusCategory.from(request.category))
		)

	@PatchMapping("/{key}")
	fun rename(@PathVariable teamId: UUID, @PathVariable key: String, @RequestBody request: TeamStatusRenameRequest): TeamStatusDto =
		TeamStatusDto.of(statuses.rename(currentUser.require(), teamId, key, request.label))

	@PutMapping("/order")
	fun reorder(@PathVariable teamId: UUID, @RequestBody request: TeamStatusOrderRequest): List<TeamStatusDto> =
		statuses.reorder(currentUser.require(), teamId, request.keys).map(TeamStatusDto::of)

	@DeleteMapping("/{key}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun remove(@PathVariable teamId: UUID, @PathVariable key: String, @RequestParam moveTo: String) =
		statuses.remove(currentUser.require(), teamId, key, moveTo)
}

data class TeamStatusCreateRequest(@field:NotBlank val label: String, @field:NotBlank val category: String)
data class TeamStatusRenameRequest(@field:NotBlank val label: String)
data class TeamStatusOrderRequest(val keys: List<String>)

data class TeamStatusDto(val key: String, val label: String, val category: String, val position: Int) {
	companion object {
		fun of(status: TeamStatus) = TeamStatusDto(status.key, status.label, status.category.wire, status.position)
	}
}
```

- [ ] **Step 6: Run the suite and commit**

```bash
cd apps/api && ./gradlew test
git add apps/api/src
git commit -m "feat(statuses): add, rename, reorder, and remove naming where the tickets go"
```

---

## Task 5: A ticket's status is its team's, and a move rebases it

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/service/TicketStatusRebaseTest.kt`

**Interfaces:**
- Consumes: `TeamStatusRepository.forTeam` (Task 2).
- Produces: `TicketService` validating `status` against the catalogue and rebasing on a team change; `fun rebase(status: String, from: List<TeamStatus>, to: List<TeamStatus>): String` in `domain/TeamStatus.kt`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Transactional
class TicketStatusRebaseTest : PostgresTest() {

	// … same fixture shape as TeamStatusServiceTest …

	@Test
	fun `a move keeps the same key when the destination has it`() {
		val actor = owner()
		val from = teams.create(actor, "From", null, null)
		val to = teams.create(actor, "To", null, null)
		val ticket = tickets.create(actor = actor, teamId = from.id, title = "Moves", status = DefaultStatus.IN_PROGRESS)

		val moved = tickets.patch(actor, ticket.id, TicketPatch(teamId = to.id))

		assertEquals("in_progress", moved.status.wire)
	}

	@Test
	fun `a move lands on the destination's first status of the same category`() {
		val actor = owner()
		val from = teams.create(actor, "From", null, null)
		val to = teams.create(actor, "To", null, null)
		statuses.remove(actor, to.id, "in_progress", moveTo = "todo")
		val ticket = tickets.create(actor = actor, teamId = from.id, title = "Moves", status = DefaultStatus.IN_PROGRESS)

		val moved = tickets.patch(actor, ticket.id, TicketPatch(teamId = to.id))

		// `in_review` is what is left of `started` there. The category is the fact that
		// survives a move; the word is not.
		assertEquals("in_review", moved.status.wire)
	}

	@Test
	fun `a move with no status of that category lands on the destination's first`() {
		val actor = owner()
		val from = teams.create(actor, "From", null, null)
		val to = teams.create(actor, "To", null, null)
		statuses.remove(actor, to.id, "in_progress", moveTo = "todo")
		statuses.remove(actor, to.id, "in_review", moveTo = "todo")
		val ticket = tickets.create(actor = actor, teamId = from.id, title = "Moves", status = DefaultStatus.IN_PROGRESS)

		val moved = tickets.patch(actor, ticket.id, TicketPatch(teamId = to.id))

		assertEquals("backlog", moved.status.wire)
	}

	@Test
	fun `a rebase writes the activity line the number needs`() {
		val actor = owner()
		val from = teams.create(actor, "From", null, null)
		val to = teams.create(actor, "To", null, null)
		statuses.remove(actor, to.id, "in_progress", moveTo = "todo")
		val ticket = tickets.create(actor = actor, teamId = from.id, title = "Moves", status = DefaultStatus.IN_PROGRESS)

		tickets.patch(actor, ticket.id, TicketPatch(teamId = to.id))

		assertTrue(activity.forTicket(ticket.id).any { it.kind == ActivityKind.STATUS_CHANGED })
	}

	@Test
	fun `a status the team never defined is refused before the database has to`() {
		val actor = owner()
		val team = teams.create(actor, "Team", null, null)
		statuses.remove(actor, team.id, "in_review", moveTo = "todo")
		val ticket = tickets.create(actor = actor, teamId = team.id, title = "Refused", status = DefaultStatus.TODO)

		// The foreign key would raise a 500; a service that knows the vocabulary answers
		// with the vocabulary.
		val refusal = assertFailsWith<BadRequestException> {
			tickets.patch(actor, ticket.id, TicketPatch(status = "in_review"))
		}
		assertTrue(refusal.message!!.contains("backlog"))
	}
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.service.TicketStatusRebaseTest'`
Expected: FAIL — the move raises `ExposedSQLException` from `tickets_status_fk`, or the
patch is accepted.

- [ ] **Step 3: Write the rebase and the validation**

In `domain/TeamStatus.kt`:

```kotlin
/**
 * The status a ticket lands on when it changes team.
 *
 * The key if the destination has it, then the destination's first status of the same
 * category, then its first status. `KAN-9` made moving a ticket ordinary, and
 * `tickets_status_fk` makes an unrebased move impossible — so this is not a nicety, it
 * is the move.
 */
fun rebase(status: String, from: List<TeamStatus>, to: List<TeamStatus>): String {
	to.firstOrNull { it.key == status }?.let { return it.key }
	val category = from.firstOrNull { it.key == status }?.category
	if (category != null) to.firstOrNull { it.category == category }?.let { return it.key }
	return to.firstOrNull()?.key
		?: throw IllegalStateException("A team with no statuses cannot hold a ticket")
}
```

In `TicketService`, wherever a status is written and wherever `teamId` changes:

```kotlin
	private fun requireStatusOf(teamId: UUID?, status: String): String {
		// A draft answers to the default vocabulary: there is no team to ask, and
		// `tickets_status_fk` does not ask either.
		if (teamId == null) {
			DefaultStatus.from(status)
			return status
		}
		val catalogue = statuses.forTeam(teamId)
		return catalogue.firstOrNull { it.key == status }?.key
			?: throw BadRequestException(
				"This team has no status $status — it has ${catalogue.joinToString(", ") { it.key }}"
			)
	}
```

and on a team change, before the update:

```kotlin
		val destination = patch.teamId
		if (destination != null && destination != existing.teamId) {
			val landed = rebase(existing.status.wire, statuses.forTeam(existing.teamId ?: destination), statuses.forTeam(destination))
			if (landed != existing.status.wire) {
				// Recorded, because the ticket did change status: a burndown that saw the
				// number move with no line behind it is a burndown nobody trusts.
				activity.statusChanged(actor, existing.id, from = existing.status.wire, to = landed)
			}
		}
```

- [ ] **Step 4: Run it to see it pass, then the whole suite**

Run: `cd apps/api && ./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src
git commit -m "feat(statuses): a ticket holds its team's vocabulary, and a move rebases by category"
```

---

## Task 6: Grouping — a team's order, or the categories

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/repo/TicketQueryRepository.kt:345-357`
- Modify: `apps/api/src/main/kotlin/dev/kanso/service/TicketService.kt` (`grouped`)
- Test: `apps/api/src/test/kotlin/dev/kanso/service/GroupedStatusOrderTest.kt`

**Interfaces:**
- Consumes: `TeamStatusRepository.forTeams` (Task 2), `StatusOrder.rankOfCategory` (Task 3).
- Produces: `TicketQueryRepository.statusRank(ranks: Map<String, Int>): Expression<Int>`; grouped buckets keyed by status key for a single team and by category otherwise.

- [ ] **Step 1: Write the failing test**

```kotlin
	@Test
	fun `one team's grouped page is ordered by that team's positions`() {
		val actor = owner()
		val team = teams.create(actor, "Ordered", null, null)
		statuses.reorder(actor, team.id, listOf("todo", "backlog", "in_progress", "in_review", "done", "canceled"))
		tickets.create(actor = actor, teamId = team.id, title = "A", status = DefaultStatus.BACKLOG)
		tickets.create(actor = actor, teamId = team.id, title = "B", status = DefaultStatus.TODO)

		val groups = tickets.grouped(actor, Scope.team(team.id), ViewGroupBy.STATUS).groups

		assertEquals(listOf("todo", "backlog"), groups.take(2).map { it.key })
	}

	@Test
	fun `a scope spanning teams is grouped by category`() {
		val actor = owner()
		val one = teams.create(actor, "One", null, null)
		val other = teams.create(actor, "Other", null, null)
		statuses.rename(actor, other.id, "todo", "Qualifié")
		tickets.create(actor = actor, teamId = one.id, title = "A", status = DefaultStatus.TODO)
		tickets.create(actor = actor, teamId = other.id, title = "B", status = DefaultStatus.TODO)

		val groups = tickets.grouped(actor, Scope.all(), ViewGroupBy.STATUS).groups

		// One bucket, named by the fact rather than by either team's word for it.
		assertEquals(listOf("unstarted"), groups.filter { it.count > 0 }.map { it.key })
		assertEquals(2, groups.single { it.key == "unstarted" }.count)
	}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.service.GroupedStatusOrderTest'`
Expected: FAIL — the buckets come back in `StatusOrder.WORKFLOW`'s order and named by key
in both cases.

- [ ] **Step 3: Make the rank a parameter**

Replace the `private val statusRank` with:

```kotlin
	/**
	 * A bucket's rank, from a map the caller built — `KAN-28`.
	 *
	 * It was `StatusOrder.WORKFLOW` folded into a `CASE`, which was right while every
	 * team read the same six. A team's order is now `team_statuses.position`, and a scope
	 * spanning teams ranks by category instead, so the sequence is no longer this file's
	 * to know. What is still this file's: how to write the `CASE`, and that the `Else`
	 * must be a number — a missing `WHEN` yields `NULL`, which Postgres sorts *first*.
	 */
	private fun statusRank(ranks: Map<String, Int>): Expression<Int> = ranks.entries
		.fold(CaseWhen<Int>()) { case, (status, rank) ->
			case.When(Tickets.status eq status, intLiteral(rank))
		}
		.Else(intLiteral(StatusOrder.UNPLACED))
```

In `TicketService.grouped`, build the map before querying:

```kotlin
		val single = scope.singleTeamId()
		val ranks = if (single != null) {
			statuses.forTeam(single).withIndex().associate { (index, row) -> row.key to index + 1 }
		} else {
			// Every key of every team in scope, ranked by its category. Two teams' words
			// for one category land in one bucket, which is the whole point of the
			// category surviving a scope change.
			statuses.forTeams(scope.teamIds()).values.flatten()
				.associate { it.key to StatusOrder.rankOfCategory(it.category) }
		}
```

and name the buckets by key for a single team, by category otherwise.

- [ ] **Step 4: Run it to see it pass, then the suite**

Run: `cd apps/api && ./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src
git commit -m "feat(statuses): a grouped page ranks by a team's positions, or by category across teams"
```

---

## Task 7: The catalogue on the wire, the mirror, and the agent's refusal

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/api/Dtos.kt:107-119`
- Modify: the Notion mirror's push and inbound label resolution
- Modify: `apps/api/src/main/kotlin/dev/kanso/mcp/McpErrors.kt`
- Test: `apps/api/src/test/kotlin/dev/kanso/api/TeamStatusControllerTest.kt`, and the mirror's existing test file

**Interfaces:**
- Consumes: `TeamStatusDto` (Task 4), `TeamStatusRepository` (Task 2).
- Produces: `TeamResponse.statuses: List<TeamStatusDto>`, always present.

- [ ] **Step 1: Write the failing test**

```kotlin
	@Test
	fun `a team carries its statuses, so nothing needs a second call to learn the words`() {
		val actor = owner()
		val team = teams.create(actor, "Support", null, null)
		service.rename(actor, team.id, "done", "Livré")

		val response = mockMvc.get("/api/teams") { header("X-Kanso-User", actor.email) }
			.andExpect { status { isOk() } }
			.andReturn().response.contentAsString

		assertTrue(response.contains(""""label":"Livré""""))
	}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd apps/api && ./gradlew test --tests 'dev.kanso.api.TeamStatusControllerTest'`
Expected: FAIL — the payload has no `statuses`.

- [ ] **Step 3: Add the field, the mirror's labels, and the refusal**

`TeamResponse` gains `val statuses: List<TeamStatusDto>`, filled from
`TeamStatusRepository.forTeams` in whatever assembles the list — one query for the page,
not one per team. Document why it is required rather than optional: the same argument
`TicketResponse.customFields` carries.

The Notion mirror pushes the team's label instead of `DefaultStatus.label`, and resolves
an inbound label against that team's catalogue, falling back to the category's first
status — the same rebase rule, for the same reason.

`McpErrors` names the team's vocabulary in the refusal.

- [ ] **Step 4: Run the suite and commit**

```bash
cd apps/api && ./gradlew test
git add apps/api/src
git commit -m "feat(statuses): a team's words travel with the team, to Notion and to an agent"
```

---

## Task 8: The web reads its scope's vocabulary

**Files:**
- Create: `apps/web/src/lib/statuses.ts`
- Test: `apps/web/src/lib/statuses.test.ts`
- Modify: `apps/web/src/lib/api/core.ts`, `apps/web/src/lib/status.ts`, `apps/web/src/lib/status-order.ts`, `apps/web/src/components/pills.tsx`, `apps/web/src/lib/actions/core.ts`

**Interfaces:**
- Consumes: `Team.statuses` (Task 7).
- Produces: `type TeamStatus = { key: string; label: string; category: StatusCategory; position: number }`; `vocabularyOf(teams: Team[], scope: Scope): TeamStatus[]`; `labelOf(vocabulary, key): string`; `CATEGORY_ORDER`; `CATEGORY_LABELS`.

- [ ] **Step 1: Write the failing test**

```ts
describe("the vocabulary a screen reads", () => {
  it("is the team's own list when the scope names one team", () => {
    const vocabulary = vocabularyOf([kanso, support], { kind: "team", id: kanso.id });

    expect(vocabulary.map((status) => status.label)).toEqual([
      "Backlog", "Todo", "In progress", "In review", "Livré", "Canceled",
    ]);
  });

  it("is the five categories when the scope spans teams", () => {
    const vocabulary = vocabularyOf([kanso, support], { kind: "all" });

    // The header of a scope holding two vocabularies is the fact, not one team's word.
    expect(vocabulary.map((status) => status.key)).toEqual([
      "backlog", "unstarted", "started", "completed", "canceled",
    ]);
  });

  it("agrees with the server about the category order", () => {
    // `StatusOrder.CATEGORY_ORDER` in `domain/StatusOrder.kt` is the other copy, and the
    // pair of tests fails on whichever side drifts.
    expect(CATEGORY_ORDER).toEqual(["backlog", "unstarted", "started", "completed", "canceled"]);
  });

  it("names a key the vocabulary does not hold by the key itself", () => {
    // A saved view older than a removed status still addresses it; a blank chip would be
    // a filter that looks empty rather than stale.
    expect(labelOf([], "in_review")).toBe("in_review");
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd apps/web && npx vitest run src/lib/statuses.test.ts`
Expected: FAIL — `Cannot find module './statuses'`.

- [ ] **Step 3: Write the module, then thread it through**

`vocabularyOf` returns the single team's `statuses`, or the five categories rendered as
`TeamStatus` rows with `CATEGORY_LABELS` for labels. `STATUS_LABELS` and `STATUS_COLORS`
become keyed by category; a pill takes a `TeamStatus` and draws its category's hue.
Keys `1`–`9` map onto `vocabularyOf(...)[n - 1]`, and a scope with four statuses leaves
`5`–`9` inert.

- [ ] **Step 4: Run the web suite, typecheck, lint**

```bash
cd apps/web && npx vitest run && npx tsc --noEmit && npx eslint
```
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src
git commit -m "feat(statuses): a screen prints the words its scope actually uses"
```

---

## Task 9: The editor

**Files:**
- Create: `apps/web/src/components/settings/team-statuses.tsx`
- Test: `apps/web/src/components/settings/team-statuses.test.tsx`
- Modify: the team settings screen, `apps/web/src/lib/queries/organise.ts` (or wherever team mutations live)

**Interfaces:**
- Consumes: the endpoints from Task 4, `vocabularyOf` (Task 8).
- Produces: `useTeamStatuses(teamId)`, `useAddStatus()`, `useRenameStatus()`, `useReorderStatuses()`, `useRemoveStatus()`.

- [ ] **Step 1: Write the failing component test**

```tsx
it("refuses a second spelling before the server has to", async () => {
  render(<TeamStatuses teamId="t1" statuses={six} />);

  fireEvent.change(screen.getByLabelText("New status"), { target: { value: "in progress" } });
  fireEvent.click(screen.getByRole("button", { name: "Add" }));

  // The key is derived, so the screen can say what the server would say — without a
  // round trip, and in the same words.
  expect(screen.getByRole("alert").textContent).toContain("already has a status called");
});

it("asks where the tickets go before it removes one", async () => {
  render(<TeamStatuses teamId="t1" statuses={six} />);

  fireEvent.click(screen.getByRole("button", { name: "Remove In review" }));

  expect(screen.getByLabelText("Move its tickets to")).toBeTruthy();
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd apps/web && npx vitest run src/components/settings/team-statuses.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Build the editor**

A list of rows: label (inline rename), category (a select), drag to reorder, `Remove`
opening the destination select. `Add` takes a label and a category. The duplicate check
runs client-side through the same derivation as the server's `statusKeyOf`, so the
refusal needs no round trip; the server's refusal is still the authority.

- [ ] **Step 4: Run the suite, typecheck, lint, commit**

```bash
cd apps/web && npx vitest run && npx tsc --noEmit && npx eslint
git add apps/web/src
git commit -m "feat(statuses): the screen where a team writes its own words"
```

---

## Task 10: Scenario 29

**Files:**
- Create: `e2e/29-team-statuses.spec.ts`
- Modify: `e2e/README.md`

- [ ] **Step 1: Write the scenario**

One test, in order: an owner renames *Done* to *Livré* and the list's group header
follows; adds *Attente client* in `started` and it appears in the board's columns;
reorders so *Todo* leads and the grouped page's first bucket changes with it; removes
*In review* naming *In progress* and a ticket that held it is there, with an activity
line saying so; then the cross-team screen, where the headers are the five categories.
Assert the API alongside the interface for the move, the way scenario 12 does.

- [ ] **Step 2: Bring up an isolated stack and run it**

```bash
COMPOSE_PROJECT_NAME=kanso_kan28 POSTGRES_PORT=5444 API_PORT=8123 WEB_PORT=3033 \
  KANSO_WEB_ORIGIN=http://localhost:3033 KANSO_AUTH_MODE=dev \
  docker compose up -d --build --wait
KANSO_WEB_URL=http://localhost:3033 KANSO_API_URL=http://localhost:8123 \
  npx playwright test e2e/29-team-statuses.spec.ts
```

- [ ] **Step 3: Run the whole e2e suite, then tear the stack down**

```bash
KANSO_WEB_URL=http://localhost:3033 KANSO_API_URL=http://localhost:8123 npx playwright test
COMPOSE_PROJECT_NAME=kanso_kan28 POSTGRES_PORT=5444 API_PORT=8123 WEB_PORT=3033 docker compose down -v
```
Expected: 64 passed, 4 skipped, plus the new scenario.

- [ ] **Step 4: Commit**

```bash
git add e2e
git commit -m "test(e2e): scenario 29, a team writes its own words and the screens follow"
```

---

## Self-review notes

- **Spec coverage.** Catalogue → Task 2. Key derivation and both uniqueness guards →
  Tasks 1 and 4. Immutable key → Task 4 (`rename` writes `label` only). Composite FK and
  drafts → Task 2. Order as data, category order across teams → Tasks 3 and 6. The four
  moves and the last-status refusal → Task 4. Rebase on a team move → Task 5. Category
  versus literal audit → Task 3's rename is the mechanism. Notion and MCP → Task 7. Web
  vocabulary, keys, pills → Task 8. Editor → Task 9. Migration → Task 2. e2e → Task 10.
- **Deferred, per the spec:** per-status colour, statuses on a project or cycle,
  transition rules, an inherited instance default.
- **The one number to re-check:** `V41`. `V40` landed on `main` while the spec was
  written, and another session merging a migration mid-branch has happened here before.
