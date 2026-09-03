package dev.kanso.service

import dev.kanso.MockMvcTest
import dev.kanso.auth.AccountService
import dev.kanso.auth.DevAuthenticationFilter
import dev.kanso.db.TeamMembers
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one combination the two axes can express and the product refuses: **a read-only seat
 * that administers a team.**
 *
 * `TeamService.addMember` refused it from the day the title existed, on an argument about a
 * lie: a title promising administration to somebody who cannot write says something untrue
 * right up until the day the title starts meaning something, and then it is a permissions
 * bug with a year of rows behind it.
 *
 * **That day is this ticket.** `TicketAccess.teamsLedBy` reads the title as the right to
 * read another person's productivity figures, so a row bearing this pair is no longer an
 * untrue label — it is a disclosure. This file is what makes the three defences against it
 * assertable rather than three comments that agree with each other.
 *
 * The third defence is the one that would not exist without this test asking for it. The
 * database cannot hold the pair shut: `team_members.role` is `TEXT` with a vocabulary check
 * and a `CHECK` there has no way to reach `users.instance_role`. So the pair is reachable by
 * a hand-written `INSERT`, by a migration nobody reviewed, or by any future code path that
 * forgets — and the rule has to refuse it at the moment it is *read*, not only at the two
 * moments it could have been written.
 */
@Transactional
class ViewerTeamAdminTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var members: TeamRepository
	@Autowired lateinit var accounts: AccountService
	@Autowired lateinit var access: TicketAccess

	private fun person(name: String, role: InstanceRole) = users.createLocalUser(
		email = "viewer-admin-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	private val owner: User by lazy { person("Seat owner", InstanceRole.OWNER) }
	private val viewer: User by lazy { person("A read-only seat", InstanceRole.VIEWER) }
	private val subject: User by lazy { person("The measured one", InstanceRole.MEMBER) }

	private val team by lazy {
		teams.create(owner, "Design", "V${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	/** The first defence: the title is never granted to a seat that cannot write. */
	@Test
	fun `a read-only seat cannot be made a team's administrator`() {
		val refused = kotlin.runCatching {
			teams.addMember(owner, team.id, viewer.id, MemberRole.ADMIN)
		}.exceptionOrNull()

		assertTrue(
			refused is BadRequestException,
			"the title was granted to a seat that cannot write, or refused by the wrong type: $refused",
		)
		assertTrue(
			refused.message.orEmpty().contains(viewer.displayName),
			"the refusal has to name who it is about: ${refused?.message}",
		)
		// And nothing was written on the way past. A refusal that half-applied would leave
		// exactly the row the other two defences are there to catch.
		assertEquals(
			emptyList(),
			members.adminTeamIdsFor(viewer.id),
			"the refused title must not have been written anyway",
		)
	}

	/**
	 * The second defence: the seat is never taken away while a team still titles them.
	 *
	 * Refused rather than fixed up, which `AccountService` argues at length: quietly
	 * rewriting `team_members` on the way past would be that method changing rows the caller
	 * is not looking at.
	 */
	@Test
	fun `a titled administrator cannot be moved onto a read-only seat`() {
		val lead = person("Design lead", InstanceRole.MEMBER)
		teams.addMember(owner, team.id, lead.id, MemberRole.ADMIN)

		val refused = kotlin.runCatching {
			accounts.setInstanceRole(owner, lead.id, InstanceRole.VIEWER)
		}.exceptionOrNull()

		assertTrue(
			refused is ConflictException,
			"the seat was downgraded under a live title, or refused by the wrong type: $refused",
		)
		assertTrue(
			refused.message.orEmpty().contains("Design"),
			"the refusal names the team so that undoing it is one screen: ${refused?.message}",
		)
		assertEquals(
			InstanceRole.MEMBER,
			users.findById(lead.id)?.instanceRole,
			"the seat must be unchanged after the refusal",
		)
	}

	/**
	 * The third defence, and the only one that survives a hand-written row.
	 *
	 * The row is forged the way reality would forge it — the title written straight onto
	 * `team_members`, past both services — because that is precisely the state neither of
	 * the refusals above can see. If `teamsLedBy` honoured it, a viewer would be reading a
	 * colleague's productivity figures, which is the failure the whole pair-refusal exists
	 * to prevent.
	 */
	@Test
	fun `a forged title on a read-only seat leads nothing, and reads nobody`() {
		teams.addMember(owner, team.id, viewer.id, MemberRole.MEMBER)
		teams.addMember(owner, team.id, subject.id, MemberRole.MEMBER)
		// Straight onto the table: this is the `INSERT` a migration or a psql session makes,
		// and the database has no constraint that can refuse it.
		TeamMembers.update({ (TeamMembers.teamId eq team.id) and (TeamMembers.userId eq viewer.id) }) {
			it[role] = MemberRole.ADMIN.wire
		}

		// The forgery took: the row really does title them, so the assertions below are about
		// the rule refusing it rather than about the fixture failing to build it.
		assertEquals(
			listOf(team.id),
			members.adminTeamIdsFor(viewer.id),
			"the fixture failed to forge the row this test is about",
		)

		assertEquals(
			emptySet(),
			access.teamsLedBy(viewer, setOf(team.id)),
			"a title the product refuses to issue must not grant anything when it is read",
		)

		// And through the filter chain, which is where it would actually have leaked.
		assertEquals(
			403,
			mvc.get("/api/people/${subject.id}/progress?teamId=${team.id}") {
				header(DevAuthenticationFilter.HEADER, viewer.email)
			}.andReturn().response.status,
			"a forged team-admin row must not read a colleague's figures over HTTP either",
		)
	}

	/**
	 * The seat is the only thing refused, not the title.
	 *
	 * Without this, the guard above could be satisfied by a `teamsLedBy` that refused every
	 * team administrator — which would pass three tests and delete the feature.
	 */
	@Test
	fun `the same forged row on a writing seat is exactly what the title is for`() {
		val lead = person("Design lead", InstanceRole.MEMBER)
		teams.addMember(owner, team.id, lead.id, MemberRole.ADMIN)

		assertEquals(
			setOf(team.id),
			access.teamsLedBy(lead, setOf(team.id)),
			"a member titled administrator of this team leads it",
		)
	}
}
