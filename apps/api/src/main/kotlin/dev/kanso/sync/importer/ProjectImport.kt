package dev.kanso.sync.importer

import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.User
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import dev.kanso.service.ProjectService
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
	private val origins: ImportOriginRepository,
) {

	fun write(
		actor: User,
		base: PlannedBase,
		fallbackTeam: UUID?,
		links: ImportLinks.Resolved,
		rows: ImportedRows,
		people: Map<String, UUID?>,
	): Int {
		var created = 0
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
				// A Notion `people` column names workspace members, and matching one to a
				// Kanso account is what `people` is: the request's own answer to that match,
				// one Notion id to at most one Kanso account. `LEAD` can hold several names —
				// Notion does not stop somebody from naming two — but a project has one lead
				// the way a ticket has many assignees, so the first one that resolves wins.
				// Unlike `TicketImport.resolveAssignees`, nothing here checks team
				// membership: `ProjectService.create` asks only that the account exist.
				leadUserId = base.reader.people(page, ImportField.LEAD).firstNotNullOfOrNull { people[it.id] },
				teamId = teamId,
				docIds = emptyList(),
			).project
			rows.put(OriginKind.PROJECT, page.id, project.id)
			origins.record(ImportOrigin(page.id, OriginKind.PROJECT, project.id, base.base.dataSourceId))
			created++
		}
		return created
	}
}
