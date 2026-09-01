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
 * One person's load: how many open tickets, how many points, cut by status, and how old
 * the oldest is. [person] is null for the unassigned bucket.
 *
 * This shape used to refuse [points] outright — the drawing said "aucune estimation en
 * points : la charge se lit au nombre et à l'ancienneté", and a points column would have
 * replaced the thing the screen was arguing for. Tickets now carry an estimate, so the
 * refusal has become the lie: a person holding three 13s and a person holding three 1s
 * were reading as the same load, which is precisely what the count cannot see.
 *
 * The count did not go away, and that is the whole of the compromise. [total] is every
 * open ticket on their plate; [points] is the part of it somebody has sized; and
 * [unestimated] is the difference, so a bar drawn in points can say what it is not
 * showing. A sum that quietly leaves out half a plate is worse than a count that never
 * claimed to weigh anything.
 */
data class WorkloadRow(
	val person: User?,
	val total: Int,
	/** Their open tickets' points. Unsized ones are absent from it, never added as zero. */
	val points: Int,
	/** How many of [total] carry no estimate — what [points] cannot speak for. */
	val unestimated: Int,
	val byStatus: Map<TicketStatus, Int>,
	/** What the sentence under the chart is about. Strictly more than three, not at least. */
	val urgentOverThreeDays: Int,
	val oldestOpenDays: Int,
)

data class Workload(val cycleId: UUID?, val rows: List<WorkloadRow>)

/**
 * Screen 23. Open tickets per person, counted and weighed.
 *
 * A ticket with two assignees counts once for each of them, and its points land whole on
 * each plate too. It is on both their plates, and halving either number would invent a
 * split of the work that nobody agreed to — 4 points each out of an 8 is a claim about
 * how the pair will divide it, which is not a thing this table knows.
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
		points = carried.sumOf { it.estimate ?: 0 },
		unestimated = carried.count { it.estimate == null },
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
