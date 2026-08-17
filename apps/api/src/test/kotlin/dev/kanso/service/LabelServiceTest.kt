package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Transactional
class LabelServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var labels: LabelService
	@Autowired lateinit var activity: ActivityService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "label-${UUID.randomUUID()}@kanso.test",
		displayName = "Label ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val actor: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "L${UUID.randomUUID().toString().take(4).uppercase()}"

	private val core by lazy { teams.create(actor, "Core", key(), null) }
	private val platform by lazy { teams.create(actor, "Platform", key(), null) }

	private val coreTicket: Ticket by lazy {
		tickets.create(
			actor = actor,
			teamId = core.id,
			title = "Labelled work",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket
	}

	@Test
	fun `two teams may both own the name sync`() {
		labels.create(actor, core.id, "sync", "indigo")
		val other = labels.create(actor, platform.id, "sync", "amber")

		assertEquals("sync", other.name)
		assertEquals(listOf("sync"), labels.list(core.id).map { it.name })
		assertEquals(listOf("amber"), labels.list(platform.id).map { it.colour.wire })
	}

	@Test
	fun `a label from another team cannot be attached`() {
		val foreign = labels.create(actor, platform.id, "sync", "amber")

		assertFailsWith<ConflictException> { labels.attach(actor, coreTicket.id, foreign.id) }
		assertEquals(emptyList(), labels.forTicket(coreTicket.id))
	}

	@Test
	fun `the same name twice in one team is a conflict, not a 500 from the unique index`() {
		labels.create(actor, core.id, "sync", "indigo")

		assertFailsWith<ConflictException> { labels.create(actor, core.id, "sync", "rose") }
	}

	@Test
	fun `a colour outside the accent vocabulary is a bad request`() {
		assertFailsWith<BadRequestException> { labels.create(actor, core.id, "sync", "chartreuse") }
		assertFailsWith<BadRequestException> { labels.create(actor, core.id, "  ", "indigo") }
	}

	@Test
	fun `writing a label needs the team, reading it does not`() {
		teamRepo.addMember(core.id, actor.id, MemberRole.ADMIN)
		val label = labels.create(actor, core.id, "sync", "indigo")
		val stranger = user(InstanceRole.MEMBER)

		assertFailsWith<AccessDeniedException> { labels.create(stranger, core.id, "design system", "blue") }
		assertFailsWith<AccessDeniedException> { labels.attach(stranger, coreTicket.id, label.id) }
		assertEquals(listOf("sync"), labels.list(core.id).map { it.name }, "reads are open")
	}

	@Test
	fun `attaching twice leaves one row, and detaching removes it`() {
		val label = labels.create(actor, core.id, "sync", "indigo")

		labels.attach(actor, coreTicket.id, label.id)
		labels.attach(actor, coreTicket.id, label.id)
		assertEquals(listOf(label.id), labels.forTicket(coreTicket.id).map { it.id })

		labels.detach(actor, coreTicket.id, label.id)
		assertEquals(emptyList(), labels.forTicket(coreTicket.id))
	}

	@Test
	fun `setting the whole list replaces it, and refuses a foreign label without writing anything`() {
		val sync = labels.create(actor, core.id, "sync", "indigo")
		val design = labels.create(actor, core.id, "design system", "blue")
		val foreign = labels.create(actor, platform.id, "sync", "amber")
		labels.attach(actor, coreTicket.id, sync.id)

		assertEquals(
			listOf("design system", "sync"),
			labels.set(actor, coreTicket.id, listOf(design.id, sync.id)).map { it.name },
			"the write answers with the ticket's labels, by name, so a pill row has an order",
		)

		assertFailsWith<ConflictException> { labels.set(actor, coreTicket.id, listOf(design.id, foreign.id)) }
		assertEquals(
			2,
			labels.forTicket(coreTicket.id).size,
			"a refused write leaves the list it was replacing alone",
		)
	}

	@Test
	fun `attaching and detaching land in the ticket's log`() {
		val label = labels.create(actor, core.id, "sync", "indigo")

		labels.attach(actor, coreTicket.id, label.id)
		labels.detach(actor, coreTicket.id, label.id)

		val rows = activity.forEntity(ActivityEntity.TICKET, coreTicket.id)
			.filter { it.kind == ActivityKind.LABELLED }
		assertEquals(2, rows.size)
		assertEquals(false, rows.first().payload["attached"])
		assertEquals("sync", rows.first().payload["name"])
	}
}
