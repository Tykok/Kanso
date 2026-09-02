package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.User
import dev.kanso.domain.MemberRole
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A project's disposition reaches every ticket it holds, the same blast radius as a
 * team's — see `TeamPermissionTest`, which this mirrors.
 */
@Transactional
class ProjectPermissionTest : PostgresTest() {

	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole): User = users.createLocalUser(
		email = "proj-perm-${UUID.randomUUID()}@kanso.test",
		displayName = "Test ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun newProject() = projects.create(
		actor = admin,
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		start = null,
		end = null,
		leadUserId = null,
		teamId = null,
		docIds = emptyList(),
	).project

	@Test
	fun `a member cannot archive a project`() {
		val member = user(InstanceRole.MEMBER)
		val project = newProject()

		assertFailsWith<AccessDeniedException> { projects.archive(member, project.id, DispositionPlan()) }
	}

	@Test
	fun `an admin may archive a project`() {
		val admin = user(InstanceRole.ADMIN)
		val project = newProject()

		assertTrue(projects.archive(admin, project.id, DispositionPlan()).project.archived)
	}

	@Test
	fun `a member cannot unarchive a project`() {
		val admin = user(InstanceRole.ADMIN)
		val member = user(InstanceRole.MEMBER)
		val project = newProject()
		projects.archive(admin, project.id, DispositionPlan())

		assertFailsWith<AccessDeniedException> { projects.unarchive(member, project.id) }
	}

	@Test
	fun `an admin may unarchive a project`() {
		val admin = user(InstanceRole.ADMIN)
		val project = newProject()
		projects.archive(admin, project.id, DispositionPlan())

		assertFalse(projects.unarchive(admin, project.id).project.archived)
	}

	@Test
	fun `a member cannot delete a project`() {
		val member = user(InstanceRole.MEMBER)
		val project = newProject()

		assertFailsWith<AccessDeniedException> {
			projects.delete(member, project.id, DispositionPlan(counts = projects.contents(project.id).direct))
		}
	}

	@Test
	fun `an admin may delete a project`() {
		val admin = user(InstanceRole.ADMIN)
		val project = newProject()

		projects.delete(admin, project.id, DispositionPlan(counts = projects.contents(project.id).direct))
		assertFailsWith<NotFoundException> { projects.get(project.id) }
	}

	/**
	 * The three below are about `create` and `update`, which took no actor at all until
	 * KAN-13's audit: any member could file a project into somebody else's team and rename,
	 * re-team or re-lead any existing one, while `archive` beside them asked for a
	 * configurator. Each of these fails against the unguarded service.
	 *
	 * They build a team with a member in it on purpose. `TicketAccess.claimedBy` leaves a
	 * team nobody belongs to open to everyone, which is the rule the rest of this file's
	 * fixtures rely on — so a test written against an empty team would pass either way and
	 * prove nothing.
	 */
	private fun claimedTeam(owner: User): UUID {
		val team = teamRows.insert("Owned ${UUID.randomUUID().toString().take(4)}", "T${UUID.randomUUID().toString().take(3).uppercase()}", null)
		teams.addMember(owner, team.id, owner.id, MemberRole.MEMBER)
		return team.id
	}

	@Test
	fun `an outsider cannot file a project into somebody else's team`() {
		val insider = user(InstanceRole.MEMBER)
		val outsider = user(InstanceRole.MEMBER)
		val team = claimedTeam(insider)

		assertFailsWith<AccessDeniedException> {
			projects.create(outsider, "Theirs", ProjectStatus.PLANNED, null, null, null, team, emptyList())
		}
	}

	@Test
	fun `an outsider cannot rename a project in somebody else's team`() {
		val insider = user(InstanceRole.MEMBER)
		val outsider = user(InstanceRole.MEMBER)
		val team = claimedTeam(insider)
		val project = projects.create(insider, "Theirs", ProjectStatus.PLANNED, null, null, null, team, emptyList()).project

		assertFailsWith<AccessDeniedException> {
			projects.update(outsider, project.id, "Mine now", ProjectStatus.PLANNED, null, null, null, team, null)
		}
	}

	@Test
	fun `a move is refused when the destination is not the actor's to edit`() {
		val insider = user(InstanceRole.MEMBER)
		val mover = user(InstanceRole.MEMBER)
		val theirs = claimedTeam(insider)
		val ours = claimedTeam(mover)
		val project = projects.create(mover, "Ours", ProjectStatus.PLANNED, null, null, null, ours, emptyList()).project

		// The origin is the mover's, so a rule that only asked about it would allow this.
		assertFailsWith<AccessDeniedException> {
			projects.update(mover, project.id, "Ours", ProjectStatus.PLANNED, null, null, null, theirs, null)
		}
	}
}
