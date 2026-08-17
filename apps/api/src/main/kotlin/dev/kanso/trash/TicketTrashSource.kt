package dev.kanso.trash

import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.service.TicketService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The one kind that has a table on this branch.
 *
 * Thin on purpose: the three exits go through [TicketService], which is where the
 * `TicketAccess` rule and the mirror bookkeeping for a ticket already live, so this file
 * adds no second answer to "who may throw a ticket away". What is genuinely its own is the
 * read: a label somebody recognises, the parent a restore names, and what the ticket holds.
 */
@Component
class TicketTrashSource(
	private val tickets: TicketService,
	private val rows: TicketRepository,
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
) : TrashSource {

	override val kind = TrashKind.TICKET

	/** The one kind with an `archived` column, and so the one with a middle exit. */
	override val archivable = true

	override fun describe(ids: Collection<UUID>): List<TrashItem> = itemsOf(rows.findTrashed(ids))

	override fun archived(limit: Int): List<TrashItem> = itemsOf(rows.findArchived(limit))

	override fun restore(actor: User, id: UUID) = tickets.restore(actor, id)

	override fun archiveInstead(actor: User, id: UUID) = tickets.archiveFromTrash(actor, id)

	override fun purge(actor: User?, id: UUID) = tickets.purge(actor, id)

	/**
	 * One query per relation for the whole set rather than two per row, the same rule
	 * `TicketService.decorate` follows.
	 */
	private fun itemsOf(found: List<Ticket>): List<TrashItem> {
		if (found.isEmpty()) return emptyList()
		val keys = teams.findAllById(found.map { it.teamId }.toSet()).associate { it.id to it }
		val projectsById = projects.findAllById(found.mapNotNull { it.projectId }.toSet()).associateBy { it.id }
		val docsByTicket = rows.docIdsFor(found.map { it.id })

		return found.map { ticket ->
			val team = keys[ticket.teamId]
			val project = ticket.projectId?.let(projectsById::get)
			TrashItem(
				kind = kind,
				id = ticket.id,
				// The identifier is how anybody refers to a ticket out loud, and the title
				// alone would leave two rows called "Old composer" indistinguishable.
				label = "${team?.key ?: "?"}-${ticket.number} · ${ticket.title}",
				// A project when it has one, its team otherwise — a ticket always has a
				// team, so this is never absent and "Restore" never has to say "somewhere".
				parent = when {
					project != null -> TrashParent("project", project.id, project.name)
					team != null -> TrashParent("team", team.id, team.name)
					else -> null
				},
				holds = holdingsOf(docsByTicket[ticket.id].orEmpty().size),
			)
		}
	}

	/**
	 * The drawing's own sentence, from this end of it: a ticket and a document that
	 * mention each other are two things, and destroying one takes the reference and never
	 * the other thing. `cascades = false` is what the pane reads to say so, rather than a
	 * sentence somebody typed into a component.
	 *
	 * A ticket reports nothing else today. `BLOCKS` and `MENTIONED_TICKETS` are the doc
	 * page's, and belong to whoever lands `doc_pages`.
	 */
	private fun holdingsOf(linkedDocs: Int): List<TrashHolding> =
		if (linkedDocs == 0) emptyList()
		else listOf(TrashHolding(TrashHoldingKind.LINKED_DOCS, linkedDocs, cascades = false))
}
