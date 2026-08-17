package dev.kanso.trash

import dev.kanso.auth.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

/** Just enough of a person for the "Deleted by" column. No email: a name is what it draws. */
data class TrashActorResponse(val id: UUID, val displayName: String)

data class TrashParentResponse(val kind: String, val id: UUID?, val name: String)

data class TrashHoldingResponse(val kind: String, val count: Int, val cascades: Boolean)

data class TrashItemResponse(
	val kind: String,
	val id: UUID,
	val label: String,
	val parent: TrashParentResponse?,
	val holds: List<TrashHoldingResponse>,
	val deletedAt: OffsetDateTime?,
	val deletedBy: TrashActorResponse?,
	/** Whole days left of the thirty. Null for an archive, which has no clock on it. */
	val daysLeft: Int?,
	/** Whether "Archive instead" exists for this kind. Only a ticket has an archive. */
	val canArchive: Boolean,
) {
	companion object {
		fun of(item: TrashItem) = TrashItemResponse(
			kind = item.kind.wire,
			id = item.id,
			label = item.label,
			parent = item.parent?.let { TrashParentResponse(it.kind, it.id, it.name) },
			holds = item.holds.map { TrashHoldingResponse(it.kind.wire, it.count, it.cascades) },
			deletedAt = item.deletedAt,
			deletedBy = item.deletedBy?.let { TrashActorResponse(it.id, it.displayName) },
			daysLeft = item.daysLeft,
			canArchive = item.canArchive,
		)
	}
}

/**
 * Both tabs in one response. No counts: the drawing's `7` and `64` are the lengths of these
 * two lists, and sending them as their own numbers would be two truths about one read.
 */
data class TrashResponse(
	val trash: List<TrashItemResponse>,
	val archives: List<TrashItemResponse>,
	/** So the screen can print "emptied after 30 days" without hardcoding the server's rule. */
	val retentionDays: Int,
)

/**
 * Screen 26. Read open, writes scoped — the rule `architecture.md` states, with no
 * exception here: the three exits go through `TicketAccess` inside `TicketService`, which is
 * the same gate every other write on a ticket passes.
 */
@RestController
@RequestMapping("/api/trash")
class TrashController(
	private val trash: TrashService,
	private val currentUser: CurrentUser,
) {

	@GetMapping
	fun load(): TrashResponse {
		val view = trash.load()
		return TrashResponse(
			trash = view.trash.map(TrashItemResponse::of),
			archives = view.archives.map(TrashItemResponse::of),
			retentionDays = TRASH_RETENTION_DAYS.toInt(),
		)
	}

	@PostMapping("/{kind}/{id}/restore")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun restore(@PathVariable kind: String, @PathVariable id: UUID) {
		trash.restore(currentUser.require(), TrashKind.from(kind), id)
	}

	@PostMapping("/{kind}/{id}/archive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun archiveInstead(@PathVariable kind: String, @PathVariable id: UUID) {
		trash.archiveInstead(currentUser.require(), TrashKind.from(kind), id)
	}

	/** For good. The one exit that does not come back. */
	@DeleteMapping("/{kind}/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun purge(@PathVariable kind: String, @PathVariable id: UUID) {
		trash.purge(currentUser.require(), TrashKind.from(kind), id)
	}
}
