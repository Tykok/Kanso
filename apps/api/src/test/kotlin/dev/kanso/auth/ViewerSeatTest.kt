package dev.kanso.auth

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.docs.DocService
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.CommentService
import dev.kanso.service.ConflictException
import dev.kanso.service.CreateComment
import dev.kanso.service.LabelService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seat below HTTP: what a viewer is refused by the rules themselves.
 *
 * `ReadOnlySeatLeakTest` drives the doorway and proves no *endpoint* answers. This file
 * asks the other question, which the doorway cannot: whether a caller that never touches
 * HTTP — an MCP tool, the Notion importer, a scheduled sweep, a service called by another
 * service — is refused too. If the seat lived only in the interceptor, everything here
 * would pass a write straight through, and the leak test would still be green.
 *
 * The refusals below are all the *same* refusal, produced by `TicketAccess`, which is why
 * comments, labels, cycles, views, docs and the rest needed no clause of their own.
 */
@Transactional
class ViewerSeatTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var comments: CommentService
	@Autowired lateinit var labels: LabelService
	@Autowired lateinit var docs: DocService
	@Autowired lateinit var access: TicketAccess
	@Autowired lateinit var accounts: AccountService
	@Autowired lateinit var invitations: InvitationService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var jdbc: JdbcClient

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "seat-${UUID.randomUUID()}@kanso.test",
		displayName = "Seat ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "S${UUID.randomUUID().toString().take(4).uppercase()}"

	/**
	 * A team the viewer belongs to.
	 *
	 * Enrolled deliberately. `TicketAccess`'s open-chain clause leaves a team nobody has
	 * joined editable by everyone, so a viewer refused in an *empty* team would prove
	 * nothing about the seat — the interesting refusal is the one that survives membership.
	 */
	private fun teamWith(vararg people: User): Team {
		val team = teams.create(admin, "Seat ${UUID.randomUUID()}", key(), null)
		people.forEach { teamRepo.addMember(team.id, it.id, MemberRole.MEMBER) }
		return team
	}

	private fun ticketIn(team: Team) = tickets.create(
		actor = admin,
		teamId = team.id,
		title = "Something to read",
		description = "and not to change",
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket

	/**
	 * The point of the whole ticket, said once: membership does not buy a viewer a write.
	 *
	 * Every refusal is checked against the *same sentence*. That is the assertion that would
	 * fail if any of these services had grown a viewer clause of its own — a second rule
	 * reads differently long before it starts disagreeing, and reading differently is the
	 * only symptom available before the disagreement ships.
	 */
	@Test
	fun `a viewer inside the team is still refused every write, by one rule`() {
		val viewer = user(InstanceRole.VIEWER)
		val team = teamWith(viewer)
		val ticket = ticketIn(team)

		val refusals = mapOf(
			"file a ticket" to { tickets.create(viewer, team.id, "New", null, TicketStatus.TODO, TicketPriority.NONE, null, null, null, emptyList(), emptyList()) },
			"rename a ticket" to { tickets.patch(viewer, ticket.id, TicketPatch(title = "Renamed")) },
			"move a ticket to done" to { tickets.patch(viewer, ticket.id, TicketPatch(status = TicketStatus.DONE)) },
			"delete a ticket" to { tickets.delete(viewer, ticket.id) },
			"comment" to { comments.create(viewer, CreateComment(ticketId = ticket.id, body = "A thought")) },
			"create a label" to { labels.create(viewer, team.id, "urgent", "rose") },
			"create a doc folder" to { docs.createFolder(viewer, team.id, null, "Notes") },
			"create a doc page" to { docs.createPage(viewer, team.id, null, "Notes", null) },
		)

		for ((what, attempt) in refusals) {
			val refused = assertFailsWith<AccessDeniedException>("a viewer was allowed to $what") { attempt() }
			assertEquals(
				TicketAccess.READS_NOT_WRITES,
				refused.message,
				"`$what` refused a viewer with a sentence of its own, which means a rule of its own",
			)
		}
	}

	/**
	 * A member turned down to a viewer is turned down by the *seat*, not by the team — and
	 * the message says so. "Design is not one of your teams" is true and useless when the
	 * person is in Design; it sends them to ask for something that would change nothing.
	 */
	@Test
	fun `the refusal names the seat and not the team`() {
		val viewer = user(InstanceRole.VIEWER)
		val stranger = user(InstanceRole.MEMBER)
		val theirs = teamWith(stranger)

		val outside = assertFailsWith<AccessDeniedException> { access.requireTeam(viewer, theirs.id) }
		val inside = assertFailsWith<AccessDeniedException> { access.requireTeam(viewer, teamWith(viewer).id) }

		assertEquals(TicketAccess.READS_NOT_WRITES, outside.message)
		assertEquals(
			outside.message,
			inside.message,
			"being in the team changes nothing for a viewer, so it must not change the sentence either",
		)
	}

	/** Reads are the product. A refusal that took them away would be the feature inverted. */
	@Test
	fun `a viewer reads what the team reads, including a draft they wrote before the demotion`() {
		val demoted = user(InstanceRole.MEMBER)
		val team = teamWith(demoted)
		val theirs = ticketIn(team)
		val draft = tickets.create(
			demoted, null, "A draft from before", null,
			TicketStatus.TODO, TicketPriority.NONE, null, null, null, emptyList(), emptyList(),
		).ticket

		users.setInstanceRole(demoted.id, InstanceRole.VIEWER)
		val now = requireNotNull(users.findById(demoted.id))
		assertEquals(InstanceRole.VIEWER, now.instanceRole, "the demotion is the premise of the rest of this test")

		assertTrue(access.mayRead(now, theirs), "the team's work stays readable; that is the entire seat")
		assertTrue(access.mayRead(now, draft), "and so does the draft they wrote when they still could")
		assertFalse(access.mayEdit(now, draft), "which they may no longer change")
		assertEquals(
			emptySet(),
			access.editableTeams(now, setOf(team.id)),
			"the timeline draws every bar fixed rather than offering a drag that would 403",
		)
	}

	/**
	 * Who may hand out the seat, and who may not.
	 *
	 * The admin case is not padding: without it this would pass on an implementation where
	 * nobody at all can create a viewer, which is a different bug.
	 */
	@Test
	fun `only a configurator may set the seat, and never on themselves`() {
		val viewer = user(InstanceRole.VIEWER)
		val member = user(InstanceRole.MEMBER)

		assertFailsWith<AccessDeniedException>("a reader must not be able to reclassify anybody") {
			accounts.setInstanceRole(viewer, member.id, InstanceRole.VIEWER)
		}
		assertFailsWith<AccessDeniedException>("nor may a plain member") {
			accounts.setInstanceRole(member, viewer.id, InstanceRole.MEMBER)
		}

		val demoted = accounts.setInstanceRole(admin, member.id, InstanceRole.VIEWER)
		assertEquals(InstanceRole.VIEWER, demoted.instanceRole, "an admin gives out the seat; somebody has to")

		// The step-down guard used to name `member` as the destination. `viewer` is the worse
		// version of the same trap — the screen that would undo it is one an admin-turned-viewer
		// can no longer reach — so it has to be caught by the same clause.
		assertFailsWith<ConflictException>("an admin must not be able to lock themselves out") {
			accounts.setInstanceRole(admin, admin.id, InstanceRole.VIEWER)
		}
	}

	/**
	 * The combination the two axes can express and the product should not have.
	 *
	 * `MemberRole.ADMIN` gates nothing today, which is exactly why this is worth refusing
	 * now: a title that promises administration to somebody who cannot write costs nothing
	 * until the day it starts meaning something, and by then there are rows.
	 */
	@Test
	fun `a viewer is never a team admin, from either direction`() {
		val viewer = user(InstanceRole.VIEWER)
		val team = teams.create(admin, "Titled ${UUID.randomUUID()}", key(), null)

		val forwards = assertFailsWith<BadRequestException>("a reader was made an administrator") {
			teams.addMember(admin, team.id, viewer.id, MemberRole.ADMIN)
		}
		assertTrue(forwards.message!!.contains("read-only seat"), "the refusal says why: ${forwards.message}")

		// Plain membership is fine, and has to be: a viewer with no team sees an empty product.
		teams.addMember(admin, team.id, viewer.id, MemberRole.MEMBER)
		assertEquals(
			listOf(MemberRole.MEMBER),
			teamRepo.members(team.id).filter { it.user.id == viewer.id }.map { it.role },
			"a reader belongs to teams like anybody else",
		)

		val writer = user(InstanceRole.MEMBER)
		teams.addMember(admin, team.id, writer.id, MemberRole.ADMIN)
		val backwards = assertFailsWith<ConflictException>("a team administrator was put on a read-only seat") {
			accounts.setInstanceRole(admin, writer.id, InstanceRole.VIEWER)
		}
		assertTrue(
			backwards.message!!.contains("Titled"),
			"the teams in the way are named, so the fix is one screen and not a search: ${backwards.message}",
		)
	}

	/** The wire value, through the enum and back out of Postgres unchanged. */
	@Test
	fun `the role survives a round trip`() {
		val viewer = user(InstanceRole.VIEWER)

		assertEquals("viewer", InstanceRole.VIEWER.wire)
		assertEquals(InstanceRole.VIEWER, InstanceRole.from("viewer"))
		assertEquals(InstanceRole.VIEWER, requireNotNull(users.findById(viewer.id)).instanceRole)
		assertFalse(InstanceRole.VIEWER.mayWrite, "the one line the whole seat is built on")
		assertFalse(InstanceRole.VIEWER.canConfigureInstance, "and it is not a configurator either")
		assertTrue(
			InstanceRole.entries.filterNot { it.mayWrite } == listOf(InstanceRole.VIEWER),
			"exactly one role reads without writing; a second would need a reason",
		)

		val (token, _) = invitations.create(admin.id, "reader-${UUID.randomUUID()}@kanso.test", InstanceRole.VIEWER)
		assertEquals(
			InstanceRole.VIEWER,
			invitations.pending().first { it.createdAt <= OffsetDateTime.now() && it.role == InstanceRole.VIEWER }.role,
			"inviting a reader is the main way a seat comes to exist, so the link has to carry the role",
		)
		assertTrue(token.isNotBlank(), "and hand back something to send")
	}

	/**
	 * The database refuses what Kotlin refuses.
	 *
	 * `V25` widened `users_instance_role_chk` rather than dropping it, and this is the
	 * assertion that says so: a role nobody declared is rejected by Postgres, not merely by
	 * the enum parser, so a bad row cannot arrive through psql, a migration or a restore.
	 */
	@Test
	fun `the check constraint refuses a role nobody declared`() {
		assertFailsWith<DataIntegrityViolationException>("`auditor` is not a role, and the database has to say so") {
			jdbc.sql("INSERT INTO users (id, email, display_name, instance_role) VALUES (:id, :email, 'Nope', 'auditor')")
				.param("id", UUID.randomUUID())
				.param("email", "auditor-${UUID.randomUUID()}@kanso.test")
				.update()
		}
	}

	/** And accepts the one it just learned, on both tables that carry it. */
	@Test
	fun `the check constraints accept the new role`() {
		val id = UUID.randomUUID()
		jdbc.sql("INSERT INTO users (id, email, display_name, instance_role) VALUES (:id, :email, 'Reader', 'viewer')")
			.param("id", id)
			.param("email", "reader-$id@kanso.test")
			.update()
		assertEquals(InstanceRole.VIEWER, requireNotNull(users.findById(id)).instanceRole)

		// `invitations_role_chk` is the narrower vocabulary — `V5` refuses `owner` there — and
		// widening it is what makes "invite somebody to read" a link rather than a two-step.
		jdbc.sql(
			"INSERT INTO invitations (id, token_hash, instance_role, created_by, expires_at) " +
				"VALUES (:id, :hash, 'viewer', :by, now() + interval '7 days')",
		)
			.param("id", UUID.randomUUID())
			.param("hash", "not-a-real-digest-${UUID.randomUUID()}")
			.param("by", admin.id)
			.update()
	}
}
