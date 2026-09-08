package dev.kanso.service

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.StatusGrouping
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
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
	/**
	 * Counted into the buckets this scope groups by — `KAN-90`, through `StatusGrouping`.
	 *
	 * One team's scope keys this by that team's own status keys. A scope holding a parent
	 * and its descendants keys it by the five categories, and it has to: two teams may
	 * both call a status `review` and declare it in different categories, so a map keyed
	 * by the bare word would add two meanings into one number. Bucketing here rather than
	 * in the client is what makes that impossible to get wrong — and it is the same rule,
	 * from the same `StatusGrouping`, that the grouped list already uses.
	 *
	 * Counted from the tickets carried, so a bucket holding nothing is absent rather than
	 * zero. The client draws the vocabulary it knows it asked for: `Team.statuses` for one
	 * team, `CATEGORY_ORDER` for a wider scope.
	 */
	val byStatus: Map<String, Int>,
	/**
	 * How much of [total] is actually in flight — the STARTED category, resolved here.
	 *
	 * A field rather than a sum the reader takes over [byStatus], because for a single
	 * team that map is keyed by words and no reader outside this service can turn a word
	 * into a meaning without a second read. `TeamWorkloadTool` prints it as its own
	 * column, and its docstring is where the open-versus-started argument lives.
	 */
	val started: Int,
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
	private val statusCategories: StatusCategories,
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
			tickets.search(teamIds = teamIds, categories = OPEN_CATEGORIES, limit = SCAN_LIMIT)
		} else {
			// In memory rather than in SQL, because `ticketsIn` is a membership read and
			// not a predicate. One catalogue read for the cycle's teams, same answer.
			cycles.ticketsIn(cycleId).let { inCycle ->
				val categories = statusCategories.of(inCycle)
				inCycle.filter { categories[it] in OPEN_CATEGORIES && !it.archived }
			}
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
		// Both read once for the whole screen: the grouping is a catalogue read per scope,
		// and `Categories` a catalogue read per team holding a row. A person's row asking
		// for either would be a query per plate.
		val grouping = statusCategories.groupingFor(teamIds)
		val categories = statusCategories.of(open)
		val rows = byPerson.map { (userId, carried) ->
			row(people[userId], carried, now, grouping, categories)
		}

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

	private fun row(
		person: User?,
		carried: List<Ticket>,
		now: OffsetDateTime,
		grouping: StatusGrouping,
		categories: Categories,
	) = WorkloadRow(
		person = person,
		total = carried.size,
		points = carried.sumOf { it.estimate ?: 0 },
		unestimated = carried.count { it.estimate == null },
		byStatus = carried
			.groupingBy { grouping.bucketOf[it.status.wire] ?: it.status.wire }
			.eachCount(),
		started = carried.count { categories[it] == StatusCategory.STARTED },
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
		/**
		 * Open means not settled: neither category that ends a ticket is in here.
		 *
		 * The categories themselves since `KAN-90`, not the statuses that have them. This
		 * constant is read by four screens over scopes that span teams — a parent team and
		 * every descendant, or one person's work across every team they are in — and no
		 * single team's keys can say what any of them mean.
		 */
		val OPEN_CATEGORIES = StatusCategory.entries.filter {
			it != StatusCategory.COMPLETED && it != StatusCategory.CANCELED
		}

		/**
		 * The read is a count, so it has to see everything to be right. Capped anyway: a
		 * team with more open tickets than this has a bigger problem than an off-by-some
		 * workload chart, and an uncapped scan is how one page takes the instance down.
		 */
		const val SCAN_LIMIT = 2000
	}
}
