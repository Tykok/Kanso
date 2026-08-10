package dev.kanso.service

import dev.kanso.domain.KansoInstant
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketStatus
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.schedule.CriticalPath
import dev.kanso.schedule.Edge
import dev.kanso.schedule.Node
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class TimelineProject(
	val id: UUID,
	val name: String,
	val start: KansoInstant?,
	val end: KansoInstant?,
	val startDerived: Boolean,
	val endDerived: Boolean,
)

data class TimelineTicket(
	val id: UUID,
	val identifier: String,
	val title: String,
	val projectId: UUID?,
	val status: TicketStatus,
	val start: KansoInstant?,
	val due: KansoInstant?,
	/** Null for an unscheduled ticket or one with no dependencies. */
	val slackMinutes: Long?,
	val critical: Boolean,
	val late: Boolean,
)

data class TimelineEdge(
	val predecessorId: UUID,
	val successorId: UUID,
	val violated: Boolean,
	/** True when the other end is absent from this response — the view draws a stub. */
	val outOfScope: Boolean,
)

data class TimelineUnscheduled(val id: UUID, val identifier: String, val title: String)

data class TimelineView(
	val projects: List<TimelineProject>,
	val tickets: List<TimelineTicket>,
	val dependencies: List<TimelineEdge>,
	val unscheduled: List<TimelineUnscheduled>,
)

/**
 * One read for the whole screen: derived bounds, slack and criticality are computed
 * together over the same component closure, and splitting them across endpoints would
 * mean walking that closure three times.
 */
@Service
class TimelineService(
	private val tickets: TicketRepository,
	private val projects: ProjectRepository,
	private val teams: TeamRepository,
	private val dependencies: DependencyRepository,
) {

	@Transactional(readOnly = true)
	fun load(teamId: UUID?, projectId: UUID?): TimelineView {
		val teamIds = teamId?.let { teams.descendantIds(it) }
		val inScope = tickets.search(
			teamIds = teamIds,
			projectId = projectId,
			includeArchived = false,
			limit = SCOPE_LIMIT,
		)
		val scopeIds = inScope.map { it.id }.toSet()

		// The closure, not the scope: anchoring the critical path on what happens to be
		// visible would repaint identical data when the filter changes.
		val componentIds = dependencies.componentIds(scopeIds)
		val graphTickets = tickets.findAllById(componentIds)
		val edges = dependencies.edgesTouching(componentIds)

		// A deadline is the *explicit* end of a ticket's own project — a derived bound is
		// a consequence of the tickets, so treating it as a constraint on them would make
		// every chain critical by construction.
		val projectEnds = projects
			.findAllById(graphTickets.mapNotNull { it.projectId }.toSet())
			.mapNotNull { project -> project.end?.let { project.id to it.at } }
			.toMap()
		val deadlines = graphTickets.mapNotNull { ticket ->
			ticket.projectId?.let { projectEnds[it] }?.let { ticket.id to it }
		}.toMap()

		val byId = graphTickets.associateBy { it.id }
		val slack = CriticalPath.slack(graphTickets.map(::toNode), edges, deadlines)
		val violated = violatedEdges(byId, edges)

		// One query for every team on screen rather than one per row — the scope crosses
		// teams whenever the filter is a parent team.
		val keys = teams.findAllById(inScope.map { it.teamId }.toSet()).associate { it.id to it.key }
		fun identifier(ticket: Ticket) = "${keys[ticket.teamId] ?: "?"}-${ticket.number}"

		return TimelineView(
			projects = projectRows(inScope, projectId, teamIds),
			tickets = inScope.filter { it.start != null || it.due != null }.map { ticket ->
				val minutes = slack[ticket.id]?.toMinutes()
				TimelineTicket(
					id = ticket.id,
					identifier = identifier(ticket),
					title = ticket.title,
					projectId = ticket.projectId,
					status = ticket.status,
					start = ticket.start,
					due = ticket.due,
					slackMinutes = minutes,
					critical = minutes == 0L,
					late = minutes != null && minutes < 0L,
				)
			},
			dependencies = edges
				.filter { it.predecessorId in scopeIds || it.successorId in scopeIds }
				.map {
					TimelineEdge(
						predecessorId = it.predecessorId,
						successorId = it.successorId,
						violated = it in violated,
						outOfScope = it.predecessorId !in scopeIds || it.successorId !in scopeIds,
					)
				},
			unscheduled = inScope.filter { it.start == null && it.due == null }
				.map { TimelineUnscheduled(it.id, identifier(it), it.title) },
		)
	}

	/**
	 * An edge is violated when its predecessor ends after its successor starts and the
	 * cascade could not repair it — which happens exactly when the successor is done.
	 */
	private fun violatedEdges(byId: Map<UUID, Ticket>, edges: List<Edge>): Set<Edge> =
		edges.filterTo(mutableSetOf()) { edge ->
			val predecessorEnd = byId[edge.predecessorId]?.let { it.due?.at ?: it.start?.at }
			val successor = byId[edge.successorId]
			val successorStart = successor?.let { it.start?.at ?: it.due?.at }
			predecessorEnd != null && successorStart != null &&
				successor.status == TicketStatus.DONE && successorStart.isBefore(predecessorEnd)
		}

	/**
	 * Bounds resolve per bound independently: an explicit date, else the tickets'
	 * planned dates, else when the done ones were completed, else no bar. The third
	 * rule is retrospective by construction — a worse answer than a plan, a better one
	 * than a blank row.
	 */
	private fun projectRows(
		inScope: List<Ticket>,
		projectId: UUID?,
		teamIds: List<UUID>?,
	): List<TimelineProject> {
		val candidates = when {
			projectId != null -> projects.findAllById(setOf(projectId))
			else -> projects.search(teamIds, includeArchived = false)
		}
		val byProject = inScope.groupBy { it.projectId }

		return candidates.map { project ->
			val members = byProject[project.id].orEmpty()
			val plannedStart = members.mapNotNull { it.start?.at ?: it.due?.at }
			val plannedEnd = members.mapNotNull { it.due?.at ?: it.start?.at }
			val completed = members.mapNotNull { it.completedAt }

			val derivedStart = plannedStart.minOrNull() ?: completed.minOrNull()
			val derivedEnd = plannedEnd.maxOrNull() ?: completed.maxOrNull()

			TimelineProject(
				id = project.id,
				name = project.name,
				// A derived bound is floating: it is a day read off the tickets, not a
				// moment anyone posed, so converting it would move the bar for a reader
				// west of UTC on data nobody touched.
				start = project.start ?: derivedStart?.let { KansoInstant(it, false) },
				end = project.end ?: derivedEnd?.let { KansoInstant(it, false) },
				startDerived = project.start == null,
				endDerived = project.end == null,
			)
		}
	}

	private fun toNode(ticket: Ticket) = Node(
		id = ticket.id,
		start = ticket.start?.at,
		end = ticket.due?.at,
		done = ticket.status == TicketStatus.DONE,
	)

	private companion object {
		/**
		 * A timeline is drawn, not paged: there is no "next page" gesture on a Gantt. The
		 * cap exists so a pathological instance returns a large response rather than the
		 * whole database, and is well above what a team's board holds.
		 */
		const val SCOPE_LIMIT = 2000
	}
}
