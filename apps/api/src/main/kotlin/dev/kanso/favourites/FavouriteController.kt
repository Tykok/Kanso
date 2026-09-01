package dev.kanso.favourites

import dev.kanso.auth.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class FavouriteResponse(
	val kind: String,
	val id: UUID,
	val label: String,
	/** Drawn dimmed, or not drawn at all: the sidebar's "Show archived" toggle decides. */
	val archived: Boolean,
) {
	companion object {
		fun of(item: FavouriteItem) = FavouriteResponse(
			kind = item.kind.wire,
			id = item.id,
			label = item.label,
			archived = item.archived,
		)
	}
}

/**
 * Under `/api/me`, beside preferences, because that is what a favourite is: a fact about
 * the person asking and not about the instance. There is no path here that names a user,
 * and there will not be one — nobody reads somebody else's sidebar.
 *
 * `PUT` rather than `POST` for the same reason [FavouriteRepository.add] is idempotent:
 * the gesture is a toggle, the second press of it is a duplicate, and `PUT` is the verb
 * that says a repeated request leaves the world where the first one did.
 */
@RestController
@RequestMapping("/api/me/favourites")
class FavouriteController(
	private val currentUser: CurrentUser,
	private val favourites: FavouriteService,
) {

	/**
	 * No `includeArchived`, unlike `GET /api/teams` and `GET /api/projects`.
	 *
	 * Those take it because the lists are large and the archived tail is most of them. A
	 * person's pins are a handful of rows, so the whole set is sent with each row's
	 * `archived` on it and the sidebar's toggle — `useUi` state that never reaches here —
	 * decides what to draw. One cache entry instead of two, one request instead of two, and
	 * flipping the toggle repaints instead of refetching.
	 */
	@GetMapping
	fun list(): List<FavouriteResponse> =
		favourites.list(currentUser.requireId()).map(FavouriteResponse::of)

	@PutMapping("/{kind}/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun add(@PathVariable kind: String, @PathVariable id: UUID) {
		favourites.add(currentUser.requireId(), FavouriteKind.from(kind), id)
	}

	@DeleteMapping("/{kind}/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun remove(@PathVariable kind: String, @PathVariable id: UUID) {
		favourites.remove(currentUser.requireId(), FavouriteKind.from(kind), id)
	}
}
