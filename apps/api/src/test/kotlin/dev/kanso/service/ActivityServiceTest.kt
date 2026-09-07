package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The log is asserted through the service, never through an event: the suite is
 * `@Transactional` and rolls back, so `EventPublisher`'s `afterCommit` never fires —
 * which is the whole reason the log is a table rather than a listener on `pg_notify`.
 */
@Transactional
class ActivityServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var activity: ActivityService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var jdbc: JdbcClient

	private fun user(name: String) = users.createLocalUser(
		email = "act-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.ADMIN,
	)

	private val actor: User by lazy { user("Log Keeper") }

	private val team by lazy {
		teams.create(actor, "Logged", "L${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(title: String = "Echo") = tickets.create(
		actor = actor,
		teamId = team.id,
		title = title,
		description = null,
		status = DefaultStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket

	private fun log(entityId: UUID) = activity.forEntity(ActivityEntity.TICKET, entityId)

	@Test
	fun `a status change records one activity row carrying before and after`() {
		val ticket = ticket()
		tickets.patch(actor, ticket.id, TicketPatch(status = DefaultStatus.IN_PROGRESS))

		val rows = log(ticket.id)

		assertEquals(listOf(ActivityKind.STATUS_CHANGED, ActivityKind.CREATED), rows.map { it.kind })
		assertEquals("todo", rows.first().payload["from"])
		assertEquals("in_progress", rows.first().payload["to"])
		assertEquals(actor.id, rows.first().actor?.id, "a feed names the person, not a uuid")
	}

	@Test
	fun `a patch that changed nothing records nothing`() {
		val ticket = ticket()
		tickets.patch(actor, ticket.id, TicketPatch(status = DefaultStatus.TODO, title = "Echo"))

		assertEquals(
			listOf(ActivityKind.CREATED),
			log(ticket.id).map { it.kind },
			"one row per scalar that actually changed — a no-op patch changed none",
		)
	}

	@Test
	fun `one patch touching three scalars records three rows, not one`() {
		val ticket = ticket()
		tickets.patch(
			actor,
			ticket.id,
			TicketPatch(title = "Renamed", status = DefaultStatus.DONE, priority = TicketPriority.HIGH),
		)

		val kinds = log(ticket.id).map { it.kind }

		assertEquals(4, kinds.size, "three changes and the creation")
		assertTrue(ActivityKind.RENAMED in kinds)
		assertTrue(ActivityKind.STATUS_CHANGED in kinds)
		assertTrue(ActivityKind.PRIORITY_CHANGED in kinds)
	}

	@Test
	fun `a date moving is recorded per bound, so a feed can say which one moved`() {
		val ticket = ticket()
		val due = KansoInstant(LocalDate.of(2026, 9, 1).atStartOfDay().atOffset(ZoneOffset.UTC), false)

		tickets.patch(actor, ticket.id, TicketPatch(due = due))

		val scheduled = log(ticket.id).single { it.kind == ActivityKind.SCHEDULED }
		assertEquals("due", scheduled.payload["field"])
		assertEquals(due.at.toString(), scheduled.payload["to"])
	}

	@Test
	fun `assigning and unassigning are one row each, naming who`() {
		val ticket = ticket()
		val lea = user("Lea")

		tickets.setAssignees(actor, ticket.id, listOf(lea.id))
		tickets.setAssignees(actor, ticket.id, emptyList())

		val rows = log(ticket.id).filter { it.kind != ActivityKind.CREATED }
		assertEquals(listOf(ActivityKind.UNASSIGNED, ActivityKind.ASSIGNED), rows.map { it.kind })
		assertTrue(rows.all { it.payload["userId"] == lea.id.toString() })
	}

	@Test
	fun `archiving and unarchiving both land, with the direction in the payload`() {
		val ticket = ticket()

		tickets.patch(actor, ticket.id, TicketPatch(archived = true))
		tickets.patch(actor, ticket.id, TicketPatch(archived = false))

		val archived = log(ticket.id).filter { it.kind == ActivityKind.ARCHIVED }
		assertEquals(2, archived.size, "coming back out of the archive is a decision too")
		assertEquals(false, archived.first().payload["to"])
	}

	@Test
	fun `re-sizing a ticket records one row carrying both sizes`() {
		val ticket = ticket()
		tickets.patch(actor, ticket.id, TicketPatch(estimate = 3))
		tickets.patch(actor, ticket.id, TicketPatch(estimate = 13))

		val sized = log(ticket.id).filter { it.kind == ActivityKind.ESTIMATED }

		assertEquals(2, sized.size, "sizing and re-sizing are two decisions")
		assertEquals(3, sized.first().payload["from"], "the row a reader comes back for is 3 → 13")
		assertEquals(13, sized.first().payload["to"])
	}

	@Test
	fun `re-sizing to the size it already had records nothing`() {
		val ticket = ticket()
		tickets.patch(actor, ticket.id, TicketPatch(estimate = 5))
		tickets.patch(actor, ticket.id, TicketPatch(estimate = 5))

		assertEquals(
			1,
			log(ticket.id).count { it.kind == ActivityKind.ESTIMATED },
			"one row per scalar that actually changed — the second patch changed none",
		)
	}

	@Test
	fun `un-sizing is recorded too, with no size to have arrived at`() {
		val ticket = ticket()
		tickets.patch(actor, ticket.id, TicketPatch(estimate = 8))
		tickets.patch(actor, ticket.id, TicketPatch(unset = setOf("estimate")))

		val undone = log(ticket.id).first { it.kind == ActivityKind.ESTIMATED }
		assertEquals(8, undone.payload["from"])
		assertNull(undone.payload["to"], "an estimate withdrawn is a judgement, and reads as one")
	}

	/**
	 * The other half of the two-sided guard. `ActivityRepository.insert` takes an
	 * [ActivityKind], so nothing in Kotlin can write a kind that is not in the enum —
	 * which is exactly why the vocabulary has to be refused a second time, down here,
	 * where a raw INSERT from some future caller would otherwise walk straight past it.
	 */
	@Test
	fun `the kinds are closed, and the database is what refuses a fourteenth`() {
		val error = assertFailsWith<Exception> {
			jdbc.sql(
				"""
				INSERT INTO activity (id, entity_type, entity_id, actor_id, kind, payload, created_at)
				VALUES (:id, 'ticket', :entityId, NULL, 'resized', CAST('{}' AS jsonb), now())
				""".trimIndent()
			)
				.param("id", UUID.randomUUID())
				.param("entityId", UUID.randomUUID())
				.update()
		}
		assertTrue(
			error.toString().contains("activity_kind_chk"),
			"refused by the CHECK, not by a Kotlin enum a raw INSERT never consults: $error",
		)
	}

	/**
	 * **Every** kind in the enum, against the CHECK — not one of them, and not the newest.
	 *
	 * This exists because the per-kind test below it does not scale into a guarantee, and the
	 * failure it misses is silent by construction. `activity.kind` has been widened five times
	 * now (`V17`, `V21`, `V23`, `V30`, `V35`), and Postgres has no `ALTER CONSTRAINT` for a
	 * CHECK's expression, so each of those had to re-state the whole vocabulary. Re-state it
	 * from the wrong ancestor and a word silently disappears from the database while Kotlin
	 * carries on writing it — no compile error, and no failing test unless something asks the
	 * question in this shape.
	 *
	 * It is not hypothetical. `V35` was drafted on a branch that predated `V30` and restated
	 * `V23`'s fourteen words plus its own; `token_revoked` was gone, and every test in this
	 * suite still passed. `V30`'s own header records the same mistake happening once before.
	 *
	 * A raw INSERT rather than `activity.record`, for the reason the refusal test above gives:
	 * the enum is one side of the guard and this is the other, and going through Kotlin would
	 * only prove Kotlin agrees with itself. `entity_type` is `ticket` throughout because the
	 * two constraints are independent — this one is about the kind vocabulary alone.
	 */
	@Test
	fun `every kind in the enum is a kind the CHECK accepts`() {
		val refused = ActivityKind.entries.filter { kind ->
			runCatching {
				jdbc.sql(
					"""
					INSERT INTO activity (id, entity_type, entity_id, actor_id, kind, payload, created_at)
					VALUES (:id, 'ticket', :entityId, NULL, :kind, CAST('{}' AS jsonb), now())
					""".trimIndent()
				)
					.param("id", UUID.randomUUID())
					.param("entityId", UUID.randomUUID())
					.param("kind", kind.wire)
					.update()
			}.isFailure
		}

		assertEquals(
			emptyList(),
			refused.map { it.wire },
			"these kinds exist in Kotlin and are refused by `activity_kind_chk`," +
				" which means a migration re-stated the list from an ancestor that predated them",
		)
	}

	@Test
	fun `estimated is a kind the CHECK accepts, so the enum and the constraint agree`() {
		val ticket = ticket()
		activity.record(
			ActivityEntity.TICKET,
			ticket.id,
			actor.id,
			ActivityKind.ESTIMATED,
			mapOf("from" to 3, "to" to 13),
		)

		val row = log(ticket.id).first { it.kind == ActivityKind.ESTIMATED }
		assertEquals(13, row.payload["to"], "the wire value round-trips through the column")
	}

	@Test
	fun `the feed is newest first, and stops at the limit it was given`() {
		val ticket = ticket()
		for (title in listOf("One", "Two", "Three")) {
			tickets.patch(actor, ticket.id, TicketPatch(title = title))
		}

		val rows = activity.forEntity(ActivityEntity.TICKET, ticket.id, limit = 2)

		assertEquals(2, rows.size)
		assertTrue(
			rows.first().createdAt >= rows.last().createdAt,
			"every reader of this list draws a feed, and a feed starts at the top",
		)
		assertEquals("Three", rows.first().payload["to"])
	}

	/**
	 * `KAN-84`. The feed's copy was written and tested against `payload.ref` and a grep found
	 * no writer for it anywhere in the API, so every sentence said "a ticket". These four
	 * assert the resolution, not the sentence — the sentence is `project-copy.test.ts`.
	 */
	@Test
	fun `every row of a ticket's feed is named, though nothing writes a ref`() {
		val ticket = ticket()
		tickets.patch(actor, ticket.id, TicketPatch(status = DefaultStatus.DONE, title = "Named"))

		val rows = log(ticket.id)

		assertEquals(3, rows.size, "the status, the title, and the creation")
		assertEquals(
			listOf("${team.key}-${ticket.number}"),
			rows.map { it.payload["ref"] }.distinct(),
			"resolved for the whole page in one pass, so no row is left saying `a ticket`",
		)
	}

	/**
	 * The half that a write-time `ref` could not have delivered: these rows predate the
	 * resolution by nothing at all in a test, but by every row already in the table in
	 * production — and `KAN-81`'s precedent is not to rebuild history from a guess.
	 */
	@Test
	fun `a row written with an empty payload is named all the same`() {
		val ticket = ticket()
		activity.record(ActivityEntity.TICKET, ticket.id, null, ActivityKind.MIRROR_PUSHED)

		val row = log(ticket.id).first { it.kind == ActivityKind.MIRROR_PUSHED }

		assertEquals("${team.key}-${ticket.number}", row.payload["ref"])
	}

	/** A renamed team renames its history with it, which is the whole reason this is a read. */
	@Test
	fun `re-keying the team re-names the rows already logged`() {
		val ticket = ticket()
		tickets.patch(actor, ticket.id, TicketPatch(status = DefaultStatus.IN_PROGRESS))
		val newKey = "R${UUID.randomUUID().toString().take(4).uppercase()}"

		teams.update(actor, team.id, "Logged", newKey, null)

		assertEquals(
			listOf("$newKey-${ticket.number}"),
			log(ticket.id).map { it.payload["ref"] }.distinct(),
			"a stored ref would have left every line above the rename naming a key nobody has",
		)
	}

	/**
	 * A draft has no identifier to resolve, and the absent key is what the feed's own
	 * fallback reads: "created a ticket", not "created null".
	 */
	@Test
	fun `a draft's rows carry no ref at all, rather than a placeholder`() {
		val draft = tickets.create(
			actor = actor,
			teamId = null,
			title = "Unclaimed",
			description = null,
			status = DefaultStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket

		val row = log(draft.id).single()

		assertNull(row.payload["ref"])
		assertTrue("ref" !in row.payload, "absent, so the mapper omits it and no client sees a null")
	}

	/**
	 * `entity_type` is what decides, never the shape of the id.
	 *
	 * The row here is a *project's*, keyed on an id that also happens to name a ticket —
	 * which is the only way to write this assertion as something that can fail, since a
	 * project's feed otherwise holds no id `tickets` would recognise. Drop the entity filter
	 * in `refsFor` and this row starts claiming a ticket's name; a project feed also asks
	 * `tickets` and `teams` nothing at all today, and that is the half no row can show.
	 */
	@Test
	fun `a feed that is not a ticket's carries no ref`() {
		val ticket = ticket()
		activity.record(ActivityEntity.PROJECT, ticket.id, actor.id, ActivityKind.MIRROR_PUSHED)

		val row = activity.forEntity(ActivityEntity.PROJECT, ticket.id).single()

		assertTrue("ref" !in row.payload, "a project's row is not about a ticket, whatever its id names")
	}

	@Test
	fun `another ticket's log is not this ticket's log`() {
		val mine = ticket("Mine")
		val theirs = ticket("Theirs")
		tickets.patch(actor, theirs.id, TicketPatch(status = DefaultStatus.DONE))

		assertEquals(listOf(ActivityKind.CREATED), log(mine.id).map { it.kind })
	}
}
