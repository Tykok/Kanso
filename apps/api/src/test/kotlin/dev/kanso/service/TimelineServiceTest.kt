package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.MemberRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.TeamRepository
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
	@Autowired lateinit var teamRepo: TeamRepository

	private val admin: User by lazy {
		users.createLocalUser(
			email = "tl-${UUID.randomUUID()}@kanso.test",
			displayName = "Timeline admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	/** An admin is editable everywhere by construction, so `editable` needs a plain member. */
	private val member: User by lazy {
		users.createLocalUser(
			email = "tl-member-${UUID.randomUUID()}@kanso.test",
			displayName = "Timeline member",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.MEMBER,
		)
	}

	private val team by lazy {
		teams.create(admin, "TL", "T${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun day(d: Int) = KansoInstant(
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC),
		false,
	)

	private fun ticket(title: String, projectId: UUID?, start: Int?, due: Int?, status: DefaultStatus = DefaultStatus.TODO) =
		tickets.create(
			actor = admin,
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
			actor = admin,
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

		val row = timeline.load(admin, teamId = team.id, projectId = null).projects.single { it.id == project.id }

		assertEquals(day(3).at, row.start!!.at)
		assertEquals(day(20).at, row.end!!.at)
		assertTrue(row.startDerived && row.endDerived, "nothing was posed, so both bounds are consequences")
	}

	@Test
	fun `an explicit bound wins over the derivation, one bound at a time`() {
		val project = projects.create(
			actor = admin,
			name = "Half posed",
			status = ProjectStatus.IN_PROGRESS,
			start = day(1),
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		ticket("Only", project.id, 5, 9)

		val row = timeline.load(admin, teamId = team.id, projectId = null).projects.single { it.id == project.id }

		assertEquals(day(1).at, row.start!!.at)
		assertEquals(false, row.startDerived)
		assertEquals(day(9).at, row.end!!.at)
		assertEquals(true, row.endDerived)
	}

	@Test
	fun `a project whose tickets are only done falls back to when they were done`() {
		val project = projects.create(
			actor = admin,
			name = "Retrospective",
			status = ProjectStatus.COMPLETED,
			start = null,
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		val id = ticket("Undated but finished", project.id, null, null)
		tickets.patch(admin, id, TicketPatch(status = DefaultStatus.DONE))

		val row = timeline.load(admin, teamId = team.id, projectId = null).projects.single { it.id == project.id }

		assertTrue(row.start != null, "a completion date is a worse answer than a plan, and a better one than a blank row")
	}

	@Test
	fun `undated tickets are listed separately rather than dropped`() {
		val id = ticket("No dates", null, null, null)

		val view = timeline.load(admin, teamId = team.id, projectId = null)

		assertTrue(view.unscheduled.any { it.id == id })
		assertTrue(view.tickets.none { it.id == id })
	}

	@Test
	fun `criticality is computed over the whole component, not the visible scope`() {
		val other = teams.create(admin, "Other", "O${UUID.randomUUID().toString().take(4).uppercase()}", null)
		val here = ticket("Here", null, 1, 10)
		val elsewhere = tickets.create(
			actor = admin,
			teamId = other.id,
			title = "Elsewhere",
			description = null,
			status = DefaultStatus.TODO,
			priority = TicketPriority.NONE,
			start = day(10),
			due = day(30),
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
		deps.insert(here, elsewhere)

		val view = timeline.load(admin, teamId = team.id, projectId = null)
		val row = view.tickets.single { it.id == here }

		assertEquals(true, row.critical, "the chain ends outside the scope, and that is what anchors it")
		assertEquals(
			false,
			view.dependencies.single().outOfScope,
			"the closure is returned as context now rather than discarded, so there is no stub left to draw",
		)
	}

	@Test
	fun `a ticket of another team in a shared project is drawn as context`() {
		val other = teams.create(admin, "Other", "O${UUID.randomUUID().toString().take(4).uppercase()}", null)
		// Team-less on purpose: `TicketService` refuses a ticket whose project belongs to
		// another team, so the transverse project is the only shape a shared one can have.
		val project = projects.create(
			actor = admin,
			name = "Shared",
			status = ProjectStatus.IN_PROGRESS,
			start = null,
			end = null,
			leadUserId = null,
			teamId = null,
			docIds = emptyList(),
		).project
		val mine = ticket("Mine", project.id, 1, 5)
		val theirs = tickets.create(
			actor = admin,
			teamId = other.id,
			title = "Theirs",
			description = null,
			status = DefaultStatus.TODO,
			priority = TicketPriority.NONE,
			start = day(2),
			due = day(6),
			projectId = project.id,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
		teamRepo.addMember(team.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(other.id, admin.id, MemberRole.MEMBER)

		val view = timeline.load(member, teamId = team.id, projectId = null)

		val ours = view.tickets.single { it.id == mine }
		val visitor = view.tickets.single { it.id == theirs }
		assertEquals(false, ours.context)
		assertEquals(true, ours.editable)
		assertEquals(true, visitor.context, "a shared project is who else is working here")
		assertEquals(false, visitor.editable)
		assertEquals(other.key, visitor.teamKey)
		assertTrue(
			view.projects.any { it.id == project.id },
			"a transverse project has no team, so the team filter cannot find it — and it is the only legal shape of a shared one",
		)
	}

	@Test
	fun `an edge to an undated ticket of another team is still a stub`() {
		val other = teams.create(admin, "Undated", "U${UUID.randomUUID().toString().take(4).uppercase()}", null)
		val here = ticket("Here", null, 1, 10)
		val elsewhere = tickets.create(
			actor = admin,
			teamId = other.id,
			title = "No dates over there",
			description = null,
			status = DefaultStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
		deps.insert(here, elsewhere)

		val view = timeline.load(admin, teamId = team.id, projectId = null)

		// It has no bar to draw and it is not the reader's to schedule, so it lands in
		// neither list — which is exactly what `outOfScope` has to keep announcing.
		assertTrue(view.tickets.none { it.id == elsewhere })
		assertTrue(view.unscheduled.none { it.id == elsewhere })
		assertEquals(
			true,
			view.dependencies.single().outOfScope,
			"the far end has no row anywhere in the response, so the view still draws a stub",
		)
	}

	@Test
	fun `a ticket in the dependency closure is drawn rather than left as a stub`() {
		val other = teams.create(admin, "Chained", "C${UUID.randomUUID().toString().take(4).uppercase()}", null)
		val here = ticket("Here", null, 1, 10)
		val elsewhere = tickets.create(
			actor = admin,
			teamId = other.id,
			title = "Elsewhere",
			description = null,
			status = DefaultStatus.TODO,
			priority = TicketPriority.NONE,
			start = day(10),
			due = day(30),
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
		deps.insert(here, elsewhere)

		val view = timeline.load(admin, teamId = team.id, projectId = null)

		assertTrue(view.tickets.any { it.id == elsewhere && it.context })
		assertEquals(
			false,
			view.dependencies.single().outOfScope,
			"both ends are in the response now, so there is nothing to stub",
		)
	}

	@Test
	fun `a successor that starts before its predecessor ends is an overlap, not a violation`() {
		val first = ticket("First", null, 1, 10)
		val second = ticket("Second", null, 20, 25)
		deps.insert(first, second)
		// Dragged backwards, under its own predecessor. `Cascade` never examines this
		// edge: its descent only considers a node one of whose predecessors moved.
		tickets.patch(admin, second, TicketPatch(start = day(5), due = day(9)))

		val edge = timeline.load(admin, teamId = team.id, projectId = null)
			.dependencies.single { it.predecessorId == first && it.successorId == second }

		assertEquals(true, edge.overlap, "the constraint is broken right now")
		assertEquals(false, edge.violated, "but the successor is not done, so the cascade could still repair it")
	}

	@Test
	fun `a done successor is violated and not merely overlapping`() {
		val first = ticket("Predecessor", null, 10, 20)
		val second = ticket("Finished early", null, 1, 5, status = DefaultStatus.DONE)
		deps.insert(first, second)

		val edge = timeline.load(admin, teamId = team.id, projectId = null)
			.dependencies.single { it.predecessorId == first && it.successorId == second }

		assertEquals(true, edge.violated)
		assertEquals(false, edge.overlap, "the two are exclusive: one names what can be repaired, the other what cannot")
	}
}
