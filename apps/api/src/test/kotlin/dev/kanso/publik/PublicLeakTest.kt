package dev.kanso.publik

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.LabelService
import dev.kanso.service.NotFoundException
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two tests this slice exists to pass.
 *
 * Every other route in Kanso answers an authenticated reader, so getting a read model
 * wrong there shows the wrong data to somebody who was allowed to see the right data.
 * These three answer strangers, which makes them the only place in the product where a
 * bad projection is a disclosure. So: a private ticket is absent from all of them, and
 * what a public ticket does answer carries no email address.
 *
 * The second assertion is made against the serialised JSON rather than against the DTO
 * fields, because the leak that matters is the one that reaches the wire. A field added
 * to a nested response three refactors from now is caught here without anybody having
 * to remember this file exists.
 */
@Transactional
class PublicLeakTest : PostgresTest() {

	@Autowired lateinit var roadmap: PublicRoadmapService
	@Autowired lateinit var votes: VoteService
	@Autowired lateinit var publication: PublicationService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var labels: LabelService
	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var json: ObjectMapper

	private val owner: User by lazy { person("Leak owner", InstanceRole.OWNER) }

	private fun person(name: String, role: InstanceRole) = users.createLocalUser(
		email = "leak-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	private fun team() = teams.insert(
		name = "Public surfaces ${UUID.randomUUID()}",
		key = "L${UUID.randomUUID().toString().take(4).uppercase()}",
		parentTeamId = null,
	)

	private fun ticket(teamId: UUID, title: String, status: String = "todo") =
		tickets.create(
			actor = owner,
			teamId = teamId,
			title = title,
			description = "The description of $title",
			status = status,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

	@Test
	fun `a private ticket is absent from every public endpoint`() {
		val team = team()
		val hidden = ticket(team.id, "The acquisition, and what we told legal")
		val shown = ticket(team.id, "Two-level sub-tickets")
		publication.publish(owner, shown.ticket.id, public = true)

		// Labels are the newest way in: screen 28 narrows its list to `good first step` and
		// prints the badges beside the title, so both the query that narrows and the one
		// that badges are now reads a private ticket could ride out on. Marked *before* the
		// assertions below so the private ticket is wearing the very label the page looks
		// for, which is the only version of this that could catch the mistake.
		val first = labels.create(owner, team.id, "good first step", "green")
		val secret = labels.create(owner, team.id, "project-cormorant", "rose")
		labels.attach(owner, hidden.ticket.id, first.id)
		labels.attach(owner, hidden.ticket.id, secret.id)
		labels.attach(owner, shown.ticket.id, first.id)

		val listed = roadmap.roadmap().groups.flatMap { it.tickets }
		assertTrue(
			listed.any { it.identifier == shown.identifier },
			"the published ticket is the one thing the roadmap is for",
		)
		assertFalse(
			listed.any { it.identifier == hidden.identifier },
			"a ticket nobody published must not appear in the shop window",
		)
		assertFalse(
			listed.any { it.title.contains("acquisition") },
			"nor may its title reach the list by any other field",
		)

		val page = roadmap.contributorPage(shown.teamKey!!, shown.ticket.number!!)
		assertEquals(listOf("good first step"), page.labels, "the shown ticket wears its own badge")
		assertFalse(
			page.otherFirstSteps.any { it.identifier == hidden.identifier },
			"a private ticket marked `good first step` is still private",
		)
		assertEquals(1, page.availableCount, "and is not counted in the number offered either")
		assertFalse(
			json.writeValueAsString(ContributorResponse.of(page)).contains("cormorant"),
			"nor may the name of a label it wears reach the page by any route",
		)

		// A 404 rather than a 403: "you may not see this" tells a stranger the ticket
		// exists, which is half of what they were fishing for.
		assertFailsWith<NotFoundException>("the contributor page must not resolve a private key") {
			roadmap.contributorPage(hidden.teamKey!!, hidden.ticket.number!!)
		}
		assertFailsWith<NotFoundException>("nor may a stranger vote a private ticket up the list") {
			votes.vote(hidden.teamKey!!, hidden.ticket.number!!, "a-voter-key")
		}
	}

	@Test
	fun `a public ticket's responses carry no email address`() {
		val team = team()
		val member = person("J. Salas", InstanceRole.MEMBER)
		teams.addMember(team.id, member.id, MemberRole.MEMBER)

		val open = ticket(team.id, "The seal is unreadable at 100% zoom on Windows")
		publication.publish(owner, open.ticket.id, public = true)
		// A badge on the wire, so the `@` sweep below is actually reading label names too.
		labels.attach(
			owner,
			open.ticket.id,
			labels.create(owner, team.id, "design system", "blue").id,
		)
		publication.whereToLook(
			owner,
			open.ticket.id,
			listOf(FilePointer("packages/ui/seal.css", "the pattern")),
		)

		val page = roadmap.contributorPage(open.teamKey!!, open.ticket.number!!)
		assertEquals(
			listOf("J. Salas"),
			page.helpers.map { it.displayName },
			"a contributor is told who to talk to, by name",
		)

		val bodies: Map<String, String> = mapOf(
			"the roadmap" to json.writeValueAsString(RoadmapResponse.of(roadmap.roadmap())),
			"the contributor page" to json.writeValueAsString(ContributorResponse.of(page)),
		)
		for ((what, body) in bodies) {
			assertFalse(body.contains(member.email), "$what names a helper's email address")
			assertFalse(body.contains(owner.email), "$what names the publisher's email address")
			assertFalse(body.contains("@"), "$what carries an address-shaped string: $body")
		}
	}
}
