package dev.kanso.repo

import dev.kanso.db.TicketCycles
import dev.kanso.db.Tickets
import dev.kanso.db.TriageDecisions
import dev.kanso.db.toTicket
import dev.kanso.domain.Ticket
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

data class TriageDecisionRow(
	val ticketId: UUID,
	val decision: String,
	val duplicateOf: UUID?,
	val decidedBy: UUID?,
	val decidedAt: OffsetDateTime,
)

/** A ticket that reads like another one, with the trigram score Postgres computed. */
data class SimilarRow(val ticketId: UUID, val similarity: Int)

@Repository
class TriageRepository(private val jdbc: JdbcClient) {

	/**
	 * The queue: unarchived tickets in these teams that nobody has ruled on and that are
	 * in no cycle, oldest first.
	 *
	 * Membership is the absence of a decision rather than a flag on the ticket. A flag
	 * would need a writer for every route that can create a ticket — the composer, the
	 * mirror poller, a future contributor form — and one that forgot would drop work on
	 * the floor silently. There is nothing to forget here.
	 *
	 * `done` and `canceled` are excluded: a ticket somebody already finished or dropped
	 * has been decided about, whatever the triage table says.
	 */
	fun queue(teamIds: Collection<UUID>, limit: Int): List<Ticket> {
		if (teamIds.isEmpty()) return emptyList()
		return Tickets.selectAll()
			.where {
				(Tickets.teamId inList teamIds) and
					(Tickets.archived eq false) and
					(Tickets.status notInList listOf("done", "canceled")) and
					(Tickets.id notInSubQuery TriageDecisions.select(TriageDecisions.ticketId)) and
					(Tickets.id notInSubQuery TicketCycles.select(TicketCycles.ticketId))
			}
			.orderBy(Tickets.createdAt to SortOrder.ASC)
			.limit(limit)
			.map { it.toTicket() }
	}

	fun countQueued(teamIds: Collection<UUID>): Int {
		if (teamIds.isEmpty()) return 0
		return Tickets.selectAll()
			.where {
				(Tickets.teamId inList teamIds) and
					(Tickets.archived eq false) and
					(Tickets.status notInList listOf("done", "canceled")) and
					(Tickets.id notInSubQuery TriageDecisions.select(TriageDecisions.ticketId)) and
					(Tickets.id notInSubQuery TicketCycles.select(TicketCycles.ticketId))
			}
			.count().toInt()
	}

	fun findDecision(ticketId: UUID): TriageDecisionRow? =
		TriageDecisions.selectAll().where { TriageDecisions.ticketId eq ticketId }
			.singleOrNull()?.toDecisionRow()

	/** The trace, newest first — what was closed, and by whom. */
	fun decisionsFor(teamIds: Collection<UUID>, limit: Int): List<TriageDecisionRow> {
		if (teamIds.isEmpty()) return emptyList()
		return TriageDecisions.selectAll()
			.where {
				TriageDecisions.ticketId inSubQuery Tickets.select(Tickets.id)
					.where { Tickets.teamId inList teamIds }
			}
			.orderBy(TriageDecisions.decidedAt to SortOrder.DESC)
			.limit(limit)
			.map { it.toDecisionRow() }
	}

	fun insertDecision(
		ticketId: UUID,
		decision: String,
		duplicateOf: UUID?,
		decidedBy: UUID?,
	): TriageDecisionRow {
		TriageDecisions.insert {
			it[TriageDecisions.ticketId] = ticketId
			it[TriageDecisions.decision] = decision
			it[TriageDecisions.duplicateOf] = duplicateOf
			it[TriageDecisions.decidedBy] = decidedBy
			it[decidedAt] = OffsetDateTime.now()
		}
		return requireNotNull(findDecision(ticketId))
	}

	/**
	 * "Looks like KAN-142 — 68 %", computed on read.
	 *
	 * Raw SQL because `similarity()` is a `pg_trgm` function the Exposed DSL cannot
	 * express, which is the same reason the six statements `architecture.md` lists are
	 * raw. It runs on the connection Spring already holds, inside the caller's
	 * transaction.
	 *
	 * The `%` operator does the index lookup and `similarity()` only scores what survived
	 * it — writing the threshold as `similarity(...) >= :floor` instead would score every
	 * title in the instance before filtering, which is the sequential scan
	 * `tickets_title_trgm_idx` exists to avoid. `%` answers against
	 * `pg_trgm.similarity_threshold`, 0.3 by default, so the floor is applied twice on
	 * purpose: the index decides what is worth scoring, the `HAVING`-style predicate
	 * decides what is worth showing.
	 */
	fun similarTitles(ticketId: UUID, title: String, teamIds: Collection<UUID>, limit: Int): List<SimilarRow> {
		if (teamIds.isEmpty()) return emptyList()
		return jdbc.sql(
			"""
			SELECT id, round(similarity(title, :title) * 100) AS score
			  FROM tickets
			 WHERE id <> :self
			   AND team_id IN (:teams)
			   AND NOT archived
			   AND title % :title
			 ORDER BY score DESC, created_at ASC
			 LIMIT :limit
			""".trimIndent()
		)
			.param("title", title)
			.param("self", ticketId)
			.param("teams", teamIds.toList())
			.param("limit", limit)
			.query { rs, _ -> SimilarRow(rs.getObject("id", UUID::class.java), rs.getInt("score")) }
			.list()
	}
}

private fun ResultRow.toDecisionRow() = TriageDecisionRow(
	ticketId = this[TriageDecisions.ticketId],
	decision = this[TriageDecisions.decision],
	duplicateOf = this[TriageDecisions.duplicateOf],
	decidedBy = this[TriageDecisions.decidedBy],
	decidedAt = this[TriageDecisions.decidedAt],
)
