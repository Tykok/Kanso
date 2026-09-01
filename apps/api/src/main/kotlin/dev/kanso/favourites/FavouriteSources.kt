package dev.kanso.favourites

import dev.kanso.docs.DocPageRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.SavedViewRepository
import dev.kanso.repo.TeamRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The four kinds, in one file.
 *
 * The trash gives each of its sources a file of its own, and it is right to: a
 * `TrashSource` answers five questions and one of them — what the thing holds, and
 * whether the delete reaches it — is a paragraph of reasoning per kind. A
 * [FavouriteSource] answers one, and it answers it in three lines. Four three-line beans
 * in four files would be four files nobody ever reads together, and the thing worth
 * reading here *is* the four side by side: two of them carry an archive flag, two of them
 * hide a trashed row, and none of them says a word about deletion because `V22`'s foreign
 * keys already did.
 */

/**
 * A team.
 *
 * Archived rows are sent, flagged rather than dropped: archiving is a decision with no
 * clock on it and the sidebar's own "Show archived" toggle is what answers it — client
 * state, which this side cannot see and must not guess at.
 */
@Component
class TeamFavouriteSource(private val teams: TeamRepository) : FavouriteSource {

	override val kind = FavouriteKind.TEAM

	override fun describe(ids: Collection<UUID>): List<FavouriteItem> =
		// The name alone, not `Core · KAN`: the tree below draws the prefix in a column of
		// its own, and a pinned row repeating it would spend a third of the width saying
		// what the row underneath already says.
		teams.findAllById(ids).map { FavouriteItem(kind, it.id, it.name, archived = it.archived) }
}

/** A project. Same archive rule as a team's, for the same reason. */
@Component
class ProjectFavouriteSource(private val projects: ProjectRepository) : FavouriteSource {

	override val kind = FavouriteKind.PROJECT

	override fun describe(ids: Collection<UUID>): List<FavouriteItem> =
		projects.findAllById(ids).map { FavouriteItem(kind, it.id, it.name, archived = it.archived) }
}

/**
 * A saved view. There is no archive to flag — `archived` is a column on `teams`,
 * `projects` and `tickets` and on nothing else — so the only put-away state is the trash,
 * which the repository's own live read already excludes.
 */
@Component
class ViewFavouriteSource(private val views: SavedViewRepository) : FavouriteSource {

	override val kind = FavouriteKind.VIEW

	override fun describe(ids: Collection<UUID>): List<FavouriteItem> =
		views.findAllLive(ids).map { FavouriteItem(kind, it.id, it.name) }
}

/** A document. Same as a view: no archive, and the trash is the repository's business. */
@Component
class DocFavouriteSource(private val pages: DocPageRepository) : FavouriteSource {

	override val kind = FavouriteKind.DOC

	override fun describe(ids: Collection<UUID>): List<FavouriteItem> =
		pages.findAllLive(ids).map { FavouriteItem(kind, it.id, it.title) }
}
