package dev.kanso.docs

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.DocRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.TeamService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folders, pages and templates. Blocks have their own file.
 *
 * `@Transactional`, so nothing here ever reaches `EventPublisher`'s `afterCommit` —
 * every assertion goes through the service, as the wiki's `Follow-ups` page records
 * it must.
 */
@Transactional
class DocServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var documents: DocService
	@Autowired lateinit var notionDocs: DocRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy { newUser(InstanceRole.ADMIN) }

	private fun newUser(role: InstanceRole) = users.createLocalUser(
		email = "docs-${UUID.randomUUID()}@kanso.test",
		displayName = "Docs ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun newTeam() = teams.create(
		admin,
		"Team ${UUID.randomUUID().toString().take(4)}",
		"K${UUID.randomUUID().toString().take(4).uppercase()}",
		null,
	)

	@Test
	fun `a folder names its parent and stays inside its team`() {
		val team = newTeam()
		val product = documents.createFolder(admin, team.id, null, "Product")
		val cycles = documents.createFolder(admin, team.id, product.id, "Cycle notes")

		val tree = documents.folders(team.id)

		// The list is flat and the client nests it, so what is asserted is the parentage,
		// not the order: a global ordering of a tree flattened by parent is not a
		// contract anybody reads, and pinning one here would only forbid changing it.
		assertEquals(2, tree.size)
		assertNull(tree.single { it.id == product.id }.parentId)
		assertEquals(product.id, tree.single { it.id == cycles.id }.parentId)
		assertEquals(team.id, cycles.teamId)
	}

	/**
	 * The same `unset` convention `TicketPatch` uses, and the same reason: `null` cannot
	 * mean both "leave alone" and "move to the root". Renaming a nested folder used to
	 * un-nest it, silently, because the parent came through as a bare nullable.
	 */
	@Test
	fun `renaming a nested folder leaves it where it is`() {
		val team = newTeam()
		val product = documents.createFolder(admin, team.id, null, "Product")
		val cycles = documents.createFolder(admin, team.id, product.id, "Cycle notes")

		val renamed = documents.updateFolder(admin, cycles.id, "Cycle reviews", null, emptySet())

		assertEquals("Cycle reviews", renamed.name)
		assertEquals(product.id, renamed.parentId)
	}

	@Test
	fun `a folder goes to the root only when asked`() {
		val team = newTeam()
		val product = documents.createFolder(admin, team.id, null, "Product")
		val cycles = documents.createFolder(admin, team.id, product.id, "Cycle notes")

		val moved = documents.updateFolder(admin, cycles.id, null, null, setOf("parentId"))

		assertNull(moved.parentId)
	}

	@Test
	fun `a folder cannot be filed inside its own descendant`() {
		val team = newTeam()
		val product = documents.createFolder(admin, team.id, null, "Product")
		val cycles = documents.createFolder(admin, team.id, product.id, "Cycle notes")

		assertFailsWith<BadRequestException> {
			documents.updateFolder(admin, product.id, null, cycles.id, emptySet())
		}
	}

	@Test
	fun `a folder cannot be parented into another team`() {
		val core = newTeam()
		val platform = newTeam()
		val elsewhere = documents.createFolder(admin, platform.id, null, "Design")

		assertFailsWith<BadRequestException> {
			documents.createFolder(admin, core.id, elsewhere.id, "Decisions")
		}
	}

	/**
	 * The decision the whole slice turns on: `notion_docs` is a four-column index row
	 * for a page somebody wrote in Notion, and a page written here is a different
	 * thing. Writing one must not touch that table at all.
	 */
	@Test
	fun `a page written here is legal without a notion page and adds no index row`() {
		val team = newTeam()
		val indexRowsBefore = notionDocs.findAll().size

		val page = documents.createPage(admin, team.id, null, "Mirror architecture", null)

		assertNull(page.page.notionPageId)
		assertEquals(admin.id, page.page.authorId)
		assertEquals(indexRowsBefore, notionDocs.findAll().size)
	}

	@Test
	fun `a page cannot be filed in another team's folder`() {
		val core = newTeam()
		val platform = newTeam()
		val elsewhere = documents.createFolder(admin, platform.id, null, "Design")

		assertFailsWith<BadRequestException> {
			documents.createPage(admin, core.id, elsewhere.id, "Screens review", null)
		}
	}

	@Test
	fun `a page from a template starts on the template's blocks`() {
		val team = newTeam()
		val decision = documents.templates().single { it.slug == "decision" }

		val page = documents.createPage(admin, team.id, null, "AGPL or not", decision.slug)

		assertEquals(decision.blocks.map { it.kind }, page.blocks.map { it.kind })
		assertEquals(decision.blocks.map { it.content }, page.blocks.map { it.content })
	}

	@Test
	fun `the three drawn templates are seeded`() {
		assertEquals(
			listOf("cycle-note", "decision", "incident-report"),
			documents.templates().map { it.slug }.sorted(),
		)
	}

	@Test
	fun `recently changed puts the page edited last at the top`() {
		val team = newTeam()
		val first = documents.createPage(admin, team.id, null, "Sync contract", null)
		documents.createPage(admin, team.id, null, "Screens review", null)
		documents.updatePage(admin, first.page.id, "Sync contract v2", null, emptySet())

		val recent = documents.pages(team.id, null, 10)

		assertEquals("Sync contract v2", recent.first().title)
		assertEquals(admin.id, recent.first().editedById)
	}

	@Test
	fun `a page belongs to the folder it is moved into, and to none once cleared`() {
		val team = newTeam()
		val product = documents.createFolder(admin, team.id, null, "Product")
		val page = documents.createPage(admin, team.id, null, "Runbook", null)

		val filed = documents.updatePage(admin, page.page.id, null, product.id, emptySet())
		assertEquals(product.id, filed.page.folderId)

		val loose = documents.updatePage(admin, page.page.id, null, null, setOf("folderId"))
		assertNull(loose.page.folderId)
	}

	/**
	 * Reads are open and writes are scoped — the rule `architecture.md` states, with no
	 * exception for a new endpoint. A team with a member is claimed, so an outsider is
	 * refused; the same outsider may still read the page.
	 */
	@Test
	fun `an outsider cannot write in a claimed team but may read it`() {
		val team = newTeam()
		val member = newUser(InstanceRole.MEMBER)
		val outsider = newUser(InstanceRole.MEMBER)
		teams.addMember(admin, team.id, member.id, dev.kanso.domain.MemberRole.MEMBER)
		val page = documents.createPage(admin, team.id, null, "Decisions", null)

		assertFailsWith<org.springframework.security.access.AccessDeniedException> {
			documents.createPage(outsider, team.id, null, "Sneaky", null)
		}
		assertTrue(documents.page(page.page.id).blocks.isEmpty())
	}

	@Test
	fun `deleting a folder does not delete the pages filed in it`() {
		val team = newTeam()
		val folder = documents.createFolder(admin, team.id, null, "Product")
		val page = documents.createPage(admin, team.id, folder.id, "Sync contract", null)

		documents.deleteFolder(admin, folder.id)

		assertNull(documents.page(page.page.id).page.folderId)
	}
}
