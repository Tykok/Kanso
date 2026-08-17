package dev.kanso.docs

import dev.kanso.domain.Wire
import dev.kanso.domain.parse
import dev.kanso.service.BadRequestException
import dev.kanso.service.TicketDetail
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The seven blocks screen 07 draws, and no eighth.
 *
 * Closed here *and* by a `CHECK` in `V9`, for the reason `Model.kt` already states
 * about ticket status: a vocabulary that only one of the two guards is holding drifts
 * the moment something writes to the database that is not this code.
 */
enum class DocBlockKind(override val wire: String) : Wire {
	PARAGRAPH("paragraph"),
	HEADING("heading"),
	NUMBERED_LIST("numbered_list"),
	CHECKBOX("checkbox"),
	CALLOUT("callout"),
	TICKET_LINK("ticket_link"),
	TABLE("table");

	companion object {
		fun from(raw: String): DocBlockKind = parse(entries.toTypedArray(), raw)
	}
}

/** A node of the tree. Team-scoped, parent-nullable; the root is `parentId == null`. */
data class DocFolder(
	val id: UUID,
	val teamId: UUID,
	val parentId: UUID?,
	val name: String,
	val position: Int,
)

/**
 * A page written here.
 *
 * [notionPageId] is null for a page that only ever existed in Kanso, which is the
 * common case and the whole reason this is not `notion_docs`.
 */
data class DocPage(
	val id: UUID,
	val teamId: UUID,
	val folderId: UUID?,
	val title: String,
	val authorId: UUID?,
	val editedById: UUID?,
	val notionPageId: String?,
	val notionUrl: String?,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)

/**
 * One block. [ticketIds] comes from `doc_block_tickets`, never from [content]: the
 * join row is the backlink, and a copy of the id inside the json would be a second
 * definition of it that nothing keeps in step.
 */
data class DocBlock(
	val id: UUID,
	val pageId: UUID,
	val position: Int,
	val kind: DocBlockKind,
	val content: Map<String, Any?>,
	val ticketIds: List<UUID>,
)

/**
 * A page with everything screen 07 draws at once: its blocks, and the tickets those
 * blocks mention, resolved so a ticket link renders a *live* status pill. The "Lié à"
 * rail is these tickets; it is derived rather than stored, because a second table
 * saying which tickets a page links to could disagree with the blocks that link them.
 */
data class DocPageDetail(
	val page: DocPage,
	val blocks: List<DocBlock>,
	val tickets: List<TicketDetail>,
)

data class DocTemplateBlock(val kind: DocBlockKind, val content: Map<String, Any?>)

data class DocTemplate(
	val id: UUID,
	val slug: String,
	val name: String,
	val summary: String,
	val blocks: List<DocTemplateBlock>,
)

/** What `c` inside a document returns: the ticket, and the reference block the page got. */
data class LinkedTicket(val ticket: TicketDetail, val block: DocBlock)

/**
 * What each kind's [DocBlock.content] must carry.
 *
 * Refused rather than defaulted: a checkbox with no `checked` and a table with no
 * `columns` both render as an empty block forever, and an empty block on screen says
 * nothing about why. `ticket_link` requires nothing — its whole content is the join
 * row — and everything not listed is allowed through, so a paragraph may carry
 * formatting this version does not read yet.
 */
private val REQUIRED_KEYS: Map<DocBlockKind, List<String>> = mapOf(
	DocBlockKind.PARAGRAPH to listOf("text"),
	DocBlockKind.HEADING to listOf("text"),
	DocBlockKind.NUMBERED_LIST to listOf("items"),
	DocBlockKind.CHECKBOX to listOf("text", "checked"),
	DocBlockKind.CALLOUT to listOf("text"),
	DocBlockKind.TICKET_LINK to emptyList(),
	DocBlockKind.TABLE to listOf("columns"),
)

fun requireContentFor(kind: DocBlockKind, content: Map<String, Any?>) {
	val missing = REQUIRED_KEYS.getValue(kind).filterNot { it in content }
	if (missing.isNotEmpty()) {
		throw BadRequestException("A ${kind.wire} block needs ${missing.joinToString()}")
	}
}
