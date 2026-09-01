package dev.kanso.service

import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.domain.Wire
import dev.kanso.domain.parse
import dev.kanso.repo.SavedViewFilters
import dev.kanso.repo.SavedViewRepository
import dev.kanso.repo.SavedViewRow
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.ViewTicketRepository
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
	private val rows: ViewTicketRepository,
	private val teams: TeamRepository,
	private val details: TicketDetails,
	private val access: TicketAccess,
	private val json: ObjectMapper,
	private val trash: TrashRepository,
) {

	@Transactional(readOnly = true)
	fun list(teamId: UUID): List<SavedViewSummary> {
		val scope = teams.descendantIds(teamId)
		return views.findByTeam(teamId).map { row ->
			SavedViewSummary(row.toDomain(), rows.count(scope, parseFilters(row.filters)))
		}
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): SavedView = requireLive(id).toDomain()

	/**
	 * The rows, matched now and ordered as the view asks. Grouping is left to the client:
	 * the group headers are counts of what is already on screen, and a grouped wire shape
	 * would be a second representation of one list that could disagree with itself.
	 */
	@Transactional(readOnly = true)
	fun tickets(id: UUID, limit: Int = 200): List<TicketDetail> {
		val row = requireLive(id)
		val found = rows.matching(
			teamIds = scopeOf(row.teamId),
			filters = parseFilters(row.filters),
			sortBy = ViewSortBy.from(row.sortBy),
			limit = limit,
		)
		return details.of(found)
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
		val current = requireLive(id)
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
		val current = require(id)
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

	private fun validate(filters: Map<String, Any?>) {
		val unknown = filters.keys - SERVED_FILTERS
		if (unknown.isNotEmpty()) {
			throw BadRequestException(
				"These filters are not served: ${unknown.sorted().joinToString()}." +
					" Served: ${SERVED_FILTERS.sorted().joinToString()}",
			)
		}
		// Parsed here rather than at match time, so an unknown status is a 400 on the write
		// that introduced it instead of an empty list every time the view is opened.
		strings(filters["status"]).forEach(TicketStatus::from)
		strings(filters["statusNot"]).forEach(TicketStatus::from)
		strings(filters["priority"]).forEach(TicketPriority::from)
	}

	private fun parseFilters(raw: String): SavedViewFilters {
		@Suppress("UNCHECKED_CAST")
		val map = json.readValue(raw, Map::class.java) as Map<String, Any?>
		return SavedViewFilters(
			statuses = strings(map["status"]).map(TicketStatus::from),
			statusesExcluded = strings(map["statusNot"]).map(TicketStatus::from),
			priorities = strings(map["priority"]).map(TicketPriority::from),
			projectIds = strings(map["project"]).map(UUID::fromString),
			assigneeIds = strings(map["assignee"]).map(UUID::fromString),
			unassigned = map["unassigned"] == true,
			cycleIds = strings(map["cycle"]).map(UUID::fromString),
			labelIds = strings(map["label"]).map(UUID::fromString),
			openedMoreThanDaysAgo = (map["openedForDays"] as? Number)?.toInt(),
			unestimated = map["unestimated"] == true,
			estimateMin = (map["estimateMin"] as? Number)?.toInt(),
			estimateMax = (map["estimateMax"] as? Number)?.toInt(),
		)
	}

	private fun strings(value: Any?): List<String> = when (value) {
		null -> emptyList()
		is List<*> -> value.mapNotNull { it?.toString() }
		else -> listOf(value.toString())
	}

	/**
	 * The row whatever state it is in, including one in the trash — which is what the three
	 * exits need, because each of them is reached *because* the view was thrown away.
	 */
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
		 * The facets the matcher actually implements.
		 *
		 * `label` was deliberately absent while `V8` had not landed: the drawing's third
		 * chip is `Étiquette synchro`, and a chip stored and drawn but never honoured is
		 * worse than one refused, because the reader cannot tell the list is wrong. `V8`
		 * landed with `labels` and `ticket_labels`, so it is served — this entry plus the
		 * one clause in `ViewTicketRepository` the old comment promised. It holds label
		 * *ids*, like `project`, `assignee` and `cycle`: labels are team-scoped, a view
		 * reaches into descendant teams, and two of them may both own the name `sync`.
		 */
		val SERVED_FILTERS = setOf(
			"status",
			"statusNot",
			"priority",
			"project",
			"assignee",
			"unassigned",
			"cycle",
			"label",
			"openedForDays",
			// The three the estimate answers. `unestimated` is separate from the two bounds
			// because null is not a small number: "not sized yet" is the question a planning
			// session opens with, and no `estimateMax` can express it.
			//
			// The bounds are not checked against `EffortPoints.SCALE` on the way in, unlike
			// `status` and `priority` beside them: they are comparisons, not vocabulary, and
			// `estimateMin: 4` is the legitimate question "bigger than a 3".
			"unestimated",
			"estimateMin",
			"estimateMax",
		)
	}
}
