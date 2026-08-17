package dev.kanso.repo

import dev.kanso.db.Labels
import dev.kanso.db.TicketLabels
import dev.kanso.domain.Accent
import dev.kanso.domain.Label
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class LabelRepository {

	fun findById(id: UUID): Label? =
		Labels.selectAll().where { Labels.id eq id }.singleOrNull()?.toLabel()

	/** By name, because the only place this is drawn is a list somebody reads. */
	fun forTeam(teamId: UUID): List<Label> =
		Labels.selectAll().where { Labels.teamId eq teamId }
			.orderBy(Labels.name to SortOrder.ASC)
			.map { it.toLabel() }

	/** Exactly the comparison `UNIQUE (team_id, name)` makes, so the pre-check cannot miss. */
	fun findByName(teamId: UUID, name: String): Label? =
		Labels.selectAll().where { (Labels.teamId eq teamId) and (Labels.name eq name) }
			.singleOrNull()?.toLabel()

	fun insert(id: UUID, teamId: UUID, name: String, colour: Accent): Label {
		Labels.insert {
			it[Labels.id] = id
			it[Labels.teamId] = teamId
			it[Labels.name] = name
			it[Labels.colour] = colour.wire
		}
		return requireNotNull(findById(id))
	}

	fun delete(id: UUID): Boolean = Labels.deleteWhere { Labels.id eq id } > 0

	// --- what a ticket wears -------------------------------------------------

	// The join names its columns: `Tables.kt` declares foreign keys as plain columns, so
	// Exposed has no reference to infer one from.
	fun forTicket(ticketId: UUID): List<Label> =
		TicketLabels.innerJoin(Labels, { TicketLabels.labelId }, { Labels.id })
			.selectAll()
			.where { TicketLabels.ticketId eq ticketId }
			.orderBy(Labels.name to SortOrder.ASC)
			.map { it.toLabel() }

	/** Idempotent: pressing the same pill twice is one label, not an error. */
	fun attach(ticketId: UUID, labelId: UUID) {
		TicketLabels.insertIgnore {
			it[TicketLabels.ticketId] = ticketId
			it[TicketLabels.labelId] = labelId
		}
	}

	fun detach(ticketId: UUID, labelId: UUID): Boolean =
		TicketLabels.deleteWhere {
			(TicketLabels.ticketId eq ticketId) and (TicketLabels.labelId eq labelId)
		} > 0

	private fun ResultRow.toLabel() = Label(
		id = this[Labels.id],
		teamId = this[Labels.teamId],
		name = this[Labels.name],
		colour = Accent.from(this[Labels.colour]),
	)
}
