package dev.kanso.publik

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.DefaultStatus
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
		// "this one" would over-count a ticket that is unclaimed but already in progress —
		// available to read, not available to pick up — and, now that the list narrows to a
		// label, a ticket nobody marked as a first step at all.
		val steps = firstSteps()
		val others = steps.entries.filterNot { it.identifier == row.identifier }

		return ContributorPage(
			identifier = row.identifier,
			title = row.title,
			explanation = row.description,
			status = row.status,
			votes = votes,
			unclaimed = row.unclaimed,
			labels = published.labelNames(row.id),
			whereToLook = published.whereToLook(row.id),
			// The ticket's own team, by display name. Members of a *parent* team may also
			// move this ticket (TicketAccess walks the chain upwards), but a contributor
			// asked "who can help with this" and the honest answer is the people whose
			// board it is on, not everyone with the authority to touch it.
			helpers = teams.members(row.teamId).map { Helper(it.user.displayName, it.role) },
			otherFirstSteps = others.take(OTHER_FIRST_STEPS),
			firstStepLabel = steps.label,
			availableCount = steps.entries.size,
		)
	}

	/** What [firstSteps] found, and which question it answered to find it. */
	private data class FirstSteps(val entries: List<RoadmapEntry>, val label: String?)

	/**
	 * The work a contributor can pick up, narrowed to the [FIRST_STEP_LABEL] label.
	 *
	 * Two conditions, and they are not the same kind of thing. `unclaimed` is a fact —
	 * `ticket_assignees` is empty — and stays derived; the label is a maintainer's
	 * judgement that this one is a reasonable place to start. Both are needed: a ticket
	 * marked as a first step that somebody is already on is not available, and an
	 * unclaimed ticket nobody marked may be the hardest thing on the board.
	 *
	 * When no team has defined the label at all, this falls back to every unclaimed
	 * ticket and says so by answering with a null label. That is the older, more generous
	 * list, and the page prints `Unclaimed · N available` over it rather than promising a
	 * maintainer picked them out. A team that *has* defined the label and marked nothing
	 * with it gets an empty list, not the fallback: they said there are none.
	 */
	private fun firstSteps(): FirstSteps {
		val unclaimed = published.findPublished(NOT_STARTED_STATUSES, LIMIT)
			.filter { it.unclaimed }
		val narrowing = published.labelDefined(FIRST_STEP_LABEL)
		val rows = if (narrowing) {
			val marked = published.idsLabelled(FIRST_STEP_LABEL)
			unclaimed.filter { it.id in marked }
		} else {
			unclaimed
		}
		val votes = published.voteCounts(rows.map { it.id })
		return FirstSteps(
			entries = rows.map { it.entry(votes[it.id] ?: 0) }.sortedWith(byVotes),
			label = FIRST_STEP_LABEL.takeIf { narrowing },
		)
	}

	private fun PublishedRow.entry(votes: Int) = RoadmapEntry(
		identifier = identifier,
		title = title,
		status = status,
		votes = votes,
		// Only where it means something. A `completed_at` on a ticket that came back out
		// of `done` would print a delivery date beside work in progress.
		completedAt = completedAt.takeIf { status.category == StatusCategory.COMPLETED },
	)

	/**
	 * Most wanted first, except in the delivered column, which reads most recent first —
	 * both taken off the drawing (128, 96, 41 under consideration; 18 July above 2 July
	 * under delivered). Votes rank the work not yet decided; once it has shipped, the
	 * question a visitor is asking changed from "will you" to "when did you".
	 */
	private fun List<RoadmapEntry>.sorted(status: DefaultStatus) =
		if (status.category == StatusCategory.COMPLETED) sortedWith(byDelivery) else sortedWith(byVotes)

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
		val ROADMAP_STATUSES = DefaultStatus.entries.filter { it.category != StatusCategory.CANCELED }

		/**
		 * Nobody has picked these up yet, which is what makes them a first step. Two
		 * categories rather than one: `backlog` is "we might" and `unstarted` is "we
		 * will", and a newcomer can start on either — what rules a ticket out here is
		 * somebody having begun it, not how sure the team is that it should happen.
		 */
		val NOT_STARTED_STATUSES = DefaultStatus.entries.filter {
			it.category == StatusCategory.BACKLOG || it.category == StatusCategory.UNSTARTED
		}

		/**
		 * The label screen 28 narrows to, in the words the rest of the project already
		 * uses: the drawing's `bon premier pas`, and `copy.ts`'s own
		 * `Tickets marked “good first step”` on the landing page. A team spells it exactly
		 * this way or the page falls back — matched on the name because a hard-coded id
		 * cannot exist and because it has to hold across every team on the instance.
		 */
		const val FIRST_STEP_LABEL = "good first step"

		/** The whole shop window in one response; it is not a paginated surface. */
		const val LIMIT = 400
		const val OTHER_FIRST_STEPS = 3

		val byVotes = compareByDescending<RoadmapEntry> { it.votes }.thenBy { it.title }
		val byDelivery = compareByDescending<RoadmapEntry> { it.completedAt }.thenBy { it.title }
	}
}
