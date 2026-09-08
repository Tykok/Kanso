package dev.kanso.service

import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.StatusGrouping
import dev.kanso.domain.StatusOrder
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
	 * buckets by — and what `PrTransition` ranks progress on. Losing it here would make
	 * every caller re-sort, and one of them would forget.
	 *
	 * [teamId] null answers the six in `StatusOrder.WORKFLOW` order: a draft has no team
	 * to ask, and this is the one place that fallback is written.
	 */
	@Transactional(readOnly = true)
	fun forTeam(teamId: UUID?): Map<String, StatusCategory> =
		if (teamId == null) StatusOrder.WORKFLOW.associate { it.wire to it.category }
		else statuses.forTeam(teamId).associate { it.key to it.category }

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
	 * teams cannot be served by any single team's keys: it filters on the category column
	 * itself, through `TicketFilters.categories` and the `EXISTS` behind it.
	 * `WorkloadService.OPEN_CATEGORIES`, `PublicRoadmapService.ROADMAP_CATEGORIES` and
	 * `TicketRepository.MOVED_ALONG_CATEGORIES` are all that second shape, and calling this
	 * for them would quietly narrow the query to one team's vocabulary.
	 */
	@Transactional(readOnly = true)
	fun keysMeaning(teamId: UUID, category: StatusCategory): List<String> =
		statuses.forTeam(teamId).filter { it.category == category }.map { it.key }

	/**
	 * Every key any of [teamIds] uses for any of [categories], each named once.
	 *
	 * What a legend that must not change shape is built from — `ProgressService.byStatus`
	 * asks for "every open status, zeros included" so the chart's key is the same list on
	 * every page load, and since `KAN-90` that list is the scope's teams' words rather
	 * than Kanso's six.
	 *
	 * Deliberately unordered beyond being distinct: two teams' catalogues have two
	 * `position` sequences and there is no honest way to interleave them. The client
	 * orders what it draws, by `KAN-28`'s rule — one team's own order, or the five
	 * categories across teams.
	 */
	@Transactional(readOnly = true)
	fun keysMeaning(teamIds: Collection<UUID>, categories: Collection<StatusCategory>): List<String> =
		statuses.forTeams(teamIds.toSet()).values.flatten()
			.filter { it.category in categories }
			.map { it.key }
			.distinct()

	/**
	 * Where work of this meaning starts, for [teamId] — the eight hard-coded writes' rule.
	 *
	 * `RequestSiphon`, `TicketImport`, `CreateTicketTool`, `TriageService` (twice),
	 * `GithubWebhookService` and the two MCP drafts all used to write a status *word*:
	 * `todo`, `backlog`, `canceled`, `in_review`. None of those is guaranteed to exist in
	 * the destination team, so each now names the meaning and takes the team's first
	 * status of it.
	 *
	 * Ordered by `position`, because that order is the team's own answer to the question:
	 * a team that put *Devis* above `backlog` said that is where work it might do arrives.
	 * It also keeps the seeded behaviour exactly: `in_progress` precedes `in_review`, so
	 * `STARTED` resolves to progress and a pull request opening does not drop a ticket
	 * into review on a team that never reordered anything.
	 *
	 * **Null rather than a throw**, because the callers disagree about what to do with it.
	 * Seven refuse with a sentence naming the team's statuses; the GitHub webhook leaves
	 * the ticket alone, because a pull request opening must not invent a movement nobody
	 * asked for. Deciding that here would take the choice away from the one caller whose
	 * answer is different.
	 *
	 * A draft answers out of the six, like [categoryOf] — there is no team to ask.
	 */
	@Transactional(readOnly = true)
	fun firstOf(teamId: UUID?, category: StatusCategory): String? =
		forTeam(teamId).entries.firstOrNull { it.value == category }?.key

	/**
	 * Where a door files work nobody has classified — the team's own entry point.
	 *
	 * `DocBlockService`, `RequestSiphon`, `TicketImport` and the three MCP creators all
	 * wrote the word `todo`. A team may have renamed that status, which was harmless, or
	 * removed it, which was not: the write would name a key `tickets_status_fk` refuses
	 * and the door would answer 500. Each now asks for the meaning and gets the team's
	 * first `UNSTARTED` status, by the position the team chose.
	 *
	 * Throws rather than answering null, unlike [firstOf]: every caller of this one wants
	 * the same thing done about a team that has no such status, which is to refuse and say
	 * so. A team can only reach that state by removing every unstarted status on purpose,
	 * and a ticket arriving from outside is exactly the case it has to be told about.
	 */
	@Transactional(readOnly = true)
	fun intakeOf(teamId: UUID?): String =
		firstOf(teamId, StatusCategory.UNSTARTED)
			?: throw BadRequestException(
				"This team has no status meaning \"unstarted\", so there is nowhere to file new work",
			)

	/**
	 * [status] if [teamId] has it, refused by name if not — every write door's guard.
	 *
	 * The sentence lists the team's own words, because the caller that got this wrong is
	 * usually an agent or an import holding Kanso's six, and a refusal that does not name
	 * the alternatives is a refusal it cannot act on. This is the whole of `KAN-90`'s
	 * answer to the MCP tools' static schema: the schema says `string` and names the six
	 * seeded keys, and the team's actual vocabulary arrives in the error.
	 *
	 * A draft is checked against the six, which is its vocabulary by definition.
	 */
	@Transactional(readOnly = true)
	fun require(teamId: UUID?, status: String): String {
		val catalogue = forTeam(teamId)
		if (status in catalogue) return status
		throw BadRequestException(
			"No status \"$status\" here. This team's statuses are: ${catalogue.keys.joinToString(", ")}",
		)
	}

	/**
	 * How a scope buckets and stacks its statuses — `KAN-28`'s rule, read for `KAN-90`.
	 *
	 * One team reads its own words in its own order, because that is the list on its
	 * screen. Anything wider reads the five categories: a scope holding two teams holds
	 * two vocabularies, and the header has to be the fact they agree on. `null` is the
	 * widest scope there is — every team in the instance — and lands in the same branch.
	 *
	 * Lives here rather than in `TicketGrouping`, where it was private, because the
	 * grouped list is no longer its only reader: the workload chart and the progress bar
	 * bucket their own `byStatus` maps by the same rule, and a second copy of it would be
	 * a screen whose bars and whose list disagreed about what a bucket is.
	 */
	@Transactional(readOnly = true)
	fun groupingFor(teamIds: Collection<UUID>?): StatusGrouping {
		val single = teamIds?.singleOrNull()
			?: return StatusGrouping.byCategory(
				if (teamIds == null) statuses.all() else statuses.forTeams(teamIds.toSet()).values.flatten()
			)
		return StatusGrouping.of(statuses.forTeam(single))
	}

	/**
	 * Every category [tickets] needs, from one read of the catalogue.
	 *
	 * A value rather than a service, and one query rather than a lookup per row: the
	 * callers are burndowns, workload charts and progress bars holding hundreds of rows
	 * across a handful of teams, so a per-ticket lookup is an N+1 on every one of those
	 * screens. Distinct team ids, so a hundred rows in two teams is one query for two.
	 */
	@Transactional(readOnly = true)
	fun of(tickets: Collection<Ticket>): Categories = forTeams(tickets.map { it.teamId })

	/**
	 * The same value, for rows that are not `Ticket`s.
	 *
	 * The public roadmap reads `PublishedRow`, which carries a team and a status key and
	 * nothing else — it comes off `public_tickets`, a separate table. Rather than give
	 * that shape a `Ticket` it is not, both go through [Categories], which asks the
	 * question the honest way round: a team and a word, not an object.
	 */
	@Transactional(readOnly = true)
	fun forTeams(teamIds: Collection<UUID?>): Categories =
		Categories(statuses.forTeams(teamIds.filterNotNull().toSet())
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

	operator fun get(ticket: Ticket): StatusCategory = get(ticket.teamId, ticket.status)

	/** For a row that is not a `Ticket` — see `StatusCategories.forTeams`. */
	operator fun get(teamId: UUID?, status: String): StatusCategory =
		StatusCategories.resolve(teamId?.let { byTeam[it] }, status)
}
