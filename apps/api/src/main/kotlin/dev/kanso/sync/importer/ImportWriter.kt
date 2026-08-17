package dev.kanso.sync.importer

import dev.kanso.docs.DocBlockRepository
import dev.kanso.docs.DocService
import dev.kanso.domain.User
import dev.kanso.service.ProjectService
import dev.kanso.service.ScheduleService
import dev.kanso.service.TicketService
import dev.kanso.sync.notion.NotionPage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A base, what it becomes, and the pages that were read out of it. */
data class PlannedBase(
	val base: WorkspaceBase,
	val target: ImportTarget,
	val pages: List<NotionPage>,
)

/**
 * The half of the import that writes, and the only half.
 *
 * It takes pages that are already in memory and never talks to Notion: an import is one
 * transaction, and a transaction that waits on a network call between two inserts holds
 * row locks for as long as Notion feels like taking.
 */
@Service
class ImportWriter(
	private val projects: ProjectService,
	private val tickets: TicketService,
	private val docs: DocService,
	private val blocks: DocBlockRepository,
	private val schedule: ScheduleService,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Transactional
	fun write(actor: User, teamId: UUID, bases: List<PlannedBase>): ImportOutcome = TODO("write")
}
