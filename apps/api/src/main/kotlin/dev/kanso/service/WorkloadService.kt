package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.CycleRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * One person's load: how many open tickets, cut by status, and how old the oldest is.
 *
 * There is no estimate here and there is not going to be one. The drawing says it in as
 * many words — "aucune estimation en points : la charge se lit au nombre et à
 * l'ancienneté" — and a points column would not merely be extra, it would replace the
 * thing the screen is arguing for. [person] is null for the unassigned bucket.
 */
data class WorkloadRow(
	val person: User?,
	val total: Int,
	val byStatus: Map<TicketStatus, Int>,
	/** What the sentence under the chart is about. Strictly more than three, not at least. */
	val urgentOverThreeDays: Int,
	val oldestOpenDays: Int,
)

data class Workload(val cycleId: UUID?, val rows: List<WorkloadRow>)

/**
 * Screen 23. Open tickets per person, counted.
 *
 * A ticket with two assignees is counted once for each of them. It is on both their
 * plates, and dividing it in half would be an estimate in everything but name.
 */
@Service
class WorkloadService(
	private val tickets: TicketRepository,
	private val cycles: CycleRepository,
	private val teams: TeamRepository,
	private val users: UserRepository,
) {

	/**
	 * [cycleId] narrows to one cycle's commitment — the drawing's header reads "Charge ·
	 * cycle 24". Absent, it is the team's whole open board, which is the honest default:
	 * work outside the cycle is still work somebody is carrying.
	 */
	@Transactional(readOnly = true)
	fun forTeam(teamId: UUID, cycleId: UUID? = null, now: OffsetDateTime = OffsetDateTime.now()): Workload {
		val teamIds = teams.descendantIds(teamId)
		val open = if (cycleId == null) {
			tickets.search(teamIds = teamIds, statuses = OPEN_STATUSES, limit = SCAN_LIMIT)
		} else {
			cycles.ticketsIn(cycleId).filter { it.status in OPEN_STATUSES && !it.archived }
		}
		if (open.isEmpty()) return Workload(cycleId, emptyList())

		val assignees = tickets.assigneeIdsFor(open.map { it.id })
		// One list per person, plus one for the tickets nobody owns. Built as a map of
		// nullable keys so the unassigned bucket travels through the same code as a person
		// rather than as a special case bolted on at the end.
		val byPerson = mutableMapOf<UUID?, MutableList<Ticket>>()
		for (ticket in open) {
			val owners = assignees[ticket.id].orEmpty()
			if (owners.isEmpty()) byPerson.getOrPut(null) { mutableListOf() } += ticket
			else owners.forEach { byPerson.getOrPut(it) { mutableListOf() } += ticket }
		}

		val people = users.findAllById(byPerson.keys.filterNotNull()).associateBy { it.id }
		val rows = byPerson.map { (userId, carried) -> row(people[userId], carried, now) }

		return Workload(
			cycleId = cycleId,
			// Heaviest first, and the unassigned pile last however big it is: a person's load
			// is the subject of this screen, and the orphan column is the footnote.
			rows = rows.sortedWith(
				compareBy<WorkloadRow> { it.person == null }
					.thenByDescending { it.total }
					.thenBy { it.person?.displayName.orEmpty() },
			),
		)
	}

	private fun row(person: User?, carried: List<Ticket>, now: OffsetDateTime) = WorkloadRow(
		person = person,
		total = carried.size,
		byStatus = OPEN_STATUSES.associateWith { status -> carried.count { it.status == status } },
		urgentOverThreeDays = carried.count {
			it.priority == TicketPriority.URGENT && daysOpen(it, now) > 3
		},
		oldestOpenDays = carried.maxOfOrNull { daysOpen(it, now) } ?: 0,
	)

	/**
	 * Whole days, truncated. A ticket created three days ago to the minute is three days
	 * old, not four and not "more than three" — the sentence under the chart is a
	 * threshold somebody will act on, and rounding it up would warn about work that
	 * arrived this morning.
	 */
	private fun daysOpen(ticket: Ticket, now: OffsetDateTime): Int =
		ChronoUnit.DAYS.between(ticket.createdAt, now).toInt().coerceAtLeast(0)

	companion object {
		/** Open means not settled. `done` and `canceled` are both out. */
		val OPEN_STATUSES = listOf(
			TicketStatus.BACKLOG,
			TicketStatus.TODO,
			TicketStatus.IN_PROGRESS,
			TicketStatus.IN_REVIEW,
		)

		/**
		 * The read is a count, so it has to see everything to be right. Capped anyway: a
		 * team with more open tickets than this has a bigger problem than an off-by-some
		 * workload chart, and an uncapped scan is how one page takes the instance down.
		 */
		const val SCAN_LIMIT = 2000
	}
}
