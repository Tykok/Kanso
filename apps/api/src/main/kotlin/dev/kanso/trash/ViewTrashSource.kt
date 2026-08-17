package dev.kanso.trash

import dev.kanso.domain.User
import dev.kanso.repo.SavedViewRepository
import dev.kanso.repo.SavedViewRow
import dev.kanso.repo.TeamRepository
import dev.kanso.service.SavedViewService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A saved view — the smallest of the four, because a saved view is the smallest thing.
 *
 * `holds` is empty and stays empty. A view is a stored *question*, never a stored answer:
 * the tickets it lists are matched on every read, so it holds none of them and deleting it
 * reaches nothing at all. A `MENTIONED_TICKETS`-shaped holding here would be a count of
 * rows the view happened to match this second, and a pane saying "held 14 tickets" about a
 * predicate would teach the reader the wrong thing about what a view is.
 *
 * The parent is the team, always: `saved_views.team_id` is `NOT NULL`, so "Restore into
 * Core" never has to say "somewhere".
 */
@Component
class ViewTrashSource(
	private val views: SavedViewService,
	private val rows: SavedViewRepository,
	private val teams: TeamRepository,
) : TrashSource {

	override val kind = TrashKind.VIEW

	override fun describe(ids: Collection<UUID>): List<TrashItem> = itemsOf(rows.findTrashed(ids))

	override fun restore(actor: User, id: UUID) = views.restoreFromTrash(actor, id)

	override fun purge(actor: User?, id: UUID) = views.purge(actor, id)

	/** One query for the teams rather than one per row, the rule every source keeps. */
	private fun itemsOf(found: List<SavedViewRow>): List<TrashItem> {
		if (found.isEmpty()) return emptyList()
		val keys = teams.findAllById(found.map { it.teamId }.toSet()).associate { it.id to it }

		return found.map { view ->
			TrashItem(
				kind = kind,
				id = view.id,
				label = view.name,
				parent = keys[view.teamId]?.let { TrashParent("team", it.id, it.name) },
			)
		}
	}
}
