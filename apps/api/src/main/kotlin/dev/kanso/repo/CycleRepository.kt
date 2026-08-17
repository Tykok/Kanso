package dev.kanso.repo

import dev.kanso.db.Cycles
import dev.kanso.db.TicketCycles
import dev.kanso.db.Tickets
import dev.kanso.db.toTicket
import dev.kanso.domain.Ticket
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** A cycle as the database holds it — the state is still a raw string here. */
data class CycleRow(
	val id: UUID,
	val teamId: UUID,
	val number: Int,
	val startsOn: LocalDate,
	val endsOn: LocalDate,
	val state: String,
)

@Repository
class CycleRepository {

	fun findById(id: UUID): CycleRow? =
		Cycles.selectAll().where { Cycles.id eq id }.singleOrNull()?.toCycleRow()

	/** Newest number first: the sidebar reads downwards from the cycle in progress. */
	fun findByTeam(teamId: UUID): List<CycleRow> =
		Cycles.selectAll().where { Cycles.teamId eq teamId }
			.orderBy(Cycles.number to SortOrder.DESC)
			.map { it.toCycleRow() }

	fun findByTeamAndNumber(teamId: UUID, number: Int): CycleRow? =
		Cycles.selectAll().where { (Cycles.teamId eq teamId) and (Cycles.number eq number) }
			.singleOrNull()?.toCycleRow()

	fun findActive(teamId: UUID): CycleRow? =
		Cycles.selectAll().where { (Cycles.teamId eq teamId) and (Cycles.state eq "active") }
			.singleOrNull()?.toCycleRow()

	fun insert(
		id: UUID,
		teamId: UUID,
		number: Int,
		startsOn: LocalDate,
		endsOn: LocalDate,
		state: String,
	): CycleRow {
		val now = OffsetDateTime.now()
		Cycles.insert {
			it[Cycles.id] = id
			it[Cycles.teamId] = teamId
			it[Cycles.number] = number
			it[Cycles.startsOn] = startsOn
			it[Cycles.endsOn] = endsOn
			it[Cycles.state] = state
			it[createdAt] = now
			it[updatedAt] = now
		}
		return requireNotNull(findById(id))
	}

	fun updateState(id: UUID, state: String): Boolean =
		Cycles.update({ Cycles.id eq id }) { it[Cycles.state] = state } > 0

	// --- membership -----------------------------------------------------------

	/**
	 * Whole ticket rows for a cycle. Not `TicketRepository.search`: that filters by team
	 * and project, and a cycle is neither — its membership is a join table, and widening
	 * `search` with a `cycleId` would change a call every controller already makes.
	 *
	 * A subquery rather than a join, because `Tables.kt` declares foreign keys as plain
	 * columns and Exposed cannot infer a join condition from them. It is the same shape
	 * `TicketRepository.search` already uses for its assignee filter.
	 */
	fun ticketsIn(cycleId: UUID): List<Ticket> =
		Tickets.selectAll()
			.where {
				Tickets.id inSubQuery TicketCycles.select(TicketCycles.ticketId)
					.where { TicketCycles.cycleId eq cycleId }
			}
			.map { it.toTicket() }

	fun countIn(cycleIds: Collection<UUID>): Map<UUID, Int> =
		if (cycleIds.isEmpty()) emptyMap()
		else TicketCycles.select(TicketCycles.cycleId, TicketCycles.ticketId.count())
			.where { TicketCycles.cycleId inList cycleIds }
			.groupBy(TicketCycles.cycleId)
			.associate { it[TicketCycles.cycleId] to it[TicketCycles.ticketId.count()].toInt() }

	fun cycleIdOf(ticketId: UUID): UUID? =
		TicketCycles.select(TicketCycles.cycleId).where { TicketCycles.ticketId eq ticketId }
			.singleOrNull()?.get(TicketCycles.cycleId)

	/**
	 * Moves rather than adds: `ticket_cycles` is keyed on the ticket, so this is the one
	 * write that takes a ticket out of whichever cycle it was in. Deleting first rather
	 * than upserting keeps `added_at` honest — a ticket carried into the next cycle
	 * joined it today, not whenever it first entered the previous one.
	 */
	fun place(ticketIds: Collection<UUID>, cycleId: UUID) {
		if (ticketIds.isEmpty()) return
		TicketCycles.deleteWhere { TicketCycles.ticketId inList ticketIds }
		TicketCycles.batchInsert(ticketIds.distinct()) { ticketId ->
			this[TicketCycles.ticketId] = ticketId
			this[TicketCycles.cycleId] = cycleId
			this[TicketCycles.addedAt] = OffsetDateTime.now()
		}
	}

	fun remove(ticketIds: Collection<UUID>) {
		if (ticketIds.isEmpty()) return
		TicketCycles.deleteWhere { TicketCycles.ticketId inList ticketIds }
	}
}

private fun ResultRow.toCycleRow() = CycleRow(
	id = this[Cycles.id],
	teamId = this[Cycles.teamId],
	number = this[Cycles.number],
	startsOn = this[Cycles.startsOn],
	endsOn = this[Cycles.endsOn],
	state = this[Cycles.state],
)
