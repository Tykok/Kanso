package dev.kanso.trash

import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Screen 26, from the server's side.
 *
 * Nothing here branches on a kind. The four kinds are rows in one closed vocabulary, and
 * each has a [TrashSource] bean; this service owns the **entry** — writing it is
 * `TicketService.delete`'s job, removing it is this file's, and the countdown is read from
 * it — while every source owns its own **entity**. That split is what keeps a source
 * roughly twenty lines and what made landing `doc_pages`, `doc_folders` and `saved_views`
 * three new beans rather than an edit here — this file has not changed to accept them.
 *
 * [load] still answers by omission rather than by pretending: an entry of a kind nobody
 * answers for is skipped, the same way an entry whose entity was destroyed by another path
 * is. Nothing registers a fifth kind today, and `V11`'s `CHECK` is what stops one arriving.
 */
@Service
class TrashService(
	sources: List<TrashSource>,
	private val entries: TrashRepository,
	private val users: UserRepository,
) {

	private val byKind: Map<TrashKind, TrashSource> = sources.associateBy { it.kind }

	/**
	 * Cap on the Archives tab. The trash is bounded by the retention window; the archives
	 * are bounded by nothing at all — the drawing shows 64 of them and an instance that
	 * has been running a year has thousands.
	 */
	private val archiveLimit = 200

	@Transactional(readOnly = true)
	fun load(now: OffsetDateTime = OffsetDateTime.now()): TrashView {
		val entryList = entries.all()
		val actors = users.findAllById(entryList.mapNotNull { it.deletedById }.toSet()).associateBy { it.id }

		val trash = entryList
			.groupBy { it.kind }
			.flatMap { (kind, group) ->
				val source = byKind[kind] ?: return@flatMap emptyList()
				val byId = group.associateBy { it.entityId }
				source.describe(group.map { it.entityId }).map { item ->
					val entry = byId.getValue(item.id)
					item.copy(
						deletedAt = entry.deletedAt,
						deletedBy = entry.deletedById?.let(actors::get),
						daysLeft = entry.daysLeft(now),
						canArchive = source.archivable,
					)
				}
			}
			// Re-sorted after the per-kind grouping, which scattered the order the entries
			// came back in. Newest first, whatever kind.
			.sortedByDescending { it.deletedAt }

		return TrashView(trash = trash, archives = byKind.values.flatMap { it.archived(archiveLimit) })
	}

	@Transactional
	fun restore(actor: User, kind: TrashKind, id: UUID) = exit(kind, id) { source ->
		source.restore(actor, id)
	}

	/**
	 * The middle exit: not gone, not coming back either. The countdown stops because
	 * somebody made a decision, which is precisely the difference between the two tabs.
	 */
	@Transactional
	fun archiveInstead(actor: User, kind: TrashKind, id: UUID) = exit(kind, id) { source ->
		source.archiveInstead(actor, id)
	}

	@Transactional
	fun purge(actor: User, kind: TrashKind, id: UUID) = exit(kind, id) { source ->
		source.purge(actor, id)
	}

	/**
	 * The retention sweep, and the reason the countdown is not a decoration.
	 *
	 * Runs with no actor: thirty days *is* the consent, and there is nobody to ask by the
	 * time it fires. Returns how many entries it emptied so the caller can log a number
	 * rather than a reassurance.
	 *
	 * An entry whose entity another path already destroyed has no source row to purge; the
	 * entry still goes, which is the only place in this file that cleans one up.
	 */
	@Transactional
	fun empty(now: OffsetDateTime = OffsetDateTime.now()): Int {
		val expired = entries.expired(now)
		for (entry in expired) {
			byKind[entry.kind]?.purge(actor = null, id = entry.entityId)
			entries.remove(entry.kind, entry.entityId)
		}
		return expired.size
	}

	/**
	 * The entity first, then the entry — in that order, and it is load-bearing: a source
	 * refusing on [dev.kanso.service.TicketAccess] must leave the thing in the trash
	 * rather than quietly losing its countdown to a request that was denied.
	 */
	private inline fun exit(kind: TrashKind, id: UUID, act: (TrashSource) -> Unit) {
		entries.find(kind, id) ?: throw BadRequestException("Nothing of kind ${kind.wire} with id $id is in the trash")
		val source = byKind[kind] ?: throw BadRequestException("Nothing answers for ${kind.wire} yet")
		act(source)
		entries.remove(kind, id)
	}
}
