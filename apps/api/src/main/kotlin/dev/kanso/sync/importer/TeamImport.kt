package dev.kanso.sync.importer

import dev.kanso.domain.User
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import dev.kanso.service.ConflictException
import dev.kanso.service.TeamService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Teams, then their parents.
 *
 * Two passes rather than one, because a parent can be listed after its child: the first
 * creates every team flat, the second moves them. [TeamService.update] is the only way to
 * set a parent and it refuses a cycle, which is exactly the behaviour wanted here — a
 * workspace whose relations happen to form a loop loses one arrow, not the import.
 */
@Service
class TeamImport(
	private val teams: TeamService,
	private val origins: ImportOriginRepository,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun write(actor: User, base: PlannedBase, rows: ImportedRows): Int {
		var created = 0
		for (page in base.adoptable) {
			val name = requireNotNull(base.reader.title(page)) { "an unadoptable page reached the writer" }
			// The key is derived from the name by `TeamService`, exactly as for a team
			// created by hand: a Notion base has nothing that could serve as one, and a key
			// invented from a column somebody happened to call `Code` would prefix every
			// ticket identifier in the team for as long as it exists.
			val team = teams.create(actor, name, null, null)
			rows.put(OriginKind.TEAM, page.id, team.id)
			origins.record(ImportOrigin(page.id, OriginKind.TEAM, team.id, base.base.dataSourceId))
			created++
		}
		return created
	}

	/**
	 * The second pass: every parent the resolver found, one move at a time — or, failing
	 * that, the base's own [Fallback.parentTeamId].
	 *
	 * Only [PlannedBase.adoptable] pages are moved. A team this run left alone because it
	 * was already imported keeps the parent it has: a second import never updates from
	 * Notion, and re-parenting a team somebody has since moved in Kanso would be the
	 * loudest possible way to break that rule.
	 */
	fun settleParents(actor: User, base: PlannedBase, links: ImportLinks.Resolved, rows: ImportedRows) {
		for (page in base.adoptable) {
			val childId = rows.team(page.id) ?: continue
			// The relation's own answer, else the base's fallback: a team whose parent
			// relation names nothing, or names a page this run did not keep, is not left
			// unparented just because nobody in Notion happened to say where it goes.
			val parentId = links.parentOfTeam[page.id]?.let(rows::team) ?: base.fallback.parentTeamId ?: continue
			val child = teams.get(childId)
			try {
				teams.update(actor, childId, child.name, child.key, parentId)
			} catch (e: ConflictException) {
				// A loop in somebody else's workspace is a fact about that workspace, not a
				// reason to refuse an import of four hundred pages. One arrow is dropped.
				log.info("Dropped an imported team parent: {}", e.message)
			}
		}
	}
}
