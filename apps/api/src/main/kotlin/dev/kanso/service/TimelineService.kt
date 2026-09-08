package dev.kanso.service

import dev.kanso.domain.KansoInstant
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.Ticket
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
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
	/** Whose work this is — a context row is drawn under its owner's key, not the reader's. */
	val teamKey: String,
	val projectId: UUID?,
	val status: String,
	val start: KansoInstant?,
	val due: KansoInstant?,
	/** Null for an unscheduled ticket or one with no dependencies. */
	val slackMinutes: Long?,
	val critical: Boolean,
	val late: Boolean,
	/** Outside the filter the reader asked for: drawn because it explains their dates. */
	val context: Boolean,
	/** May this reader move it — the same rule the mutations enforce, answered once here. */
	val editable: Boolean,
)

data class TimelineEdge(
	val predecessorId: UUID,
	val successorId: UUID,
	/**
	 * The cascade cannot repair this edge, which happens exactly when the successor is
	 * done. Distinct from [overlap] on purpose: "it is finished, too late" and "move the
	 * successor and it is fixed" are different sentences, and one red state would print
	 * only the first.
	 */
	val violated: Boolean,
	/** Broken now, and repairable: the successor starts before the predecessor ends and is not done. */
	val overlap: Boolean,
	/**
	 * True when the other end is absent from this response — the view draws a stub. Rare
	 * now that the closure comes back as context: what is left is an end that is archived
	 * or past a cap, which is the only kind still worth a stub.
	 */
	val outOfScope: Boolean,
)

data class TimelineUnscheduled(val id: UUID, val identifier: String, val title: String)

data class TimelineView(
	val projects: List<TimelineProject>,
	val tickets: List<TimelineTicket>,
	val dependencies: List<TimelineEdge>,
	val unscheduled: List<TimelineUnscheduled>,
	/** A cap was hit, so the drawing is incomplete — there is no next page to offer instead. */
	val truncated: Boolean,
)

/**
 * One read for the whole screen: derived bounds, slack and criticality are computed
 * together over the same component closure, and splitting them across endpoints would
 * mean walking that closure three times.
 *
 * Three sets, and keeping them apart is most of this file: the scope the reader filtered
 * for, the closure the schedule is computed over, and what is drawn — the scope plus the
 * context that explains its dates. They were one set before; a chain that leaves the team
 * is the normal case, and drawing only your own half of it printed dates with their
 * causes cut off.
 */
@Service
class TimelineService(
	private val tickets: TicketRepository,
	private val projects: ProjectRepository,
	private val teams: TeamRepository,
	private val dependencies: DependencyRepository,
	private val access: TicketAccess,
	private val statusCategories: StatusCategories,
) {

	@Transactional(readOnly = true)
	fun load(actor: User, teamId: UUID?, projectId: UUID?): TimelineView {
		val teamIds = teamId?.let { teams.descendantIds(it) }
		val own = tickets.search(
			teamIds = teamIds,
			projectId = projectId,
			includeArchived = false,
			limit = SCOPE_LIMIT,
		)
		val ownIds = own.map { it.id }.toSet()

		// The closure, not the scope: anchoring the critical path on what happens to be
		// visible would repaint identical data when the filter changes.
		val componentIds = dependencies.componentIds(ownIds)
		val graphTickets = tickets.findAllById(componentIds)
		val edges = dependencies.edgesTouching(componentIds)

		// Who else is working in the projects this scope has work in. The widening the
		// reader asked for, and the part of the response most able to surprise: a shared
		// project pulls in another team's whole board.
		val shared = tickets.findByProjectIds(
			own.mapNotNull { it.projectId }.toSet(),
			limit = SCOPE_LIMIT,
		)

		// A ticket with no team is drawn on nobody's timeline. `own` never holds one — the
		// one predicate excludes them — but these two do not go through it: a transverse
		// project can hold a draft, and so can a dependency chain. A Gantt row is labelled
		// with an identifier and coloured by whose work it is, and a draft has neither; it
		// is also private to its author, and this is a shared drawing. It stays in
		// `graphTickets` for the slack maths below, where it is a duration rather than a row,
		// and its edges come back flagged `outOfScope`, which is exactly what they are.
		val context = (shared + graphTickets)
			.filter { it.id !in ownIds && !it.archived && it.teamId != null }
			.distinctBy { it.id }
		val drawn = own + context
		val truncated = own.size >= SCOPE_LIMIT || shared.size >= SCOPE_LIMIT

		// A bar needs a date, and this is the same predicate `Node.scheduled` uses — one
		// spelling of "drawable", so the arrows and the scheduler cannot disagree.
		val bars = drawn.filter { it.start != null || it.due != null }

		// What the client can actually resolve, which is not the same as [drawn]: every
		// `own` ticket is somewhere in the response — dated ones as bars, undated ones in
		// the tray — but an undated *context* ticket is in neither list. Computing
		// `outOfScope` over `drawn` would clear the flag on an edge whose far end has no
		// row anywhere, which is the one case the flag exists to announce.
		val presentIds = ownIds + bars.map { it.id }

		// A deadline is the *explicit* end of a ticket's own project — a derived bound is
		// a consequence of the tickets, so treating it as a constraint on them would make
		// every chain critical by construction.
		//
		// Deliberately still the closure and not [drawn]: a deadline only ever tightens a
		// late finish, so posting one for a ticket that has no dependencies would be a
		// constraint nothing reads, and widening it is how a shared project's end would
		// start binding chains that never entered it.
		val projectEnds = projects
			.findAllById(graphTickets.mapNotNull { it.projectId }.toSet())
			.mapNotNull { project -> project.end?.let { project.id to it.at } }
			.toMap()
		val deadlines = graphTickets.mapNotNull { ticket ->
			ticket.projectId?.let { projectEnds[it] }?.let { ticket.id to it }
		}.toMap()

		// The union, both ways round. A ticket can be in the closure without being drawn
		// (archived, or beyond a cap) and is now also drawn without being in the closure
		// (a shared project's work with no arrows). Dropping the first kind would delete
		// the far end of an edge, `CriticalPath` would discard that edge as unusable, and
		// the chain would measure shorter than it is — exactly the narrowing the closure
		// exists to prevent. The second kind costs nothing: a node no edge touches never
		// reaches a component, so it gets no slack rather than a wrong one.
		val nodes = (graphTickets + drawn).distinctBy { it.id }
		val byId = nodes.associateBy { it.id }
		// One read for every team on the timeline, beside the one that resolves their keys
		// below: a Gantt filtered on a parent team draws several vocabularies at once, and
		// "is this bar done" is a question about each row's own team.
		val categories = statusCategories.of(nodes)
		val slack = CriticalPath.slack(nodes.map { toNode(it, categories) }, edges, deadlines)
		val broken = brokenEdges(byId, edges, categories)

		// One query for every team on screen rather than one per row — the scope crosses
		// teams whenever the filter is a parent team, and a context row prints the key of
		// whoever owns it rather than the reader's.
		val keys = teams.findAllById(drawn.mapNotNull { it.teamId }.toSet()).associate { it.id to it.key }
		fun identifier(ticket: Ticket) = "${keys[ticket.teamId] ?: "?"}-${ticket.number}"


		// One call for every team drawn: the rule costs a couple of queries per distinct
		// team, and asking it per ticket would be two thousand ancestor walks.
		val editableTeams = access.editableTeams(actor, drawn.mapNotNull { it.teamId }.toSet())

		return TimelineView(
			projects = projectRows(own, drawn, projectId, teamIds),
			tickets = bars.map { ticket ->
				val minutes = slack[ticket.id]?.toMinutes()
				TimelineTicket(
					id = ticket.id,
					identifier = identifier(ticket),
					title = ticket.title,
					teamKey = keys[ticket.teamId] ?: "?",
					projectId = ticket.projectId,
					status = ticket.status,
					start = ticket.start,
					due = ticket.due,
					slackMinutes = minutes,
					critical = minutes == 0L,
					late = minutes != null && minutes < 0L,
					context = ticket.id !in ownIds,
					// The server's answer, so the client has no rule to re-derive and
					// no membership graph to hold.
					editable = ticket.teamId in editableTeams,
				)
			},
			dependencies = edges
				.filter { it.predecessorId in presentIds || it.successorId in presentIds }
				.map {
					val unrepairable = broken[it]
					TimelineEdge(
						predecessorId = it.predecessorId,
						successorId = it.successorId,
						violated = unrepairable == true,
						overlap = unrepairable == false,
						outOfScope = it.predecessorId !in presentIds || it.successorId !in presentIds,
					)
				},
			// The scope's own undated work only: the tray is where *your* tickets wait for
			// a date, and other teams' would make it a list the reader cannot empty.
			unscheduled = own.filter { it.start == null && it.due == null }
				.map { TimelineUnscheduled(it.id, identifier(it), it.title) },
			truncated = truncated,
		)
	}

	/**
	 * Which edges are broken, and which kind of broken.
	 *
	 * This deliberately reports more than [dev.kanso.schedule.Cascade] does, and the
	 * divergence must survive review. The two answer different questions: the cascade
	 * reports what *this request* could not repair, and skips any node none of whose
	 * predecessors moved — so dragging a successor backwards under its own predecessor
	 * never examines that edge at all. This reports what is broken *now*, including
	 * breakage that predates every request.
	 */
	private fun brokenEdges(byId: Map<UUID, Ticket>, edges: List<Edge>, categories: Categories): Map<Edge, Boolean> =
		edges.mapNotNull { edge ->
			val predecessorEnd = byId[edge.predecessorId]?.let { it.due?.at ?: it.start?.at }
			val successor = byId[edge.successorId]
			val successorStart = successor?.let { it.start?.at ?: it.due?.at }
			if (predecessorEnd == null || successorStart == null) return@mapNotNull null
			if (!successorStart.isBefore(predecessorEnd)) return@mapNotNull null
			// The value is "is this one the cascade cannot repair".
			edge to (categories[successor] == StatusCategory.COMPLETED)
		}.toMap()

	/**
	 * Bounds resolve per bound independently: an explicit date, else the tickets'
	 * planned dates, else when the done ones were completed, else no bar. The third
	 * rule is retrospective by construction — a worse answer than a plan, a better one
	 * than a blank row.
	 *
	 * Derived from the scope's own tickets, not from the context rows drawn inside the
	 * same project: a bound is a statement about a project, and letting another team's
	 * dates move it would make the bar answer to work the reader cannot touch.
	 *
	 * Which is not the same question as *which* projects get a row. Every project a drawn
	 * row points at gets one, because a team filter alone cannot find the projects that
	 * matter most here: `search` matches `team_id IN (…)`, and a transverse project has a
	 * null team, which SQL's `IN` never matches. A transverse project is also the only
	 * legal shape of a shared one — `TicketService` refuses a ticket whose project belongs
	 * to another team — so without this union the headline case of the widening returns
	 * rows whose project has no name and no bar anywhere in the response.
	 */
	private fun projectRows(
		own: List<Ticket>,
		drawn: List<Ticket>,
		projectId: UUID?,
		teamIds: List<UUID>?,
	): List<TimelineProject> {
		val filtered = when {
			projectId != null -> projects.findAllById(setOf(projectId))
			else -> projects.search(teamIds, includeArchived = false)
		}
		// Archived ones stay out: they are absent from the filtered set by the same rule,
		// and a row's project going to the archive is not a reason to redraw it here.
		val referenced = projects
			.findAllById(drawn.mapNotNull { it.projectId }.toSet())
			.filter { !it.archived }
		val candidates = (filtered + referenced).distinctBy { it.id }
		val byProject = own.groupBy { it.projectId }

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

	private fun toNode(ticket: Ticket, categories: Categories) = Node(
		id = ticket.id,
		start = ticket.start?.at,
		end = ticket.due?.at,
		done = categories[ticket] == StatusCategory.COMPLETED,
	)

	private companion object {
		/**
		 * A timeline is drawn, not paged: there is no "next page" gesture on a Gantt. This
		 * caps `own` and `shared` — the scope the reader filtered for, and the widening
		 * into shared projects — each independently, at well above what a team's board
		 * holds.
		 *
		 * It does not cap `graphTickets`, the dependency closure: `truncated` cannot see
		 * that term at all, so a response is `own(≤SCOPE_LIMIT) + shared(≤SCOPE_LIMIT) +
		 * |closure|` with the last unbounded. That is accepted rather than fixed. The
		 * closure is the set `CriticalPath` computes slack over; capping it would drop the
		 * far end of an arrow out from under a chain still using it, and the ticket that
		 * lost its stub would report the wrong slack rather than an honestly incomplete
		 * one. An oversized response is a better failure than a wrong number.
		 */
		const val SCOPE_LIMIT = 2000
	}
}
