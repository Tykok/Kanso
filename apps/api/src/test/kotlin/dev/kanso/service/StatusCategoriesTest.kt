package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TeamStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamStatusRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a status means, asked in one place — `KAN-90`.
 *
 * Every case here is a question a screen used to answer with `ticket.status.category`, and
 * that property could only ever answer out of the six. A team's seventh status would have
 * had no answer at all, which is why this collaborator exists before the type change that
 * makes the seventh possible.
 *
 * The rows are inserted through [TeamStatusRepository] rather than through a service `add`,
 * because `add` is Task 2 and this is Task 1: what is under test is the *reading*, and it
 * has to be right before there is a door that writes a seventh word.
 */
@Transactional
class StatusCategoriesTest : PostgresTest() {

	@Autowired lateinit var categories: StatusCategories
	@Autowired lateinit var statuses: TeamStatusRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun owner() = users.createLocalUser(
		email = "categories-${UUID.randomUUID()}@kanso.test",
		displayName = "Categories owner",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.OWNER,
	)

	private fun team(actor: User) = teams.create(actor, "Team ${UUID.randomUUID()}", null, null)

	/** A seventh word, written straight to the catalogue — Task 2 is the door for it. */
	private fun add(teamId: UUID, label: String, category: StatusCategory, position: Int): String {
		val key = dev.kanso.domain.statusKeyOf(label)
		statuses.insert(TeamStatus(teamId, key, label, category, position))
		return key
	}

	@Test
	fun `a status a team invented means what the team said it means`() {
		val actor = owner()
		val team = team(actor)
		val key = add(team.id, "Devis", StatusCategory.BACKLOG, 6)

		// The whole ticket in one assertion: `DefaultStatus` has no `devis`, so this is an
		// answer no property on the ticket could have given.
		assertEquals(StatusCategory.BACKLOG, categories.categoryOf(team.id, key))
	}

	@Test
	fun `two teams' words for one category are both that category`() {
		val actor = owner()
		val one = team(actor)
		val other = team(actor)
		val theirs = add(other.id, "Qualifié", StatusCategory.UNSTARTED, 6)

		// `todo` is seeded as UNSTARTED in both, and the invented word means the same
		// thing in the team that invented it — which is what lets a cross-team list put
		// all three rows under one header.
		assertEquals(StatusCategory.UNSTARTED, categories.categoryOf(one.id, "todo"))
		assertEquals(StatusCategory.UNSTARTED, categories.categoryOf(other.id, "todo"))
		assertEquals(StatusCategory.UNSTARTED, categories.categoryOf(other.id, theirs))
	}

	@Test
	fun `a draft answers out of the six, and an unreadable status is unstarted work`() {
		// No team to ask, so the six are the vocabulary — `DefaultStatus`' own docstring.
		assertEquals(StatusCategory.STARTED, categories.categoryOf(null, "in_review"))
		assertEquals(StatusCategory.COMPLETED, categories.categoryOf(null, "done"))

		// Unreachable rather than lenient: `tickets_status_fk` refuses a row whose status
		// its team never declared. Counted as unstarted instead of throwing, so a path
		// nobody has written yet draws a burndown one ticket short rather than none.
		assertEquals(StatusCategory.UNSTARTED, categories.categoryOf(null, "devis"))
	}

	@Test
	fun `forTeam answers a seven-entry map once a team has invented a word`() {
		val actor = owner()
		val team = team(actor)
		add(team.id, "Devis", StatusCategory.BACKLOG, 6)

		val map = categories.forTeam(team.id)

		assertEquals(7, map.size)
		assertEquals(StatusCategory.BACKLOG, map["devis"])
		assertEquals(StatusCategory.COMPLETED, map["done"])
	}

	@Test
	fun `keysMeaning names the team's own words, not Kanso's`() {
		val actor = owner()
		val team = team(actor)
		add(team.id, "Devis", StatusCategory.BACKLOG, 6)

		// This is what replaces the seven `DefaultStatus.entries.filter { … }` lists that
		// were rendered into `WHERE status IN (…)`. A list built from the enum would have
		// left `devis` out of every backlog query, silently.
		assertEquals(listOf("backlog", "devis"), categories.keysMeaning(team.id, StatusCategory.BACKLOG).sorted())
		assertEquals(listOf("done"), categories.keysMeaning(team.id, StatusCategory.COMPLETED))
	}

	/**
	 * What `of` is for: two teams and a draft resolved from one read of the catalogue.
	 *
	 * Every status here is one of the six, and it has to be — `Ticket.status` is still
	 * `DefaultStatus`, so a row *carrying* an invented word is impossible until Task 3
	 * changes the type. What this pins is the part that is testable now and is the reason
	 * `of` exists at all: the batching, the per-team resolution, and the draft's fallback.
	 * The seventh-status case for `Categories[ticket]` is asserted in Task 3, on a row
	 * that can finally hold one — [categoryOf][StatusCategories.categoryOf] above already
	 * proves the lookup underneath it.
	 */
	@Test
	fun `a Categories resolves each row against its own team, and a draft against the six`() {
		val actor = owner()
		val one = team(actor)
		val other = team(actor)
		statuses.rename(other.id, "todo", "Qualifié")

		val rows = listOf(
			ticket(one.id, DefaultStatus.IN_PROGRESS),
			ticket(other.id, DefaultStatus.TODO),
			ticket(null, DefaultStatus.DONE),
		)

		val resolved = categories.of(rows)

		// Read at the call site where `ticket.status.category` used to be, which is the
		// only shape a burndown holding hundreds of rows can afford.
		assertEquals(StatusCategory.STARTED, resolved[rows[0]])
		// Renamed, and still UNSTARTED: the word moved and the meaning did not.
		assertEquals(StatusCategory.UNSTARTED, resolved[rows[1]])
		assertEquals(StatusCategory.COMPLETED, resolved[rows[2]])
	}

	private fun ticket(teamId: UUID?, status: DefaultStatus) = dev.kanso.domain.Ticket(
		id = UUID.randomUUID(),
		number = null,
		teamId = teamId,
		createdBy = null,
		title = "A row",
		description = null,
		status = status,
		priority = dev.kanso.domain.TicketPriority.NONE,
		estimate = null,
		start = null,
		due = null,
		completedAt = null,
		projectId = null,
		parentId = null,
		archived = false,
		mirror = dev.kanso.domain.MirrorInfo(),
		createdAt = java.time.OffsetDateTime.now(),
		updatedAt = java.time.OffsetDateTime.now(),
	)
}
