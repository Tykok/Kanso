package dev.kanso.publik

import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketStatus
import java.time.OffsetDateTime

/**
 * What a stranger sees. Deliberately not [dev.kanso.service.TicketDetail] with fields
 * dropped on the way out: a projection built by omission stays correct only as long as
 * everyone who adds a field remembers to omit it here too, and screen 27 is the one
 * surface in Kanso where forgetting is a disclosure rather than a cosmetic bug.
 *
 * So these are separate types with no path back to a `Ticket`, a `User` or a `Team`.
 */

/** One row of the roadmap: a title, how many people want it, and nothing else. */
data class RoadmapEntry(
	/** `KAN-142`. The only identifier a public surface ever prints. */
	val identifier: String,
	val title: String,
	val status: TicketStatus,
	val votes: Int,
	/** When it shipped. Null for anything not delivered. */
	val completedAt: OffsetDateTime?,
)

/**
 * A column. [count] is the group's own size rather than a number computed elsewhere:
 * the drawing prints a count beside each heading, and a heading that disagrees with the
 * rows under it is worse than no count at all.
 */
data class RoadmapGroup(val status: TicketStatus, val tickets: List<RoadmapEntry>) {
	val count: Int get() = tickets.size
}

data class Roadmap(val groups: List<RoadmapGroup>)

/** A path worth opening first, and why it is on the list. */
data class FilePointer(val path: String, val note: String?)

/**
 * Somebody a contributor can talk to: a member of the ticket's team, by display name.
 *
 * The name is a disclosure and an intentional one — screen 28's whole purpose is to
 * tell a stranger who to ask — but it is the *only* one: no email, no id, no avatar
 * URL, nothing that could be joined against anything. [role] is the app's own word for
 * the membership, which is as close as the schema gets to the drawing's "reviews the
 * PRs"; inventing a speciality nobody recorded would be worse than saying less.
 */
data class Helper(val displayName: String, val role: MemberRole)

/** Screen 28: one open ticket with enough context around it to start on it. */
data class ContributorPage(
	val identifier: String,
	val title: String,
	/** The ticket's description — the explanation of the problem. */
	val explanation: String?,
	val status: TicketStatus,
	val votes: Int,
	/** Nobody is on it. The drawing's third badge, and a fact, not a label. */
	val unclaimed: Boolean,
	val whereToLook: List<FilePointer>,
	val helpers: List<Helper>,
	/** Other published tickets nobody has claimed. Three of them, as drawn. */
	val otherFirstSteps: List<RoadmapEntry>,
	/** How many unclaimed published tickets there are in total, this one included. */
	val unclaimedCount: Int,
)
