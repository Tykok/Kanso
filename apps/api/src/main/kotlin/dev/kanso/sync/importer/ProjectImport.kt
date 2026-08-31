package dev.kanso.sync.importer

import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.User
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import dev.kanso.repo.UserRepository
import dev.kanso.service.ProjectService
import dev.kanso.sync.notion.NotionPage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * A base whose pages *are* projects — the other shape, next to a base that becomes one.
 *
 * `IN_PROGRESS` rather than `PLANNED` for the same reason the old single-project import
 * chose it: these are works somebody has already been doing somewhere else, and a project
 * full of half-finished tickets that calls itself planned is wrong on the one screen that
 * reads project status. The default lives here rather than in the reader because null
 * means "nobody said", and only the thing writing the row gets to decide what to write
 * instead.
 */
@Service
class ProjectImport(
	private val projects: ProjectService,
	private val users: UserRepository,
	private val origins: ImportOriginRepository,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun write(
		actor: User,
		base: PlannedBase,
		fallbackTeam: UUID?,
		links: ImportLinks.Resolved,
		rows: ImportedRows,
		people: Map<String, UUID?>,
	): Int {
		var created = 0
		// One query for the whole base, not one per project — see [existingAccounts] for why
		// this checks existence and nothing else: `ProjectService.create` refuses a
		// `leadUserId` `users` cannot find, but Kanso itself has no team-membership rule for a
		// lead to be held to either.
		val existingAccounts = existingAccounts(users, base, ImportField.LEAD, people)
		for (page in base.adoptable) {
			// The team its own relation named, else the base's own answer, else the
			// request's — one of which `NotionImportService.perform` guarantees is present
			// for a `PROJECTS` base, but `ProjectService.create` is left to accept the
			// nullable type as it is rather than being handed a promise it cannot check.
			val teamId = links.teamOfProject[page.id]?.let(rows::team)
				?: base.fallback.teamId
				?: fallbackTeam
			val project = projects.create(
				name = requireNotNull(base.reader.title(page)) { "an unadoptable page reached the writer" },
				status = base.reader.projectStatus(page) ?: ProjectStatus.IN_PROGRESS,
				start = base.reader.start(page),
				end = base.reader.end(page),
				leadUserId = resolveLead(base, page, people, existingAccounts),
				teamId = teamId,
				docIds = emptyList(),
			).project
			rows.put(OriginKind.PROJECT, page.id, project.id)
			origins.record(ImportOrigin(page.id, OriginKind.PROJECT, project.id, base.base.dataSourceId))
			created++
		}
		return created
	}

	/**
	 * A Notion `people` column names workspace members, and matching one to a Kanso
	 * account is what `people` is: the request's own answer to that match, one Notion id
	 * to at most one Kanso account. `LEAD` can hold several names — Notion does not stop
	 * somebody from naming two — but a project has one lead the way a ticket has many
	 * assignees, so the first *resolved and still-existing* one wins, not just the first
	 * one listed: skipping past a name nobody matched, or one matched to an account since
	 * deleted, to the next candidate is what keeps a stale mapping from silently emptying
	 * the field instead of finding the next best answer already on the page.
	 *
	 * Nothing counts a dropped lead the way [TicketsWritten.droppedAssignees] counts a
	 * dropped assignee — `ImportOutcome` has no field for it, and a `LEAD` column names at
	 * most one winner, not four hundred, so a count would only ever say 0 or 1 per project.
	 * It is still worth a trace: a project silently losing the lead it was mapped to is
	 * not nothing, so it is logged the way [TicketLinks] logs a dropped relation.
	 */
	private fun resolveLead(
		base: PlannedBase,
		page: NotionPage,
		people: Map<String, UUID?>,
		existingAccounts: Set<UUID>,
	): UUID? {
		val resolved = base.reader.people(page, ImportField.LEAD).mapNotNull { people[it.id] }
		resolved.filterNot { it in existingAccounts }.forEach {
			log.info("Dropped an imported project lead on {}: account {} no longer exists", page.id, it)
		}
		return resolved.firstOrNull { it in existingAccounts }
	}
}
