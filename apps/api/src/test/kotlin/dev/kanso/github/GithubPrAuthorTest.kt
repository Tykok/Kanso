package dev.kanso.github

import dev.kanso.PostgresTest
import dev.kanso.api.TicketResponse
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import dev.kanso.service.TicketDetails
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The name on the row, and the handle that is still there without it.
 *
 * This is the browser-visible half of `KAN-74`, asserted where it actually has to be
 * right: **on the JSON**, not on the Kotlin. The shared mapper is
 * `default-property-inclusion: non_null`, so a nullable field is *absent* from the body
 * rather than `null` in it — and a hand-written TypeScript type saying `author: User |
 * null` would be a lie no compiler catches. That exact trap has already produced a "Last
 * used Invalid Date" on a screen in this repository, so the assertion is `containsKey`
 * against a parsed body and not `assertNull` against a data class.
 */
@Transactional
class GithubPrAuthorTest : PostgresTest() {

	@Autowired lateinit var accounts: GithubAccountRepository
	@Autowired lateinit var github: GithubRepository
	@Autowired lateinit var details: TicketDetails
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var objectMapper: ObjectMapper

	private fun member(name: String): User = users.createLocalUser(
		email = "pr-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	private val admin: User by lazy {
		users.createLocalUser(
			email = "pr-admin-${UUID.randomUUID()}@kanso.test",
			displayName = "Admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun key() = "P${UUID.randomUUID().toString().take(4).uppercase()}"

	private fun team(): Team = teams.create(admin, "Repo", key(), null).also {
		teamRepo.addMember(it.id, admin.id, MemberRole.MEMBER)
	}

	private fun ticketIn(teamId: UUID): Ticket = tickets.create(
		actor = admin, teamId = teamId, title = "Overlap warning", description = null,
		status = DefaultStatus.TODO, priority = TicketPriority.NONE,
		start = null, due = null, projectId = null,
		assigneeIds = emptyList(), docIds = emptyList(),
	).ticket

	/**
	 * A pull request on a ticket, authored by [authorLogin].
	 *
	 * The installation is upserted first because `github_pull_requests.installation_id` is a
	 * foreign key — the same ordering `GithubRepository.rememberInstallation` documents, and
	 * the reason it is callable from any payload rather than only the `installation` event.
	 */
	private fun pullRequestOn(ticketId: UUID, number: Int, authorLogin: String?) {
		val installation = System.nanoTime() and 0x7FFF_FFFFL
		github.rememberInstallation(installation, "tykok", "Organization")
		val prId = github.upsertPullRequest(
			installationId = installation,
			repoFullName = "tykok/kanso",
			number = number,
			nodeId = "PR_$number",
			title = "Warn on overlap",
			url = "https://github.com/tykok/kanso/pull/$number",
			state = PrState.MERGED,
			draft = false,
			authorLogin = authorLogin,
			headRef = "feat/kan-142-overlap",
			baseRef = "main",
			openedAt = null,
			mergedAt = null,
		)
		github.link(ticketId, prId, closes = true, linkedBy = null)
	}

	/** The one row, as the API would actually serialise it. */
	private fun rowOf(ticket: Ticket): Map<*, *> {
		val detail = details.of(listOf(ticket)).single()
		val body = objectMapper.writeValueAsString(TicketResponse.of(detail))
		val parsed = objectMapper.readValue(body, Map::class.java)
		val prs = parsed["pullRequests"] as List<*>
		return prs.single() as Map<*, *>
	}

	/**
	 * **The assertion the ticket cares about most.**
	 *
	 * An author who never linked their GitHub account leaves the row exactly as it was
	 * before any of this existed: the handle, and no `author` key at all. Not a blank, not
	 * an error, not "unknown" — the key is missing, which is the one spelling of "nobody"
	 * this API has.
	 */
	@Test
	fun `an author who never linked has no author key and keeps their handle`() {
		val team = team()
		val ticket = ticketIn(team.id)
		pullRequestOn(ticket.id, 418, "a-stranger")

		val row = rowOf(ticket)

		assertEquals("a-stranger", row["authorLogin"], "the handle is what the row still shows")
		assertFalse(
			row.containsKey("author"),
			"the mapper omits nulls, so an unresolved author is an ABSENT key — a TS type " +
				"saying `author: User | null` would be wrong here",
		)
	}

	/**
	 * And the same row once that person consents.
	 *
	 * `authorLogin` stays: the GitHub handle is a fact about the pull request and the member
	 * name is a fact about Kanso, and a screen that showed only the second would lose the
	 * ability to say *which* GitHub account this member is.
	 */
	@Test
	fun `a consented author is named, and the handle stays beside the name`() {
		val team = team()
		val ticket = ticketIn(team.id)
		val elie = member("Elie")
		accounts.link(elie.id, System.nanoTime() and 0x7FFF_FFFFL, "tykok", GithubToken("ghu_x", null, null))
		pullRequestOn(ticket.id, 419, "tykok")

		val row = rowOf(ticket)

		assertTrue(row.containsKey("author"), "a consented author is named")
		val author = row["author"] as Map<*, *>
		assertEquals("Elie", author["displayName"])
		assertEquals(elie.id.toString(), author["id"])
		assertEquals("tykok", row["authorLogin"], "the handle is not replaced by the name")
	}

	/**
	 * The capitalisation on the payload is not the capitalisation in the table, and the join
	 * has to survive that.
	 *
	 * `github_accounts_login_idx` is `lower(github_login)` for this reason, stated in `V36`:
	 * GitHub logins are case-insensitive and a payload's spelling is not stable enough to
	 * join on. An author stored as `Tykok` whose pull request says `TYKOK` is one person.
	 */
	@Test
	fun `the join matches a login GitHub spelled differently`() {
		val team = team()
		val ticket = ticketIn(team.id)
		val elie = member("Elie")
		accounts.link(elie.id, System.nanoTime() and 0x7FFF_FFFFL, "Tykok", GithubToken("ghu_x", null, null))
		pullRequestOn(ticket.id, 420, "TYKOK")

		val author = rowOf(ticket)["author"] as? Map<*, *>
		assertEquals("Elie", author?.get("displayName"), "one person, two spellings")
	}

	/**
	 * A pull request with no author at all still has a row.
	 *
	 * `author_login` is nullable on `github_pull_requests`, and the `LEFT JOIN` has to
	 * tolerate joining on null rather than dropping the pull request. An `INNER JOIN` here
	 * would turn a missing name into a missing row, which is the failure this test exists to
	 * catch — a section that silently loses entries is worse than one that shows a handle.
	 */
	@Test
	fun `a pull request with no author is still on the ticket`() {
		val team = team()
		val ticket = ticketIn(team.id)
		pullRequestOn(ticket.id, 421, null)

		val row = rowOf(ticket)
		assertFalse(row.containsKey("authorLogin"), "no author, so no handle either")
		assertFalse(row.containsKey("author"))
		assertEquals(421, row["number"], "and the pull request itself did not disappear")
	}
}
