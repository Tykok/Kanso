package dev.kanso.service

import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.Ticket
import dev.kanso.repo.TeamStatusRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * What a status means, asked in one place — `KAN-90`.
 *
 * This replaces `ticket.status.category`, and the move off a property is the whole design.
 * A property on the ticket can only answer out of the six `DefaultStatus` names, so the
 * moment a team invents a seventh word, every screen that asked the ticket what its work
 * *meant* would get either a crash or — far worse — a plausible wrong answer. A burndown
 * would draw a day short and no test would see it. So the question moves to the only thing
 * that can answer it: the team's own catalogue.
 *
 * **Nothing here reads a status literal to decide what work means.** That is the plan's
 * one global constraint, and this class is where it is kept: `keysMeaning` is what the
 * seven `DefaultStatus.entries.filter { … }` lists become, so a query asking for "the
 * finished ones" gets the asking team's finished ones instead of Kanso's.
 *
 * Reading is unauthenticated on purpose, like `TeamStatusService.forTeams`: this answers
 * what a team *calls* its work, for rows the caller has already been handed.
 */
@Service
class StatusCategories(private val statuses: TeamStatusRepository) {

	/**
	 * One team's catalogue as a lookup, in the order the team reads it.
	 *
	 * A `LinkedHashMap` and not a sort at the call site: `TeamStatusRepository` already
	 * orders by `(position, key)`, and that order is what a grouped page stacks its
	 * buckets by. Losing it here would make every caller re-sort, and one of them would
	 * forget.
	 */
	@Transactional(readOnly = true)
	fun forTeam(teamId: UUID): Map<String, StatusCategory> =
		statuses.forTeam(teamId).associate { it.key to it.category }

	/**
	 * What [status] means for [teamId] — the direct replacement for `.status.category`.
	 *
	 * [teamId] is nullable because a draft has no team to ask. Takes the status as a
	 * `String` rather than a `DefaultStatus` so that Task 3's type change moves no call
	 * site a second time.
	 *
	 * One query per call, so this is for the single reading. A screen holding rows uses
	 * [of].
	 */
	@Transactional(readOnly = true)
	fun categoryOf(teamId: UUID?, status: String): StatusCategory =
		resolve(teamId?.let { forTeam(it) }, status)

	/**
	 * The team's own keys that mean [category] — what a `WHERE status IN (…)` asks for.
	 *
	 * This is the honest replacement for `DefaultStatus.entries.filter { it.category == … }`,
	 * and it is honest only for a query scoped to *one* team. A query whose scope spans
	 * teams cannot be served by any single team's keys: it has to filter on the category
	 * column itself, by joining `team_statuses`. `WorkloadService.OPEN_STATUSES`,
	 * `PublicRoadmapService.ROADMAP_STATUSES` and `TicketRepository.MOVED_ALONG_STATUSES`
	 * are all that second shape, and calling this for them would quietly narrow the query
	 * to one team's vocabulary.
	 */
	@Transactional(readOnly = true)
	fun keysMeaning(teamId: UUID, category: StatusCategory): List<String> =
		statuses.forTeam(teamId).filter { it.category == category }.map { it.key }

	/**
	 * Every category [tickets] needs, from one read of the catalogue.
	 *
	 * A value rather than a service, and one query rather than a lookup per row: the
	 * callers are burndowns, workload charts and progress bars holding hundreds of rows
	 * across a handful of teams, so a per-ticket lookup is an N+1 on every one of those
	 * screens. Distinct team ids, so a hundred rows in two teams is one query for two.
	 */
	@Transactional(readOnly = true)
	fun of(tickets: Collection<Ticket>): Categories =
		Categories(statuses.forTeams(tickets.mapNotNull { it.teamId }.toSet())
			.mapValues { (_, rows) -> rows.associate { it.key to it.category } })

	companion object {
		/**
		 * The team's word, then Kanso's, then unstarted work.
		 *
		 * The last step is unreachable rather than lenient, and that is a deliberate
		 * choice against throwing: `tickets_status_fk` refuses a ticket whose status its
		 * team never declared, so a status that resolves to nothing here can only come
		 * from a path nobody has written yet. Counted as `UNSTARTED`, such a row draws a
		 * burndown one ticket short; thrown, it draws no burndown at all. The first is a
		 * bug somebody reports, the second is an outage.
		 *
		 * The middle step is a draft's, whose vocabulary is the six by definition — see
		 * `DefaultStatus`' own docstring.
		 */
		internal fun resolve(catalogue: Map<String, StatusCategory>?, status: String): StatusCategory =
			catalogue?.get(status)
				?: DefaultStatus.entries.firstOrNull { it.wire == status }?.category
				?: StatusCategory.UNSTARTED
	}
}

/**
 * The categories of a set of rows, already read — `StatusCategories.of`.
 *
 * Indexed rather than called, so the call site reads as closely as possible to the
 * `ticket.status.category` it replaces: `categories[ticket]` where the property was.
 */
class Categories(private val byTeam: Map<UUID, Map<String, StatusCategory>>) {

	operator fun get(ticket: Ticket): StatusCategory =
		StatusCategories.resolve(ticket.teamId?.let { byTeam[it] }, ticket.status.wire)
}
