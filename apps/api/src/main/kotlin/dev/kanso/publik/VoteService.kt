package dev.kanso.publik

import dev.kanso.db.Votes
import dev.kanso.service.NotFoundException
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/** How the roadmap answers a click: the new total, and whether this voter is in it. */
data class VoteResult(val votes: Int, val voted: Boolean)

/**
 * Voting, from a reader with no session.
 *
 * The ticket is resolved through [PublicRoadmapRepository] and no other way, so a vote
 * cannot be used to discover a private ticket: an unpublished key is a 404, exactly as
 * it is for a read, and the response is identical to the one for a key that was never
 * allocated.
 *
 * A second click is not an error. `insertIgnore` onto the `(ticket_id, voter_key)`
 * primary key makes the endpoint idempotent, which is what a client behind a flaky
 * connection needs; returning a 409 would only teach the interface to treat "you have
 * already voted" as a failure and then hide it again.
 */
@Service
class VoteService(
	private val published: PublicRoadmapRepository,
) {

	@Transactional
	fun vote(teamKey: String, number: Int, voterKey: String): VoteResult {
		val row = published.findPublishedByKey(teamKey, number)
			?: throw NotFoundException("No published ticket ${teamKey.uppercase()}-$number")

		Votes.insertIgnore {
			it[ticketId] = row.id
			it[Votes.voterKey] = voterKey
			it[createdAt] = OffsetDateTime.now()
		}

		return VoteResult(votes = published.voteCounts(listOf(row.id))[row.id] ?: 0, voted = true)
	}
}
