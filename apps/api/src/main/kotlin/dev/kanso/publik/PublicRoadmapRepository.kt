package dev.kanso.publik

import dev.kanso.db.Labels
import dev.kanso.db.PublicTickets
import dev.kanso.db.Teams
import dev.kanso.db.TicketAssignees
import dev.kanso.db.TicketFiles
import dev.kanso.db.TicketLabels
import dev.kanso.db.Votes
import dev.kanso.domain.TicketStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** A published ticket as the database holds it, before votes are counted onto it. */
data class PublishedRow(
	val id: UUID,
	val identifier: String,
	val teamId: UUID,
	val title: String,
	val description: String?,
	val status: TicketStatus,
	val completedAt: OffsetDateTime?,
	val unclaimed: Boolean,
)

/**
 * The only place in the application that reads tickets for a reader with no session.
 *
 * Every query here names `isPublic eq true` and `archived eq false`, and there is no
 * parameter that could turn either off — a boolean argument called `includePrivate`
 * defaulting to false is exactly the shape of the mistake this slice is written to
 * prevent. A caller that wants a private ticket has to use a different repository,
 * which is a different security decision made in a different file.
 */
@Repository
class PublicRoadmapRepository {

	private val published get() = (PublicTickets.isPublic eq true) and (PublicTickets.archived eq false)

	/**
	 * Nobody is assigned. `notExists` rather than a left join and a null check: a ticket
	 * with two assignees would otherwise arrive as two rows and be counted twice.
	 */
	private val unclaimed = notExists(
		TicketAssignees.select(TicketAssignees.ticketId)
			.where { TicketAssignees.ticketId eq PublicTickets.id }
	)

	/** Every published ticket in these statuses, newest work first inside each. */
	fun findPublished(statuses: Collection<TicketStatus>, limit: Int): List<PublishedRow> =
		PublicTickets.join(Teams, JoinType.INNER, PublicTickets.teamId, Teams.id)
			.select(
				PublicTickets.id,
				PublicTickets.number,
				PublicTickets.teamId,
				PublicTickets.title,
				PublicTickets.description,
				PublicTickets.status,
				PublicTickets.completedAt,
				Teams.key,
				unclaimed,
			)
			.where { published and (PublicTickets.status inList statuses.map { it.wire }) }
			.limit(limit)
			.map {
				PublishedRow(
					id = it[PublicTickets.id],
					identifier = "${it[Teams.key]}-${it[PublicTickets.number]}",
					teamId = it[PublicTickets.teamId],
					title = it[PublicTickets.title],
					description = it[PublicTickets.description],
					status = TicketStatus.from(it[PublicTickets.status]),
					completedAt = it[PublicTickets.completedAt],
					unclaimed = it[unclaimed],
				)
			}

	/**
	 * One published ticket by the identifier people paste at each other, or null.
	 *
	 * Null covers both "no such ticket" and "that ticket is not published", on purpose:
	 * telling a stranger which of the two it was is telling them the ticket exists.
	 */
	fun findPublishedByKey(teamKey: String, number: Int): PublishedRow? =
		PublicTickets.join(Teams, JoinType.INNER, PublicTickets.teamId, Teams.id)
			.select(
				PublicTickets.id,
				PublicTickets.number,
				PublicTickets.teamId,
				PublicTickets.title,
				PublicTickets.description,
				PublicTickets.status,
				PublicTickets.completedAt,
				Teams.key,
				unclaimed,
			)
			.where { published and (Teams.key eq teamKey.uppercase()) and (PublicTickets.number eq number) }
			.map {
				PublishedRow(
					id = it[PublicTickets.id],
					identifier = "${it[Teams.key]}-${it[PublicTickets.number]}",
					teamId = it[PublicTickets.teamId],
					title = it[PublicTickets.title],
					description = it[PublicTickets.description],
					status = TicketStatus.from(it[PublicTickets.status]),
					completedAt = it[PublicTickets.completedAt],
					unclaimed = it[unclaimed],
				)
			}
			.singleOrNull()

	/** Votes per ticket, for the ids on screen. One query, not one per row. */
	fun voteCounts(ticketIds: Collection<UUID>): Map<UUID, Int> {
		if (ticketIds.isEmpty()) return emptyMap()
		val votes = Votes.voterKey.count()
		return Votes.select(Votes.ticketId, votes)
			.where { Votes.ticketId inList ticketIds }
			.groupBy(Votes.ticketId)
			.associate { it[Votes.ticketId] to it[votes].toInt() }
	}

	/**
	 * The names of the labels a *published* ticket wears, for screen 28's badges.
	 *
	 * Names only, and joined back through the public projection rather than reading
	 * `ticket_labels` on its own: an id would let a visitor line a label up against any
	 * endpoint that ever leaks one, and a query keyed on ticket id alone would answer for
	 * a private ticket the day somebody called it with one. The colour is left behind —
	 * neither screen 21's chip nor screen 28's badge draws one.
	 */
	fun labelNames(ticketId: UUID): List<String> =
		PublicTickets
			.join(TicketLabels, JoinType.INNER, PublicTickets.id, TicketLabels.ticketId)
			.join(Labels, JoinType.INNER, TicketLabels.labelId, Labels.id)
			.select(Labels.name)
			.where { published and (PublicTickets.id eq ticketId) }
			.orderBy(Labels.name to SortOrder.ASC)
			.map { it[Labels.name] }

	/**
	 * Has any team defined a label by this name?
	 *
	 * Asked separately from [idsLabelled] because the two answer different questions: a
	 * team that defined `good first step` and has marked nothing with it yet is saying
	 * there are none, and that is not the same as an instance where nobody ever heard of
	 * the idea. About a label, not about a ticket, so it reads no ticket at all.
	 */
	fun labelDefined(name: String): Boolean =
		Labels.selectAll().where { Labels.name eq name }.limit(1).any()

	/** The published tickets wearing a label of this name, in whichever team owns it. */
	fun idsLabelled(name: String): Set<UUID> =
		PublicTickets
			.join(TicketLabels, JoinType.INNER, PublicTickets.id, TicketLabels.ticketId)
			.join(Labels, JoinType.INNER, TicketLabels.labelId, Labels.id)
			.select(PublicTickets.id)
			.where { published and (Labels.name eq name) }
			.mapTo(mutableSetOf()) { it[PublicTickets.id] }

	fun whereToLook(ticketId: UUID): List<FilePointer> =
		TicketFiles.selectAll()
			.where { TicketFiles.ticketId eq ticketId }
			.orderBy(TicketFiles.position to SortOrder.ASC)
			.map { FilePointer(it[TicketFiles.path], it[TicketFiles.note]) }
}
