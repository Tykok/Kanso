package dev.kanso.trash

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.docs.DocBlockRepository
import dev.kanso.docs.DocBlockService
import dev.kanso.docs.DocService
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.NotFoundException
import dev.kanso.service.ProjectService
import dev.kanso.service.SavedViewService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import dev.kanso.service.ViewGroupBy
import dev.kanso.service.ViewSortBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three kinds `V11` named and the branch that wrote it could not answer for.
 *
 * `TrashServiceTest` owns the ticket, the countdown and the sweep — the machinery every
 * kind shares. This file owns what is particular to a document, a saved view and a
 * folder: what each one's label says, where a restore puts it back, and above all what it
 * holds and whether the delete reaches it.
 *
 * Asserted through the services rather than through events, for the reason
 * `TrashServiceTest` states: the suite is `@Transactional` and rolls back, so
 * `EventPublisher`'s `afterCommit` never fires and an event assertion could only pass
 * vacuously.
 */
@Transactional
class TrashSourcesTest : PostgresTest() {

	@Autowired lateinit var trash: TrashService
	@Autowired lateinit var entries: TrashRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var documents: DocService
	@Autowired lateinit var blocks: DocBlockService
	@Autowired lateinit var blockRows: DocBlockRepository
	@Autowired lateinit var views: SavedViewService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "sources-${UUID.randomUUID()}@kanso.test",
		displayName = "Sources ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun newTeam(name: String = "Core") =
		teams.create(admin, name, "T${UUID.randomUUID().toString().take(4).uppercase()}", null)

	private fun newTicket(teamId: UUID, title: String, projectId: UUID? = null) = tickets.create(
		actor = admin,
		teamId = teamId,
		title = title,
		description = null,
		status = DefaultStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = projectId,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket

	private fun trashRow(id: UUID) = trash.load().trash.single { it.id == id }

	private fun holding(id: UUID, kind: TrashHoldingKind) =
		trashRow(id).holds.single { it.kind == kind }

	// --- documents -----------------------------------------------------------

	@Test
	fun `deleting a document moves it to the trash instead of destroying it`() {
		val team = newTeam()
		val page = documents.createPage(admin, team.id, null, "Cycle notes 22", null).page

		documents.deletePage(admin, page.id)

		assertFailsWith<NotFoundException>("a document in the trash is not a document you can open") {
			documents.page(page.id)
		}
		assertTrue(
			documents.pages(team.id, null, 50).isEmpty(),
			"and it is gone from the recent list screen 22 draws",
		)
		val row = trashRow(page.id)
		assertEquals(TrashKind.DOC, row.kind)
		assertEquals("Cycle notes 22", row.label, "the title is what anybody calls a page out loud")
		assertEquals(admin.id, row.deletedBy?.id)
	}

	/**
	 * The drawing's own load-bearing sentence, from the end that owns it: deleting a page
	 * that mentioned two tickets deletes neither ticket. `holds` is what makes that a fact
	 * the pane reads rather than a sentence somebody typed into a component, so both halves
	 * of the pair are asserted — the blocks that go, and the tickets that do not.
	 */
	@Test
	fun `purging a document that mentioned two tickets deletes neither ticket`() {
		val team = newTeam()
		val page = documents.createPage(admin, team.id, null, "Cycle notes 22", null).page
		val seal = newTicket(team.id, "SVG seal")
		val composer = newTicket(team.id, "Old composer")
		blocks.linkTicket(admin, page.id, seal.id, null)
		blocks.linkTicket(admin, page.id, composer.id, null)

		documents.deletePage(admin, page.id)

		assertEquals(2, holding(page.id, TrashHoldingKind.BLOCKS).count)
		assertTrue(holding(page.id, TrashHoldingKind.BLOCKS).cascades, "the blocks go with the page")
		assertEquals(2, holding(page.id, TrashHoldingKind.MENTIONED_TICKETS).count)
		assertFalse(
			holding(page.id, TrashHoldingKind.MENTIONED_TICKETS).cascades,
			"and the tickets do not — which is the whole sentence the pane has to say",
		)

		trash.purge(admin, TrashKind.DOC, page.id)

		assertEquals(
			listOf("Old composer", "SVG seal"),
			listOf(composer, seal).map { tickets.get(it.id).ticket.title }.sorted(),
			"only the reference goes",
		)
		assertTrue(blockRows.findByPage(page.id).isEmpty(), "the blocks and their backlinks went")
	}

	@Test
	fun `restoring a document puts it back in the folder it was filed in`() {
		val team = newTeam()
		val folder = documents.createFolder(admin, team.id, null, "Product")
		val page = documents.createPage(admin, team.id, folder.id, "Pricing draft", null).page
		documents.deletePage(admin, page.id)

		assertEquals("Product", trashRow(page.id).parent?.name, "the parent is named, not implied")

		trash.restore(admin, TrashKind.DOC, page.id)

		assertEquals(folder.id, documents.page(page.id).page.folderId)
		assertTrue(trash.load().trash.isEmpty())
	}

	@Test
	fun `a document at the root of its team is restored into the team`() {
		val team = newTeam("Core")
		val page = documents.createPage(admin, team.id, null, "Loose page", null).page
		documents.deletePage(admin, page.id)

		assertEquals("Core", trashRow(page.id).parent?.name)
		assertEquals(team.id, trashRow(page.id).parent?.id)
	}

	/**
	 * Archived is a decision and deleted is a countdown, and only a ticket carries the
	 * first: there is no `archived` column on `doc_pages`, `doc_folders` or `saved_views`,
	 * and adding one would be a migration inventing a fact no screen draws. Refused with a
	 * message rather than silently doing nothing, and the entry stays put — the order
	 * `TrashService.exit` keeps is what guarantees that.
	 */
	@Test
	fun `a document cannot be archived instead, because only a ticket can be archived`() {
		val team = newTeam()
		val page = documents.createPage(admin, team.id, null, "Cycle notes 22", null).page
		documents.deletePage(admin, page.id)

		assertFailsWith<BadRequestException> { trash.archiveInstead(admin, TrashKind.DOC, page.id) }

		assertEquals(listOf(page.id), trash.load().trash.map { it.id }, "and nothing moved")
	}

	/**
	 * And the row says so *before* anybody presses it. A button that refuses everything it
	 * is offered for is worse than a button that is not there: the pane draws the exits a
	 * row actually has, from the same fact the refusal above is made of.
	 */
	@Test
	fun `a row says whether the middle exit exists for its kind`() {
		val team = newTeam()
		val page = documents.createPage(admin, team.id, null, "Cycle notes 22", null).page
		val ticket = newTicket(team.id, "SVG seal")
		documents.deletePage(admin, page.id)
		tickets.delete(admin, ticket.id)

		assertFalse(trashRow(page.id).canArchive, "a document has no archive to go to")
		assertTrue(trashRow(ticket.id).canArchive, "a ticket does, and it is the only one that does")
	}

	@Test
	fun `a stranger cannot restore or purge a claimed team's document`() {
		val team = newTeam("Mobile")
		teamRows.addMember(team.id, admin.id, MemberRole.MEMBER)
		val page = documents.createPage(admin, team.id, null, "Cycle notes 22", null).page
		documents.deletePage(admin, page.id)
		val stranger = user(InstanceRole.MEMBER)

		assertFailsWith<AccessDeniedException> { trash.restore(stranger, TrashKind.DOC, page.id) }
		assertFailsWith<AccessDeniedException> { trash.purge(stranger, TrashKind.DOC, page.id) }
		assertEquals(listOf(page.id), trash.load().trash.map { it.id }, "and nothing moved")
	}

	// --- saved views ---------------------------------------------------------

	private fun newView(teamId: UUID, name: String) = views.create(
		actor = admin,
		teamId = teamId,
		name = name,
		shared = true,
		filters = emptyMap(),
		groupBy = ViewGroupBy.STATUS,
		sortBy = ViewSortBy.PRIORITY,
	)

	@Test
	fun `deleting a saved view takes it out of the rail and puts it in the trash`() {
		val team = newTeam("Core")
		val view = newView(team.id, "Slipping")

		views.delete(admin, view.id)

		assertTrue(views.list(team.id).isEmpty(), "the sidebar rail is a live read")
		assertFailsWith<NotFoundException> { views.get(view.id) }
		val row = trashRow(view.id)
		assertEquals(TrashKind.VIEW, row.kind)
		assertEquals("Slipping", row.label)
		assertEquals("Core", row.parent?.name, "a saved view belongs to a team, and always has one")
		assertTrue(row.holds.isEmpty(), "a stored question holds nothing: the rows are matched, not kept")
	}

	@Test
	fun `restoring a saved view brings it back to the rail with its question intact`() {
		val team = newTeam()
		val view = newView(team.id, "Slipping")
		views.delete(admin, view.id)

		trash.restore(admin, TrashKind.VIEW, view.id)

		assertEquals(listOf("Slipping"), views.list(team.id).map { it.view.name })
		assertTrue(trash.load().trash.isEmpty())
	}

	@Test
	fun `purging a saved view is the only thing that destroys the row`() {
		val team = newTeam()
		val view = newView(team.id, "Slipping")
		views.delete(admin, view.id)

		trash.purge(admin, TrashKind.VIEW, view.id)

		assertTrue(trash.load().trash.isEmpty())
		assertFailsWith<NotFoundException> { views.get(view.id) }
	}

	/**
	 * `saved_views_team_name_uniq` still holds a view in the trash to its name, so the
	 * conflict is unavoidable — but a 409 naming a view the reader cannot see anywhere is
	 * the kind of refusal that reads as a bug. It says where the name went instead.
	 */
	@Test
	fun `a name held by a view in the trash is refused by saying where it is`() {
		val team = newTeam()
		val view = newView(team.id, "Slipping")
		views.delete(admin, view.id)

		val error = assertFailsWith<Exception> { newView(team.id, "Slipping") }

		assertTrue(
			error.message.orEmpty().contains("trash"),
			"a refusal naming an invisible view is worse than no refusal: ${error.message}",
		)
	}

	// --- folders -------------------------------------------------------------

	/**
	 * The open decision of the follow-up, settled: the delete cascades to the sub-folders
	 * and not to the pages, which is the line `V9` already drew in the schema —
	 * `doc_folders.parent_id` is ON DELETE CASCADE, `doc_pages.folder_id` is ON DELETE SET
	 * NULL. The trash does not change the shape of the delete, only when it becomes
	 * irreversible.
	 */
	@Test
	fun `deleting a folder takes its sub-folders with it and files every page at the root`() {
		val team = newTeam()
		val product = documents.createFolder(admin, team.id, null, "Product")
		val cycles = documents.createFolder(admin, team.id, product.id, "Cycle notes")
		val top = documents.createPage(admin, team.id, product.id, "Pricing draft", null).page
		val nested = documents.createPage(admin, team.id, cycles.id, "Cycle 22", null).page

		documents.deleteFolder(admin, product.id)

		assertTrue(documents.folders(team.id).isEmpty(), "the branch goes together, as it always has")
		assertEquals(2, holding(product.id, TrashHoldingKind.PAGES).count)
		assertFalse(
			holding(product.id, TrashHoldingKind.PAGES).cascades,
			"tidying a tree is not a decision to destroy what was written in it",
		)
		assertEquals(1, holding(product.id, TrashHoldingKind.FOLDERS).count)
		assertTrue(
			holding(product.id, TrashHoldingKind.FOLDERS).cascades,
			"and the pane says the sub-folder goes before anybody confirms",
		)
		assertEquals(
			listOf(null, null),
			documents.pages(team.id, null, 50).sortedBy { it.title }.map { it.folderId },
			"both pages are readable, at the root — the one place a purge would leave them",
		)
		assertEquals(
			listOf("Cycle 22", "Pricing draft"),
			documents.pages(team.id, null, 50).map { it.title }.sorted(),
		)
	}

	@Test
	fun `restoring a folder puts the branch and its pages back exactly`() {
		val team = newTeam()
		val product = documents.createFolder(admin, team.id, null, "Product")
		val cycles = documents.createFolder(admin, team.id, product.id, "Cycle notes")
		val page = documents.createPage(admin, team.id, cycles.id, "Cycle 22", null).page
		documents.deleteFolder(admin, product.id)

		trash.restore(admin, TrashKind.FOLDER, product.id)

		val tree = documents.folders(team.id)
		assertEquals(setOf(product.id, cycles.id), tree.map { it.id }.toSet())
		assertEquals(product.id, tree.single { it.id == cycles.id }.parentId)
		assertEquals(
			cycles.id,
			documents.page(page.id).page.folderId,
			"nothing was written on the way in, so nothing has to be guessed on the way out",
		)
	}

	@Test
	fun `a folder at the root is restored into its team, and a nested one into its parent`() {
		val team = newTeam("Core")
		val product = documents.createFolder(admin, team.id, null, "Product")
		val cycles = documents.createFolder(admin, team.id, product.id, "Cycle notes")

		documents.deleteFolder(admin, cycles.id)
		assertEquals("Product", trashRow(cycles.id).parent?.name)

		documents.deleteFolder(admin, product.id)
		assertEquals("Core", trashRow(product.id).parent?.name)
	}

	@Test
	fun `purging a folder destroys the branch and keeps every page`() {
		val team = newTeam()
		val product = documents.createFolder(admin, team.id, null, "Product")
		val cycles = documents.createFolder(admin, team.id, product.id, "Cycle notes")
		val page = documents.createPage(admin, team.id, cycles.id, "Cycle 22", null).page
		documents.deleteFolder(admin, product.id)

		trash.purge(admin, TrashKind.FOLDER, product.id)

		assertTrue(trash.load().trash.isEmpty())
		assertTrue(documents.folders(team.id).isEmpty())
		assertEquals(
			"Cycle 22",
			documents.page(page.id).page.title,
			"the one mistake nobody can undo is destroying the writing, and this never does it",
		)
	}

	@Test
	fun `a stranger cannot restore or purge a claimed team's folder or view`() {
		val team = newTeam("Mobile")
		teamRows.addMember(team.id, admin.id, MemberRole.MEMBER)
		val folder = documents.createFolder(admin, team.id, null, "Product")
		val view = newView(team.id, "Slipping")
		documents.deleteFolder(admin, folder.id)
		views.delete(admin, view.id)
		val stranger = user(InstanceRole.MEMBER)

		assertFailsWith<AccessDeniedException> { trash.restore(stranger, TrashKind.FOLDER, folder.id) }
		assertFailsWith<AccessDeniedException> { trash.purge(stranger, TrashKind.VIEW, view.id) }
		assertEquals(2, trash.load().trash.size, "and nothing moved")
	}

	// --- the seams -----------------------------------------------------------

	/**
	 * `doc_pages`, `doc_folders` and `saved_views` are all `ON DELETE CASCADE` from `teams`,
	 * so a team's disposition takes them in Postgres without any Kotlin asking — and `V11`
	 * has no foreign key to take their entries with them. Three kinds, one orphan each,
	 * from one gesture.
	 */
	@Test
	fun `deleting a team forgets the entries of the documents, folders and views it takes`() {
		val team = newTeam()
		val page = documents.createPage(admin, team.id, null, "Cycle notes 22", null).page
		val folder = documents.createFolder(admin, team.id, null, "Product")
		val view = newView(team.id, "Slipping")
		documents.deletePage(admin, page.id)
		documents.deleteFolder(admin, folder.id)
		views.delete(admin, view.id)

		teams.delete(
			admin,
			team.id,
			DispositionPlan(tickets = DispositionChoice.TAKE, counts = teams.contents(team.id).direct),
		)

		assertTrue(trash.load().trash.isEmpty())
		assertNull(entries.find(TrashKind.DOC, page.id))
		assertNull(entries.find(TrashKind.FOLDER, folder.id))
		assertNull(entries.find(TrashKind.VIEW, view.id))
	}

	/**
	 * The other disposition path, and the only kind it destroys.
	 *
	 * The live ticket is what makes the case real rather than hypothetical:
	 * `ProjectService.disperseTickets` returns early when it can see nothing, and it cannot
	 * see the trash — so a project holding *only* a thrown-away ticket destroys nothing at
	 * all and leaves it alive with `project_id` cleared, which is `ON DELETE SET NULL` doing
	 * the right thing. It is the mixed case that reaches `deleteByProject`, and that
	 * statement deletes by `project_id` without asking about the trash.
	 */
	@Test
	fun `deleting a project forgets the entry of a ticket it destroys`() {
		val team = newTeam()
		val project = projects.create(
			actor = admin,
			name = "Product",
			status = ProjectStatus.PLANNED,
			start = null,
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		newTicket(team.id, "Still live", project.id)
		val ticket = newTicket(team.id, "SVG seal", project.id)
		tickets.delete(admin, ticket.id)

		projects.delete(
			admin,
			project.id,
			DispositionPlan(
				tickets = DispositionChoice.TAKE,
				counts = projects.contents(project.id).direct,
			),
		)

		assertTrue(trash.load().trash.isEmpty())
		assertNull(entries.find(TrashKind.TICKET, ticket.id))
	}

	// --- the sweep reaches every kind ----------------------------------------

	/**
	 * The countdown is `TrashServiceTest`'s subject, but a sweep that only knew how to
	 * empty a ticket would leave three kinds sitting past their thirty days forever.
	 */
	@Test
	fun `the sweep empties a document, a view and a folder past their thirty days`() {
		val team = newTeam()
		val page = documents.createPage(admin, team.id, null, "Cycle notes 22", null).page
		val folder = documents.createFolder(admin, team.id, null, "Product")
		val view = newView(team.id, "Slipping")
		documents.deletePage(admin, page.id)
		documents.deleteFolder(admin, folder.id)
		views.delete(admin, view.id)
		listOf(TrashKind.DOC to page.id, TrashKind.FOLDER to folder.id, TrashKind.VIEW to view.id)
			.forEach { (kind, id) -> entries.backdate(kind, id, java.time.OffsetDateTime.now().minusDays(31)) }

		assertEquals(3, trash.empty())

		assertTrue(trash.load().trash.isEmpty())
		assertFailsWith<NotFoundException> { documents.page(page.id) }
	}
}
