package dev.kanso.publik

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

/**
 * The wire shapes for the three routes that answer without a session.
 *
 * They exist rather than serialising [Roadmap] and [ContributorPage] directly for the
 * same reason those types exist rather than reusing `TicketDetail`: one more layer that
 * has to be edited on purpose before anything new reaches a stranger. `PublicLeakTest`
 * asserts against the JSON these produce, so a field added here without a thought is
 * caught by the suite rather than by somebody reading the page.
 *
 * No ids. A public identifier is `KAN-142`, and a UUID would let a visitor line the
 * roadmap up against any other endpoint that ever leaks one.
 */

data class RoadmapEntryResponse(
	val key: String,
	val title: String,
	/** The application's own wire value: `backlog`, `todo`, `in_progress`, … */
	val status: String,
	val votes: Int,
	val deliveredAt: OffsetDateTime?,
) {
	companion object {
		fun of(entry: RoadmapEntry) = RoadmapEntryResponse(
			key = entry.identifier,
			title = entry.title,
			status = entry.status.wire,
			votes = entry.votes,
			deliveredAt = entry.completedAt,
		)
	}
}

data class RoadmapGroupResponse(val status: String, val count: Int, val tickets: List<RoadmapEntryResponse>) {
	companion object {
		fun of(group: RoadmapGroup) = RoadmapGroupResponse(
			status = group.status.wire,
			count = group.count,
			tickets = group.tickets.map(RoadmapEntryResponse::of),
		)
	}
}

data class RoadmapResponse(val groups: List<RoadmapGroupResponse>) {
	companion object {
		fun of(roadmap: Roadmap) = RoadmapResponse(roadmap.groups.map(RoadmapGroupResponse::of))
	}
}

data class FilePointerResponse(val path: String, val note: String?) {
	companion object {
		fun of(pointer: FilePointer) = FilePointerResponse(pointer.path, pointer.note)
	}
}

/** A name and a membership. Never an id, never an address — see [Helper]. */
data class HelperResponse(val displayName: String, val role: String) {
	companion object {
		fun of(helper: Helper) = HelperResponse(helper.displayName, helper.role.wire)
	}
}

data class ContributorResponse(
	val key: String,
	val title: String,
	val explanation: String?,
	val status: String,
	val votes: Int,
	val unclaimed: Boolean,
	/** Label names, never ids and never colours — see [ContributorPage.labels]. */
	val labels: List<String>,
	val whereToLook: List<FilePointerResponse>,
	val helpers: List<HelperResponse>,
	val otherFirstSteps: List<RoadmapEntryResponse>,
	val firstStepLabel: String?,
	val availableCount: Int,
) {
	companion object {
		fun of(page: ContributorPage) = ContributorResponse(
			key = page.identifier,
			title = page.title,
			explanation = page.explanation,
			status = page.status.wire,
			votes = page.votes,
			unclaimed = page.unclaimed,
			labels = page.labels,
			whereToLook = page.whereToLook.map(FilePointerResponse::of),
			helpers = page.helpers.map(HelperResponse::of),
			otherFirstSteps = page.otherFirstSteps.map(RoadmapEntryResponse::of),
			firstStepLabel = page.firstStepLabel,
			availableCount = page.availableCount,
		)
	}
}

data class VoteResponse(val votes: Int, val voted: Boolean) {
	companion object {
		fun of(result: VoteResult) = VoteResponse(result.votes, result.voted)
	}
}

// --- the scoped writes behind them -------------------------------------------

/** No default: a request that forgot to say which way it meant is a 400, not a publish. */
data class PublicationRequest(val public: Boolean)

data class FilePointerRequest(
	@field:NotBlank @field:Size(max = 400) val path: String,
	@field:Size(max = 200) val note: String? = null,
) {
	fun toDomain() = FilePointer(path.trim(), note?.trim()?.ifEmpty { null })
}

data class WhereToLookRequest(@field:Size(max = 12) val files: List<FilePointerRequest> = emptyList())
