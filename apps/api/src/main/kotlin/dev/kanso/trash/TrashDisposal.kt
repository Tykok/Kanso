package dev.kanso.trash

import dev.kanso.docs.DocFolderRepository
import dev.kanso.docs.DocPageRepository
import dev.kanso.repo.SavedViewRepository
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What `V11`'s missing foreign key costs, paid in one place.
 *
 * `trash_entries.entity_id` points at four tables and so can carry no foreign key. Two
 * paths destroy entities outright — `TeamService.delete` and `ProjectService.delete`, each
 * under its own consent model, a retyped name rather than a countdown — and neither can be
 * left to take the entries with it, or a deletion would go on counting down over a row that
 * is already gone.
 *
 * A bean rather than three repositories injected into `TeamService`, because *which table a
 * kind lives in* is this package's fact and nowhere else's: the disposition asks to forget a
 * team, not to know that a team holds pages, folders and views.
 *
 * Every query below is driven from the trash and not from the teams, which is the cheap
 * direction of the same question: the trash holds tens of rows and a team can hold thousands
 * of pages.
 */
@Component
class TrashDisposal(
	private val entries: TrashRepository,
	private val tickets: TicketRepository,
	private val pages: DocPageRepository,
	private val folders: DocFolderRepository,
	private val views: SavedViewRepository,
) {

	/**
	 * The ids a destructive disposition just deleted, whether or not any was in the trash.
	 *
	 * For the project path, where deleting the project destroys nothing by itself —
	 * `tickets.project_id` is `ON DELETE SET NULL` — so the only rows that go are the ones
	 * the plan asked for, and those are the ones to forget.
	 */
	fun forgetTickets(ticketIds: Collection<UUID>) {
		entries.forget(TrashKind.TICKET, ticketIds)
	}

	/**
	 * Everything a team's disappearance takes with it, asking or not.
	 *
	 * All four kinds are `ON DELETE CASCADE` from `teams`, so all four rows can go in
	 * Postgres with no Kotlin involved — which is precisely what makes them the easiest
	 * orphans to leave behind. The tickets are here rather than left to the plan's own
	 * deletes for exactly that reason: a ticket already in the trash is invisible to
	 * `TicketRepository.search`, so the disposition never sees it, never pushes it to the
	 * mirror, and the cascade takes it anyway.
	 */
	fun forgetTeams(teamIds: Collection<UUID>) {
		if (teamIds.isEmpty()) return
		forgetTickets(tickets.idsWithinTeams(entries.idsOf(TrashKind.TICKET), teamIds))
		entries.forget(TrashKind.DOC, pages.idsWithinTeams(entries.idsOf(TrashKind.DOC), teamIds))
		entries.forget(
			TrashKind.FOLDER,
			folders.idsWithinTeams(entries.idsOf(TrashKind.FOLDER), teamIds),
		)
		entries.forget(TrashKind.VIEW, views.idsWithinTeams(entries.idsOf(TrashKind.VIEW), teamIds))
	}
}
