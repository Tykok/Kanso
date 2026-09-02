package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Project
import dev.kanso.domain.ProjectHealth
import dev.kanso.domain.ProjectStatus
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Health, and the two things it is not.
 *
 * It is not a column — the current health is the latest row of `project_updates`, so
 * every read here goes through [ProjectService.get] and never through a stored value.
 * And it is not [ProjectStatus] — the last test in this file is the one that matters
 * most, because the day somebody makes an update touch the status is the day the two
 * questions collapse back into one.
 */
@Transactional
class ProjectHealthTest : PostgresTest() {

	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var updates: ProjectUpdateService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var activity: ActivityService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole = InstanceRole.MEMBER): User = users.createLocalUser(
		email = "health-${UUID.randomUUID()}@kanso.test",
		displayName = "Health ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "H${UUID.randomUUID().toString().take(4).uppercase()}"

	/** Team-less on purpose unless a test says otherwise: the transverse case. */
	private fun newProject(teamId: UUID? = null): Project = projects.create(
		actor = admin,
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.IN_PROGRESS,
		start = null,
		end = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	/**
	 * A team with somebody in it. `TeamAccess`'s open-chain clause hands an unclaimed
	 * chain to everyone, so a team nobody has joined cannot refuse the outsider the
	 * permission tests need.
	 */
	private fun claimedTeam(): UUID =
		teams.create(admin, "Claimed", key(), null).id.also { teamRepo.addMember(it, admin.id, MemberRole.ADMIN) }

	// --- the current health --------------------------------------------------

	@Test
	fun `a project nobody has assessed has no health, not a good one`() {
		val project = newProject()

		// Null, and specifically not ON_TRACK. "Nobody has said" and "somebody said it is
		// fine" are different facts, and the optimistic default is how a signal becomes
		// noise — every project green on the day the feature ships.
		assertNull(projects.get(project.id).health)
	}

	@Test
	fun `an update posted is the health read back`() {
		val project = newProject()

		updates.post(admin, project.id, ProjectHealth.AT_RISK, "The API contract moved under us.")

		assertEquals(ProjectHealth.AT_RISK, projects.get(project.id).health)
	}

	@Test
	fun `the latest update wins, and the earlier one is still there`() {
		val project = newProject()
		updates.post(admin, project.id, ProjectHealth.ON_TRACK, "Two of three done.")
		updates.post(admin, project.id, ProjectHealth.OFF_TRACK, "The third needs a rewrite.")

		assertEquals(ProjectHealth.OFF_TRACK, projects.get(project.id).health)
		// Newest first, and the history keeps both: an update is a dated statement and the
		// correction for a wrong one is the next update, not an edit to it.
		assertEquals(
			listOf(ProjectHealth.OFF_TRACK, ProjectHealth.ON_TRACK),
			updates.forProject(project.id).map { it.health },
		)
	}

	@Test
	fun `a health that recovers reads as recovered`() {
		val project = newProject()
		updates.post(admin, project.id, ProjectHealth.OFF_TRACK, "Blocked on the vendor.")
		updates.post(admin, project.id, ProjectHealth.ON_TRACK, "Vendor answered.")

		assertEquals(ProjectHealth.ON_TRACK, projects.get(project.id).health)
	}

	@Test
	fun `the list carries each project's own health, and null for the unassessed`() {
		val assessed = newProject()
		val silent = newProject()
		updates.post(admin, assessed.id, ProjectHealth.AT_RISK, "Short a reviewer.")

		val byId = projects.list(null, includeDescendants = false, includeArchived = false)
			.associateBy { it.project.id }

		assertEquals(ProjectHealth.AT_RISK, byId.getValue(assessed.id).health)
		assertNull(byId.getValue(silent.id).health)
	}

	// --- what an update must carry -------------------------------------------

	@Test
	fun `an update needs a sentence under the colour`() {
		val project = newProject()

		assertFailsWith<BadRequestException> {
			updates.post(admin, project.id, ProjectHealth.AT_RISK, "   ")
		}
	}

	@Test
	fun `an update on a project that does not exist is a 404`() {
		assertFailsWith<NotFoundException> {
			updates.post(admin, UUID.randomUUID(), ProjectHealth.ON_TRACK, "Fine.")
		}
	}

	// --- who may post --------------------------------------------------------

	@Test
	fun `a plain member of the team may post one`() {
		val teamId = claimedTeam()
		val member = user()
		teamRepo.addMember(teamId, member.id, MemberRole.MEMBER)
		val project = newProject(teamId)

		updates.post(member, project.id, ProjectHealth.ON_TRACK, "Halfway, on time.")

		// The point of the assertion: archiving this same project would need an admin —
		// `ProjectPermissionTest` pins that — because disposition is instance
		// configuration. Saying how the work is going is daily work, so it takes the rule
		// daily work already has, which is `TicketAccess`'s team scope.
		assertEquals(ProjectHealth.ON_TRACK, projects.get(project.id).health)
	}

	@Test
	fun `somebody outside the team may not post on its project`() {
		val project = newProject(claimedTeam())

		assertFailsWith<AccessDeniedException> {
			updates.post(user(), project.id, ProjectHealth.OFF_TRACK, "Looks bad from here.")
		}
	}

	@Test
	fun `an admin may post on a team they are not in`() {
		val teamId = teams.create(admin, "Elsewhere", key(), null).id
		teamRepo.addMember(teamId, user().id, MemberRole.MEMBER)
		val project = newProject(teamId)

		updates.post(user(InstanceRole.ADMIN), project.id, ProjectHealth.AT_RISK, "Reviewed from outside.")

		assertEquals(ProjectHealth.AT_RISK, projects.get(project.id).health)
	}

	@Test
	fun `a project with no team is open to anyone, like the transverse case it is`() {
		val project = newProject(teamId = null)

		updates.post(user(), project.id, ProjectHealth.ON_TRACK, "Nobody owns this and it is fine.")

		assertEquals(ProjectHealth.ON_TRACK, projects.get(project.id).health)
	}

	// --- the feed ------------------------------------------------------------

	@Test
	fun `posting one writes the project's own activity row`() {
		val project = newProject()
		updates.post(admin, project.id, ProjectHealth.ON_TRACK, "First pass done.")
		updates.post(admin, project.id, ProjectHealth.AT_RISK, "Reviewer is out.")

		val feed = activity.forEntity(ActivityEntity.PROJECT, project.id)

		assertEquals(listOf(ActivityKind.HEALTH_POSTED, ActivityKind.HEALTH_POSTED), feed.map { it.kind })
		// Newest first. `to` is where the health moved and `from` is where it was, the same
		// shape `status_changed` uses — so a feed can say "moved to at risk from on track"
		// with one reader rather than a branch per kind.
		assertEquals(ProjectHealth.AT_RISK.wire, feed.first().payload["to"])
		assertEquals(ProjectHealth.ON_TRACK.wire, feed.first().payload["from"])
		// The first update moved from nothing, and the mapper omits nulls rather than
		// sending one — an absent key, not an explicit null.
		assertTrue("from" !in feed.last().payload)
		// The body is named, never quoted: an activity row outlives what it describes.
		assertTrue(feed.all { it.payload["updateId"] != null })
		assertTrue(feed.none { it.payload.values.any { value -> value == "Reviewer is out." } })
	}

	// --- the distinction the table exists for --------------------------------

	@Test
	fun `health does not touch the status, in either direction`() {
		val project = newProject()
		updates.post(admin, project.id, ProjectHealth.OFF_TRACK, "This will not make the date.")

		val after = projects.get(project.id)

		// A project that is in progress and off track is the whole point: status says
		// where the work is, health says whether it will land, and collapsing either into
		// the other loses the only sentence worth reading.
		assertEquals(ProjectStatus.IN_PROGRESS, after.project.status)
		assertEquals(ProjectHealth.OFF_TRACK, after.health)
	}

	@Test
	fun `changing the status leaves the health exactly where it was`() {
		val project = newProject()
		updates.post(admin, project.id, ProjectHealth.AT_RISK, "One dependency late.")

		projects.update(
			actor = admin,
			id = project.id,
			name = project.name,
			status = ProjectStatus.PAUSED,
			start = null,
			end = null,
			leadUserId = null,
			teamId = null,
			docIds = null,
		)

		assertEquals(ProjectHealth.AT_RISK, projects.get(project.id).health)
	}
}
