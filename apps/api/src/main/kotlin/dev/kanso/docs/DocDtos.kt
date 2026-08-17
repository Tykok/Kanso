package dev.kanso.docs

import dev.kanso.api.TicketResponse
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The wire shapes for documents.
 *
 * In the slice's own package rather than in `api/Dtos.kt` — that file is read by every
 * controller and would be five branches appending to one place. `TicketResponse` is
 * imported rather than re-invented: a linked ticket is the same ticket, so it has no
 * business having a second shape here.
 */

data class DocFolderResponse(
	val id: UUID,
	val teamId: UUID,
	val parentId: UUID?,
	val name: String,
	val position: Int,
) {
	companion object {
		fun of(folder: DocFolder) =
			DocFolderResponse(folder.id, folder.teamId, folder.parentId, folder.name, folder.position)
	}
}

/** A page without its blocks: what the tree and the "recently changed" list read. */
data class DocPageResponse(
	val id: UUID,
	val teamId: UUID,
	val folderId: UUID?,
	val title: String,
	val authorId: UUID?,
	val editedById: UUID?,
	/** Null for a page written here. Present when it mirrors one written in Notion. */
	val notionPageId: String?,
	val notionUrl: String?,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
) {
	companion object {
		fun of(page: DocPage) = DocPageResponse(
			id = page.id,
			teamId = page.teamId,
			folderId = page.folderId,
			title = page.title,
			authorId = page.authorId,
			editedById = page.editedById,
			notionPageId = page.notionPageId,
			notionUrl = page.notionUrl,
			createdAt = page.createdAt,
			updatedAt = page.updatedAt,
		)
	}
}

data class DocBlockResponse(
	val id: UUID,
	val pageId: UUID,
	val position: Int,
	val kind: String,
	val content: Map<String, Any?>,
	/** From `doc_block_tickets`, never from [content] — the join row is the backlink. */
	val ticketIds: List<UUID>,
) {
	companion object {
		fun of(block: DocBlock) = DocBlockResponse(
			id = block.id,
			pageId = block.pageId,
			position = block.position,
			kind = block.kind.wire,
			content = block.content,
			ticketIds = block.ticketIds,
		)
	}
}

/**
 * Everything screen 07 draws, in one GET.
 *
 * [tickets] is the "Lié à" rail and the source of every ticket link block's status
 * pill: resolved server-side so the page paints in one round trip, and *live* rather
 * than copied into the block, which is the only reason a ticket link is worth more
 * than typing the identifier.
 */
data class DocPageDetailResponse(
	val page: DocPageResponse,
	val blocks: List<DocBlockResponse>,
	val tickets: List<TicketResponse>,
) {
	companion object {
		fun of(detail: DocPageDetail) = DocPageDetailResponse(
			page = DocPageResponse.of(detail.page),
			blocks = detail.blocks.map(DocBlockResponse::of),
			tickets = detail.tickets.map(TicketResponse::of),
		)
	}
}

/** The template's blocks have no page and no id yet, so they get their own shape. */
data class DocTemplateBlockResponse(val kind: String, val content: Map<String, Any?>)

data class DocTemplateResponse(
	val id: UUID,
	val slug: String,
	val name: String,
	val summary: String,
	val blocks: List<DocTemplateBlockResponse>,
) {
	companion object {
		fun of(template: DocTemplate) = DocTemplateResponse(
			id = template.id,
			slug = template.slug,
			name = template.name,
			summary = template.summary,
			blocks = template.blocks.map { DocTemplateBlockResponse(it.kind.wire, it.content) },
		)
	}
}

// --- requests ----------------------------------------------------------------

data class DocFolderRequest(
	val teamId: UUID,
	val parentId: UUID? = null,
	@field:NotBlank @field:Size(max = 120) val name: String,
)

data class DocFolderPatchRequest(
	@field:Size(max = 120) val name: String? = null,
	val parentId: UUID? = null,
)

data class DocPageRequest(
	val teamId: UUID,
	val folderId: UUID? = null,
	@field:NotBlank @field:Size(max = 200) val title: String,
	/** `cycle-note`, `decision`, `incident-report`, or nothing for an empty page. */
	val templateSlug: String? = null,
)

/**
 * `null` leaves a field alone; naming it in [unset] clears it. The convention
 * `TicketPatchRequest` already uses, and the reason is the same: JSON cannot tell an
 * absent key from an explicit null, and a page may legitimately belong to no folder.
 */
data class DocPagePatchRequest(
	@field:Size(max = 200) val title: String? = null,
	val folderId: UUID? = null,
	val unset: Set<String> = emptySet(),
)

data class DocBlockRequest(
	val kind: String,
	val content: Map<String, Any?> = emptyMap(),
	/** The block this one goes after. Absent appends to the end of the page. */
	val afterBlockId: UUID? = null,
)

data class DocBlockPatchRequest(val content: Map<String, Any?>)

data class DocBlockMoveRequest(val toIndex: Int)

/** `#` — the ticket already exists. */
data class DocTicketLinkRequest(val ticketId: UUID, val afterBlockId: UUID? = null)

/** `c` — a title and nothing else; the page answers every other question. */
data class DocNewTicketRequest(@field:NotBlank @field:Size(max = 200) val title: String)

/** What `c` gives back: the ticket, and the reference block the page received. */
data class LinkedTicketResponse(val ticket: TicketResponse, val block: DocBlockResponse) {
	companion object {
		fun of(linked: LinkedTicket) =
			LinkedTicketResponse(TicketResponse.of(linked.ticket), DocBlockResponse.of(linked.block))
	}
}
