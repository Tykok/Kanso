package dev.kanso.docs

import dev.kanso.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * `DocumentController` and not `DocController`: `dev.kanso.api.DocController` already
 * exists and serves `/api/docs` — the `notion_docs` index. Two beans of one simple name
 * is a startup failure, and the name is the honest place to record that these are two
 * different things sharing a path prefix.
 *
 * The prefix is shared on purpose. `GET /api/docs` stays what it was: the index rows
 * relations point at. Everything below it — `/folders`, `/pages`, `/blocks`,
 * `/templates` — is the documents written here.
 *
 * `GET`s take no actor and are open, writes go through `TicketAccess` inside the
 * service. That is `architecture.md`'s rule and no endpoint here is an exception.
 */
@RestController
@RequestMapping("/api/docs")
class DocumentController(
	private val documents: DocService,
	private val blocks: DocBlockService,
	private val currentUser: CurrentUser,
) {

	// --- folders -------------------------------------------------------------

	@GetMapping("/folders")
	fun folders(@RequestParam(required = false) teamId: UUID?): List<DocFolderResponse> =
		documents.folders(teamId).map(DocFolderResponse::of)

	@PostMapping("/folders")
	@ResponseStatus(HttpStatus.CREATED)
	fun createFolder(@Valid @RequestBody request: DocFolderRequest): DocFolderResponse =
		DocFolderResponse.of(
			documents.createFolder(currentUser.require(), request.teamId, request.parentId, request.name),
		)

	@PatchMapping("/folders/{id}")
	fun patchFolder(
		@PathVariable id: UUID,
		@Valid @RequestBody request: DocFolderPatchRequest,
	): DocFolderResponse = DocFolderResponse.of(
		documents.updateFolder(currentUser.require(), id, request.name, request.parentId),
	)

	@DeleteMapping("/folders/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun deleteFolder(@PathVariable id: UUID) = documents.deleteFolder(currentUser.require(), id)

	// --- pages ---------------------------------------------------------------

	/** Newest edit first. `limit` bounds a list nobody paginates, not a page size. */
	@GetMapping("/pages")
	fun pages(
		@RequestParam(required = false) teamId: UUID?,
		@RequestParam(required = false) folderId: UUID?,
		@RequestParam(defaultValue = "50") limit: Int,
	): List<DocPageResponse> =
		documents.pages(teamId, folderId, limit.coerceIn(1, 200)).map(DocPageResponse::of)

	@GetMapping("/pages/{id}")
	fun page(@PathVariable id: UUID): DocPageDetailResponse =
		DocPageDetailResponse.of(documents.page(id))

	@PostMapping("/pages")
	@ResponseStatus(HttpStatus.CREATED)
	fun createPage(@Valid @RequestBody request: DocPageRequest): DocPageDetailResponse =
		DocPageDetailResponse.of(
			documents.createPage(
				actor = currentUser.require(),
				teamId = request.teamId,
				folderId = request.folderId,
				title = request.title,
				templateSlug = request.templateSlug,
			),
		)

	@PatchMapping("/pages/{id}")
	fun patchPage(
		@PathVariable id: UUID,
		@Valid @RequestBody request: DocPagePatchRequest,
	): DocPageDetailResponse = DocPageDetailResponse.of(
		documents.updatePage(currentUser.require(), id, request.title, request.folderId, request.unset),
	)

	@DeleteMapping("/pages/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun deletePage(@PathVariable id: UUID) = documents.deletePage(currentUser.require(), id)

	// --- blocks --------------------------------------------------------------

	@PostMapping("/pages/{id}/blocks")
	@ResponseStatus(HttpStatus.CREATED)
	fun addBlock(@PathVariable id: UUID, @RequestBody request: DocBlockRequest): DocBlockResponse =
		DocBlockResponse.of(
			blocks.addBlock(
				actor = currentUser.require(),
				pageId = id,
				kind = DocBlockKind.from(request.kind),
				content = request.content,
				afterBlockId = request.afterBlockId,
			),
		)

	@PatchMapping("/blocks/{id}")
	fun patchBlock(@PathVariable id: UUID, @RequestBody request: DocBlockPatchRequest): DocBlockResponse =
		DocBlockResponse.of(blocks.updateBlock(currentUser.require(), id, request.content))

	/** Returns the whole page's blocks: a move renumbers every one of them. */
	@PutMapping("/blocks/{id}/position")
	fun moveBlock(
		@PathVariable id: UUID,
		@RequestBody request: DocBlockMoveRequest,
	): List<DocBlockResponse> =
		blocks.moveBlock(currentUser.require(), id, request.toIndex).map(DocBlockResponse::of)

	@DeleteMapping("/blocks/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun deleteBlock(@PathVariable id: UUID) = blocks.deleteBlock(currentUser.require(), id)

	// --- the two gestures inside a document ----------------------------------

	/** `#` — mention a ticket that already exists. */
	@PostMapping("/pages/{id}/tickets")
	@ResponseStatus(HttpStatus.CREATED)
	fun linkTicket(
		@PathVariable id: UUID,
		@RequestBody request: DocTicketLinkRequest,
	): DocBlockResponse = DocBlockResponse.of(
		blocks.linkTicket(currentUser.require(), id, request.ticketId, request.afterBlockId),
	)

	/**
	 * `c` — a new ticket, already attached. Not `POST /api/tickets`: the point of the
	 * gesture is that the link cannot be forgotten, and two requests can be.
	 */
	@PostMapping("/pages/{id}/tickets/new")
	@ResponseStatus(HttpStatus.CREATED)
	fun createLinkedTicket(
		@PathVariable id: UUID,
		@Valid @RequestBody request: DocNewTicketRequest,
	): LinkedTicketResponse =
		LinkedTicketResponse.of(blocks.createLinkedTicket(currentUser.require(), id, request.title))

	// --- templates -----------------------------------------------------------

	@GetMapping("/templates")
	fun templates(): List<DocTemplateResponse> = documents.templates().map(DocTemplateResponse::of)
}
