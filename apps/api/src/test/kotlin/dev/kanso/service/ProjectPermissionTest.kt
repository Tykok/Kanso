package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A project's disposition reaches every ticket it holds, the same blast radius as a
 * team's — see `TeamPermissionTest`, which this mirrors.
 */
@Transactional
class ProjectPermissionTest : PostgresTest() {

	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole): User = users.createLocalUser(
		email = "proj-perm-${UUID.randomUUID()}@kanso.test",
		displayName = "Test ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun newProject() = projects.create(
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
}
