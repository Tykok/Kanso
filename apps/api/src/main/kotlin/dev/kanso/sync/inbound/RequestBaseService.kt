package dev.kanso.sync.inbound

import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.RequestBase
import dev.kanso.repo.RequestBaseRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Pointing the instance at a Notion base of requests, and at the team that triages them.
 *
 * Registration is where every fact the siphon cannot work out for itself is supplied by a
 * person: which base, and whose queue. [RequestSiphon] then needs no human and asks no
 * questions, which is what lets it run on a thirty-second timer.
 *
 * Who may do it is settled by what it does rather than by its prefix. It names a database
 * in the connected workspace and files its contents into a team the registrant need not
 * belong to — the same class of act as `SyncAdminController.bootstrapNotion`, guarded the
 * same way, on `SetupController`'s argument. Note what this deliberately does *not* follow:
 * `KAN-55` settled that Notion *discovery* — `sources`, `schema`, `preview`, `people-seen`
 * — stays open to any member, because a non-configurator running an import is the flow
 * those were written for. Choosing a base to browse is not choosing one to wire the
 * instance to permanently, and the guard belongs on the second.
 */
@Service
class RequestBaseService(
	private val bases: RequestBaseRepository,
	private val meta: NotionMetaRepository,
	private val teams: TeamRepository,
) {

	@Transactional(readOnly = true)
	fun all(): List<RequestBase> = bases.findAll()

	/**
	 * Registers [dataSourceId] as a requests base feeding [teamId]'s queue.
	 *
	 * **The mirror's own databases are refused.** `NotionDiscovery` already keeps the import
	 * out of them and says why: "offering to import `Kanso · Tickets` back into Kanso would
	 * duplicate every ticket in the instance". A siphon does it worse — on a timer, forever,
	 * with each pass adopting whatever the last pass pushed. Refused here, at the only moment
	 * a person could have made the mistake, rather than defended against on every poll.
	 *
	 * **The team must exist**, because it is the one thing a request cannot do without:
	 * `V37` has the argument, and `TicketService.create` refuses a null actor with no team.
	 * A dangling id would fail once per page for as long as the base was registered.
	 */
	@Transactional
	fun register(dataSourceId: String, databaseId: String, teamId: UUID): RequestBase {
		val source = dataSourceId.trim()
		val database = databaseId.trim()
		if (source.isEmpty() || database.isEmpty()) {
			throw BadRequestException("A requests base needs both its database id and its data source id")
		}
		val mirrored = meta.findAll()
		if (mirrored.any { it.dataSourceId == source || it.databaseId == database }) {
			throw BadRequestException(
				"That database is one Kanso's own mirror writes to. Siphoning it would adopt the " +
					"instance's own tickets back into itself, on every poll.",
			)
		}
		if (teams.findById(teamId) == null) throw BadRequestException("No team $teamId")
		bases.save(source, database, teamId)
		return RequestBase(source, database, teamId)
	}

	/** Stops the siphon. The tickets it already produced are ordinary tickets and stay. */
	@Transactional
	fun unregister(dataSourceId: String) {
		if (!bases.remove(dataSourceId.trim())) {
			throw NotFoundException("No requests base $dataSourceId")
		}
	}
}
