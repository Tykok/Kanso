package dev.kanso.sync.importer

import dev.kanso.domain.User
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import dev.kanso.service.TeamService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * What one teams base produced, and the pages it could not.
 *
 * [refused] is not [PlannedBase.skippedPages]: those pages are refused by the *reader*,
 * before a transaction is open, and every base has them. These are pages the reader
 * accepted and the writer then could not key — the one per-page failure that only shows up
 * mid-write — and they join the same [ImportOutcome.skipped] list, because a reader asking
 * "what did this import not bring over" is asking one question.
 */
data class TeamsWritten(val teams: Int, val refused: List<SkippedPage>)

/**
 * Teams, then their parents.
 *
 * Two passes rather than one, because a parent can be listed after its child: the first
 * creates every team flat, the second moves them. [TeamService.update] is the only way to
 * set a parent and it refuses a cycle, which is exactly the behaviour wanted here — a
 * workspace whose relations happen to form a loop loses one arrow, not the import.
 *
 * Both refusals are *asked for* before the call rather than caught after it —
 * [TeamService.derivableKey] and [TeamService.moveRefusal]. A `try`/`catch` around either
 * service call is the shape this file used to have and must never have again: it reads as
 * if it worked and it does not, because both services are `@Transactional` and merely
 * participate in [ImportWriter]'s transaction, so the exception marks the whole run
 * rollback-only on its way out of them — before any `catch` here can run. [ImportWriter]
 * has the full rule.
 */
@Service
class TeamImport(
	private val teams: TeamService,
	private val origins: ImportOriginRepository,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun write(actor: User, base: PlannedBase, rows: ImportedRows): TeamsWritten {
		var created = 0
		val refused = mutableListOf<SkippedPage>()
		for (page in base.adoptable) {
			val name = requireNotNull(base.reader.title(page)) { "an unadoptable page reached the writer" }
			// The key is derived from the name by `TeamService`, exactly as for a team
			// created by hand: a Notion base has nothing that could serve as one, and a key
			// invented from a column somebody happened to call `Code` would prefix every
			// ticket identifier in the team for as long as it exists.
			//
			// Asked before the insert, because the answer can be no. `TeamService.resolveKey`
			// gives up after ninety-nine collisions, so a base holding a hundredth name that
			// shares its first three alphanumerics with the others has one page it cannot
			// key: dropped and counted, like every other per-page refusal in this import —
			// one page must not roll back a run of four hundred, and `resolveKey` is right to
			// refuse rather than invent a key nobody could read.
			if (teams.derivableKey(name) == null) {
				log.info("Dropped an imported team: no free key can be derived from '{}'", name)
				refused += SkippedPage(base.base.name, page.id, "Kanso could not derive a free team key from '$name'")
				continue
			}
			// Still with no key of its own, so `create` derives the same one it was just
			// asked about: handing the derived key back would route into `validateKey`, a
			// second path with a throw of its own, which is exactly what was being avoided.
			val team = teams.create(actor, name, null, null)
			rows.put(OriginKind.TEAM, page.id, team.id)
			origins.record(ImportOrigin(page.id, OriginKind.TEAM, team.id, base.base.dataSourceId))
			created++
		}
		return TeamsWritten(created, refused)
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
			// A loop in somebody else's workspace is a fact about that workspace, not a
			// reason to refuse an import of four hundred pages. One arrow is dropped — and
			// the question is put to `TeamService` before the move rather than after it,
			// which is the only way the rest of the run survives the answer.
			val refusal = teams.moveRefusal(child, parentId)
			if (refusal != null) {
				log.info("Dropped an imported team parent: {}", refusal)
				continue
			}
			teams.update(actor, childId, child.name, child.key, parentId)
		}
	}
}
