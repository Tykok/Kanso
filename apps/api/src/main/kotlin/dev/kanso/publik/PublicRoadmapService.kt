package dev.kanso.publik

import dev.kanso.domain.TicketStatus
import dev.kanso.repo.TeamRepository
import dev.kanso.service.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Screens 27 and 28, for a reader with no account.
 *
 * The statuses are the application's own and nothing is reworded on the way out: the
 * response carries `backlog`, `todo`, `in_progress`, `done` — the wire values every
 * other endpoint uses — and the web renders them through the same `STATUS_LABELS` the
 * ticket list does. A marketing vocabulary invented here would be a second definition
 * of what a status means, drifting from the first the moment either changed.
 */
@Service
class PublicRoadmapService(
	private val published: PublicRoadmapRepository,
	private val teams: TeamRepository,
) {

	@Transactional(readOnly = true)
	fun roadmap(): Roadmap {
		val rows = published.findPublished(ROADMAP_STATUSES, LIMIT)
		val votes = published.voteCounts(rows.map { it.id })
		val entries = rows.map { it.entry(votes[it.id] ?: 0) }
		return Roadmap(
			ROADMAP_STATUSES
				.map { status -> RoadmapGroup(status, entries.filter { it.status == status }.sorted(status)) }
				.filter { it.count > 0 },
		)
	}

	@Transactional(readOnly = true)
	fun contributorPage(teamKey: String, number: Int): ContributorPage {
		val row = published.findPublishedByKey(teamKey, number)
			?: throw NotFoundException("No published ticket ${teamKey.uppercase()}-$number")
		val votes = published.voteCounts(listOf(row.id))[row.id] ?: 0
		// [firstSteps] already holds this ticket when it qualifies as one, so its size is
		// the count and the list minus this ticket is what to offer next. Adding one for
		// "this one" would have over-counted a ticket that is unclaimed but already in
		// progress — available to read, not available to pick up.
		val steps = firstSteps()
		val others = steps.filterNot { it.identifier == row.identifier }

		return ContributorPage(
			identifier = row.identifier,
			title = row.title,
			explanation = row.description,
			status = row.status,
			votes = votes,
			unclaimed = row.unclaimed,
			whereToLook = published.whereToLook(row.id),
			// The ticket's own team, by display name. Members of a *parent* team may also
			// move this ticket (TicketAccess walks the chain upwards), but a contributor
			// asked "who can help with this" and the honest answer is the people whose
			// board it is on, not everyone with the authority to touch it.
			helpers = teams.members(row.teamId).map { Helper(it.user.displayName, it.role) },
			otherFirstSteps = others.take(OTHER_FIRST_STEPS),
			unclaimedCount = steps.size,
		)
	}

	/**
	 * Published tickets nobody has picked up, in the two statuses where starting on one
	 * is still useful. Not "tickets labelled `good first step`" — labels are the
	 * foundation's and are not in the schema yet; when they are, this is the query that
	 * narrows, and `unclaimed` stays the fact that decides the badge either way.
	 */
	private fun firstSteps(): List<RoadmapEntry> {
		val rows = published.findPublished(listOf(TicketStatus.BACKLOG, TicketStatus.TODO), LIMIT)
			.filter { it.unclaimed }
		val votes = published.voteCounts(rows.map { it.id })
		return rows.map { it.entry(votes[it.id] ?: 0) }.sortedWith(byVotes)
	}

	private fun PublishedRow.entry(votes: Int) = RoadmapEntry(
		identifier = identifier,
		title = title,
		status = status,
		votes = votes,
		// Only where it means something. A `completed_at` on a ticket that came back out
		// of `done` would print a delivery date beside work in progress.
		completedAt = completedAt.takeIf { status == TicketStatus.DONE },
	)

	/**
	 * Most wanted first, except in the delivered column, which reads most recent first —
	 * both taken off the drawing (128, 96, 41 under consideration; 18 July above 2 July
	 * under delivered). Votes rank the work not yet decided; once it has shipped, the
	 * question a visitor is asking changed from "will you" to "when did you".
	 */
	private fun List<RoadmapEntry>.sorted(status: TicketStatus) =
		if (status == TicketStatus.DONE) sortedWith(byDelivery) else sortedWith(byVotes)

	private companion object {
		/**
		 * Left to right, and `canceled` is not among them: a roadmap answers "are you
		 * considering it, have you accepted it, is it moving, has it shipped", and a
		 * canceled ticket answers none of those. Every other status the application has
		 * appears, including `in_review` — folding review into progress would print a
		 * word over a ticket the app calls something else, which is the reformulation
		 * the drawing rules out. Groups with nothing in them are dropped, so an instance
		 * whose published work sits in four of these draws four columns.
		 */
		val ROADMAP_STATUSES = listOf(
			TicketStatus.BACKLOG,
			TicketStatus.TODO,
			TicketStatus.IN_PROGRESS,
			TicketStatus.IN_REVIEW,
			TicketStatus.DONE,
		)

		/** The whole shop window in one response; it is not a paginated surface. */
		const val LIMIT = 400
		const val OTHER_FIRST_STEPS = 3

		val byVotes = compareByDescending<RoadmapEntry> { it.votes }.thenBy { it.title }
		val byDelivery = compareByDescending<RoadmapEntry> { it.completedAt }.thenBy { it.title }
	}
}
