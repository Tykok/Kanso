package dev.kanso.docs

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The blocks of a page, and the backlinks that make a mentioned ticket the same ticket. */
@Transactional
class DocBlockServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var documents: DocService
	@Autowired lateinit var blocks: DocBlockService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "blocks-${UUID.randomUUID()}@kanso.test",
			displayName = "Blocks admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun newTeam() = teams.create(
		admin,
		"Team ${UUID.randomUUID().toString().take(4)}",
		"K${UUID.randomUUID().toString().take(4).uppercase()}",
		null,
	)

	private fun newPage(teamId: UUID) =
		documents.createPage(admin, teamId, null, "Mirror architecture", null).page

	private fun paragraph(text: String) = mapOf<String, Any?>("text" to text)

	@Test
	fun `blocks come back in the order they were written`() {
		val page = newPage(newTeam().id)
		blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("Postgres wins"), null)
		blocks.addBlock(admin, page.id, DocBlockKind.HEADING, mapOf("text" to "The path of a write"), null)
		blocks.addBlock(admin, page.id, DocBlockKind.CHECKBOX, mapOf("text" to "Echo suppression", "checked" to false), null)

		val written = documents.page(page.id).blocks

		assertEquals(listOf(0, 1, 2), written.map { it.position })
		assertEquals(
			listOf(DocBlockKind.PARAGRAPH, DocBlockKind.HEADING, DocBlockKind.CHECKBOX),
			written.map { it.kind },
		)
	}

	@Test
	fun `a block inserted after another lands between it and the next`() {
		val page = newPage(newTeam().id)
		val first = blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("one"), null)
		blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("three"), null)

		blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("two"), first.id)

		assertEquals(
			listOf("one", "two", "three"),
			documents.page(page.id).blocks.map { it.content["text"] },
		)
	}

	@Test
	fun `moving a block renumbers the whole page`() {
		val page = newPage(newTeam().id)
		blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("one"), null)
		blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("two"), null)
		val third = blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("three"), null)

		blocks.moveBlock(admin, third.id, 0)

		val reordered = documents.page(page.id).blocks
		assertEquals(listOf("three", "one", "two"), reordered.map { it.content["text"] })
		assertEquals(listOf(0, 1, 2), reordered.map { it.position })
	}

	/**
	 * A callout needs a body, a checkbox a state. The kind vocabulary is closed by a
	 * `CHECK`, so this is about the *content* of a legal kind: a block whose shape does
	 * not match its kind reads as an empty block forever, with nothing to say why.
	 */
	@Test
	fun `a block whose content does not match its kind is refused`() {
		val page = newPage(newTeam().id)

		assertFailsWith<BadRequestException> {
			blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, emptyMap(), null)
		}
	}

	/**
	 * The product's whole premise: a ticket mentioned in a page is *the same ticket*, so
	 * the block carries the id and the reader gets a live status pill rather than a
	 * sentence that was true when it was typed.
	 */
	@Test
	fun `a ticket link block resolves to the live ticket`() {
		val team = newTeam()
		val page = newPage(team.id)
		val ticket = tickets.create(
			admin, team.id, "Echo suppression", null, DefaultStatus.TODO,
			dev.kanso.domain.TicketPriority.NONE, null, null, null, emptyList(), emptyList(),
		)

		val block = blocks.linkTicket(admin, page.id, ticket.ticket.id, null)
		tickets.patch(admin, ticket.ticket.id, dev.kanso.service.TicketPatch(status = DefaultStatus.IN_PROGRESS))

		val detail = documents.page(page.id)
		assertEquals(DocBlockKind.TICKET_LINK, block.kind)
		assertEquals(listOf(ticket.ticket.id), block.ticketIds)
		assertEquals(DefaultStatus.IN_PROGRESS, detail.tickets.single().ticket.status)
	}

	/** `c` inside a document. Not the generic composer: the link is the point. */
	@Test
	fun `c creates a ticket already attached to the page`() {
		val team = newTeam()
		val page = newPage(team.id)

		val linked = blocks.createLinkedTicket(admin, page.id, "Persist the cursor per data source")

		val detail = documents.page(page.id)
		assertEquals("Persist the cursor per data source", linked.ticket.ticket.title)
		// The page receives a reference block…
		assertEquals(DocBlockKind.TICKET_LINK, detail.blocks.single().kind)
		assertEquals(listOf(linked.ticket.ticket.id), detail.blocks.single().ticketIds)
		// …and the ticket keeps the link.
		assertEquals(listOf(linked.ticket.ticket.id), detail.tickets.map { it.ticket.id })
	}

	@Test
	fun `deleting a page deletes the reference and neither ticket`() {
		val team = newTeam()
		val page = newPage(team.id)
		val linked = blocks.createLinkedTicket(admin, page.id, "Still here afterwards")

		documents.deletePage(admin, page.id)

		assertNotNull(tickets.get(linked.ticket.ticket.id))
	}

	@Test
	fun `editing a block records who touched the page and when`() {
		val team = newTeam()
		val page = newPage(team.id)
		val block = blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("draft"), null)

		blocks.updateBlock(admin, block.id, paragraph("settled"))

		val touched = documents.page(page.id).page
		assertEquals(admin.id, touched.editedById)
		assertTrue(touched.updatedAt >= page.updatedAt)
	}

	@Test
	fun `an outsider cannot write blocks into a claimed team's page`() {
		val team = newTeam()
		val member = users.createLocalUser(
			email = "member-${UUID.randomUUID()}@kanso.test",
			displayName = "Member",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.MEMBER,
		)
		val outsider = users.createLocalUser(
			email = "outsider-${UUID.randomUUID()}@kanso.test",
			displayName = "Outsider",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.MEMBER,
		)
		teams.addMember(admin, team.id, member.id, dev.kanso.domain.MemberRole.MEMBER)
		val page = newPage(team.id)

		assertFailsWith<org.springframework.security.access.AccessDeniedException> {
			blocks.addBlock(outsider, page.id, DocBlockKind.PARAGRAPH, paragraph("sneaky"), null)
		}
	}
}
