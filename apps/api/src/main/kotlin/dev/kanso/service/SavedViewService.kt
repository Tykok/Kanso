package dev.kanso.service

import dev.kanso.domain.User
import dev.kanso.domain.Wire
import dev.kanso.domain.parse
import dev.kanso.repo.SavedViewRepository
import dev.kanso.repo.SavedViewRow
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.TicketQueryRepository
import dev.kanso.repo.TicketScope
import dev.kanso.trash.TrashKind
import dev.kanso.trash.TrashRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** How the rows are stacked. Mirrors `saved_views_group_by_chk`. */
enum class ViewGroupBy(override val wire: String) : Wire {
	STATUS("status"), PRIORITY("priority"), ASSIGNEE("assignee"), PROJECT("project"), NONE("none");

	companion object {
		fun from(raw: String): ViewGroupBy = parse(entries.toTypedArray(), raw)
	}
}

/** How the rows are ordered inside a group. Mirrors `saved_views_sort_by_chk`. */
enum class ViewSortBy(override val wire: String) : Wire {
	PRIORITY("priority"), UPDATED("updated"), CREATED("created"), TITLE("title");

	companion object {
		fun from(raw: String): ViewSortBy = parse(entries.toTypedArray(), raw)
	}
}

data class SavedView(
	val id: UUID,
	val teamId: UUID,
	val name: String,
	val shared: Boolean,
	val filters: Map<String, Any?>,
	val groupBy: ViewGroupBy,
	val sortBy: ViewSortBy,
	val createdBy: UUID?,
)

/** A view and the number of rows it currently answers with — one sidebar row. */
data class SavedViewSummary(val view: SavedView, val count: Int)

/**
 * Saved views: a stored question, never a stored answer.
 *
 * `filters` is jsonb because the set of facets is open by design, but the keys are
 * checked against [SERVED_FILTERS] on the way in. That is the difference between a chip
 * the screen can draw and a chip the screen can draw *and* honour — and a chip that
 * displays without filtering is worse than one that was refused, because the reader has
 * no way to tell the list is wrong.
 */
@Service
class SavedViewService(
	private val views: SavedViewRepository,
	private val rows: TicketQueryRepository,
	private val teams: TeamRepository,
	/** Shared with the main list, so a stored question and an unsaved one stack alike. */
	private val groups: TicketGroups,
	private val access: TicketAccess,
	private val json: ObjectMapper,
	private val trash: TrashRepository,
) {

	@Transactional(readOnly = true)
	fun list(actor: User, teamId: UUID): List<SavedViewSummary> {
		val scope = TicketScope(teams.descendantIds(teamId))
		return views.findByTeam(teamId)
			.filter { it.isVisibleTo(actor) }
			.map { row -> SavedViewSummary(row.toDomain(), rows.count(scope, parseFilters(row.filters))) }
	}

	@Transactional(readOnly = true)
	fun get(actor: User, id: UUID): SavedView = requireVisible(actor, requireLive(id)).toDomain()

	/**
	 * The same question stacked: every bucket it has, each with a count of the whole
	 * match and the rows of it this page reached.
	 *
	 * The counting used to be the client's, over whatever page it held — so a view of two
	 * thousand tickets drew `Todo · 29` meaning "twenty-nine of the two hundred you sent
	 * me". The header now says what the database says, and says it whether or not the
	 * rows under it have been fetched yet.
	 *
	 * The only way to read a view's rows. There was a flat `tickets(id)` beside this one
	 * answering `/views/{id}/tickets`, kept while the web app was moving over; nothing
	 * called either after the move, and a second door onto one question is a door that
	 * drifts. A caller that wants the flat list concatenates the buckets — they are the
	 * same rows in the same order, since the page boundary is cut against this stacking.
	 *
	 * `includeArchived` is left at its default and always will be: an archived ticket is
	 * out of every saved view, whatever the view asks. The main list is the caller that
	 * has a choice about it, because the Archives tab is a screen.
	 */
	@Transactional(readOnly = true)
	fun grouped(actor: User, id: UUID, limit: Int = 200, offset: Long = 0): List<TicketGroup> {
		val row = requireVisible(actor, requireLive(id))
		return groups.of(
			scope = TicketScope(scopeOf(row.teamId)),
			filters = parseFilters(row.filters),
			groupBy = ViewGroupBy.from(row.groupBy),
			sortBy = ViewSortBy.from(row.sortBy),
			limit = limit,
			offset = offset,
		)
	}

	/**
	 * How many rows the view answers with — the number in the sidebar, and now also the
	 * one in its header.
	 *
	 * The header used to read the size of a page of rows, which is not a count: a view
	 * matching two thousand tickets reported two hundred, and stopped moving when the two
	 * hundred and first arrived. [list] next to it has always asked the database, so the
	 * sidebar and the header disagreed the moment either was refetched alone.
	 */
	@Transactional(readOnly = true)
	fun count(actor: User, id: UUID): Int {
		val row = requireVisible(actor, requireLive(id))
		return rows.count(TicketScope(scopeOf(row.teamId)), parseFilters(row.filters))
	}

	@Transactional
	fun create(
		actor: User,
		teamId: UUID,
		name: String,
		shared: Boolean,
		filters: Map<String, Any?>,
		groupBy: ViewGroupBy,
		sortBy: ViewSortBy,
	): SavedView {
		access.requireTeam(actor, teamId)
		if (name.isBlank()) throw BadRequestException("A saved view needs a name")
		validate(filters)
		views.findByTeamAndName(teamId, name)?.let { taken ->
			// `saved_views_team_name_uniq` holds a view in the trash to its name too, so the
			// refusal is unavoidable — but one naming a view the reader cannot find anywhere
			// reads as a bug in the app. It says where the name went instead.
			if (trash.find(TrashKind.VIEW, taken.id) != null) {
				throw ConflictException(
					"A view called \"$name\" is in the trash. Restore it, or delete it for good.",
				)
			}
			throw ConflictException("This team already has a view called \"$name\"")
		}
		return views.insert(
			id = UUID.randomUUID(),
			teamId = teamId,
			name = name,
			shared = shared,
			filters = json.writeValueAsString(filters),
			groupBy = groupBy.wire,
			sortBy = sortBy.wire,
			createdBy = actor.id,
		).toDomain()
	}

	/**
	 * Absent means unchanged, as everywhere else in Kanso. `filters` is the exception that
	 * proves the rule: an empty map is a legitimate value — it is what removing the last
	 * chip leaves behind — so it is passed whole rather than merged key by key.
	 */
	@Transactional
	fun update(
		actor: User,
		id: UUID,
		name: String? = null,
		shared: Boolean? = null,
		filters: Map<String, Any?>? = null,
		groupBy: ViewGroupBy? = null,
		sortBy: ViewSortBy? = null,
	): SavedView {
		val current = requireVisible(actor, requireLive(id))
		access.requireTeam(actor, current.teamId)
		filters?.let(::validate)
		val renamed = name ?: current.name
		if (renamed != current.name && views.findByTeamAndName(current.teamId, renamed) != null) {
			throw ConflictException("This team already has a view called \"$renamed\"")
		}
		return views.update(
			id = id,
			name = renamed,
			shared = shared ?: current.shared,
			filters = filters?.let(json::writeValueAsString) ?: current.filters,
			groupBy = (groupBy ?: ViewGroupBy.from(current.groupBy)).wire,
			sortBy = (sortBy ?: ViewSortBy.from(current.sortBy)).wire,
		)?.toDomain() ?: throw NotFoundException("No saved view $id")
	}

	/**
	 * Puts the view in the trash, and touches the row not at all.
	 *
	 * A saved view is a stored question, so there is nothing to unpick: the rail stops
	 * asking it because [SavedViewRepository.findByTeam] is a live read, and the question
	 * itself sits untouched for thirty days. A second delete is not a second countdown.
	 */
	@Transactional
	fun delete(actor: User, id: UUID) {
		val current = requireVisible(actor, require(id))
		access.requireTeam(actor, current.teamId)
		if (trash.find(TrashKind.VIEW, id) != null) return
		trash.add(TrashKind.VIEW, id, actor.id)
	}

	/** Nothing to put back. Who may do it is the whole of this. */
	@Transactional
	fun restoreFromTrash(actor: User, id: UUID) {
		access.requireTeam(actor, require(id).teamId)
	}

	/** For good, and what [delete] used to do. [actor] is null for the retention sweep. */
	@Transactional
	fun purge(actor: User?, id: UUID) {
		val current = require(id)
		actor?.let { access.requireTeam(it, current.teamId) }
		views.delete(id)
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * A view is scoped to its team and that team's descendants, before any filter runs. A
	 * team's saved view showing another team's work would make the sidebar count next to
	 * it a number about somebody else.
	 */
	private fun scopeOf(teamId: UUID): List<UUID> = teams.descendantIds(teamId)

	/**
	 * The gate, and it is not this service's own any more: `GET /api/tickets` asks the same
	 * question and is held to the same names. A key served here and refused there — or the
	 * other way round — would be the divergence this file used to be one half of.
	 */
	private fun validate(filters: Map<String, Any?>) = TicketFilterVocabulary.validate(filters)

	private fun parseFilters(raw: String): TicketFilters {
		@Suppress("UNCHECKED_CAST")
		val map = json.readValue(raw, Map::class.java) as Map<String, Any?>
		return TicketFilterVocabulary.parse(map)
	}

	/**
	 * The row whatever state it is in, including one in the trash — which is what the three
	 * exits need, because each of them is reached *because* the view was thrown away.
	 */
	/**
	 * What `shared` has meant since `V10` and what nothing enforced: *"False means it is the
	 * author's own, which is why `created_by` is not nullable-by-accident: an unshared view
	 * with no owner would be reachable by nobody."*
	 *
	 * The column was written, read back and shown in the UI, and appeared in no predicate
	 * anywhere — so a colleague on the same team listed a private view, read its rows,
	 * published it by patching `shared` to true, rewrote the question it asked, or binned it.
	 * Team write access was the only thing ever checked, and on a fresh instance whose
	 * `team_members` is empty every member has that.
	 *
	 * [NotFoundException] and not [org.springframework.security.access.AccessDeniedException],
	 * the same answer `TicketAccess.requireReadable` gives a private draft and for the same
	 * reason: a 403 tells somebody walking ids that the row is there, and "Alice has a view
	 * she did not share" is the fact being kept.
	 *
	 * A shared view stays the team's to edit and to delete — that is what sharing it was. The
	 * rule is only about the ones that were not shared.
	 */
	private fun SavedViewRow.isVisibleTo(actor: User) = shared || createdBy == actor.id

	private fun requireVisible(actor: User, row: SavedViewRow): SavedViewRow =
		row.takeIf { it.isVisibleTo(actor) } ?: throw NotFoundException("No saved view ${row.id}")

	private fun require(id: UUID): SavedViewRow =
		views.findById(id) ?: throw NotFoundException("No saved view $id")

	/** What every other caller needs: a view in the trash is a 404, not an empty list. */
	private fun requireLive(id: UUID): SavedViewRow =
		views.findLive(id) ?: throw NotFoundException("No saved view $id")

	private fun SavedViewRow.toDomain(): SavedView {
		@Suppress("UNCHECKED_CAST")
		val decoded = json.readValue(filters, Map::class.java) as Map<String, Any?>
		return SavedView(
			id = id,
			teamId = teamId,
			name = name,
			shared = shared,
			filters = decoded,
			groupBy = ViewGroupBy.from(groupBy),
			sortBy = ViewSortBy.from(sortBy),
			createdBy = createdBy,
		)
	}

	companion object {
		/**
		 * Where the gate went. It lived here while a saved view was the only thing holding
		 * one; `GET /api/tickets` validates through it too now, so it belongs to neither of
		 * them and sits in [TicketFilterVocabulary] with the parser it guards. Kept as an
		 * alias because this is the name the rest of the codebase already points at.
		 */
		val SERVED_FILTERS get() = TicketFilterVocabulary.SERVED
	}
}
