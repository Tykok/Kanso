package dev.kanso.publik

import dev.kanso.domain.MemberRole
import dev.kanso.domain.StatusCategory
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
	/** The team's own key, printed on the card. The *column* is [RoadmapGroup.category]. */
	val status: String,
	/**
	 * What [status] means — carried, not derivable — `KAN-90`.
	 *
	 * The card draws a rule down its left edge for work in progress, and it used to ask
	 * `categoryOf(entry.status)`: a map over Kanso's six, which answers `undefined` for a
	 * word a team invented and does it silently. The owning team's catalogue is the only
	 * thing that knows, and a public page has no team to fetch it from.
	 */
	val category: StatusCategory,
	val votes: Int,
	/** When it shipped. Null for anything not delivered. */
	val completedAt: OffsetDateTime?,
)

/**
 * A column. [count] is the group's own size rather than a number computed elsewhere:
 * the drawing prints a count beside each heading, and a heading that disagrees with the
 * rows under it is worse than no count at all.
 */
/**
 * One column of the public roadmap — a **category** since `KAN-90`, not a status.
 *
 * `ROADMAP_STATUSES`' docstring used to forbid exactly this: folding review into progress
 * "would print a word over a ticket the app calls something else, which is the
 * reformulation the drawing rules out". That held while every team read the same six
 * words. It cannot now — this page is every published ticket in the instance, so keeping
 * the words would give it one column per word per team, growing as teams are added, with
 * `Done` and `Livré` side by side meaning the same thing.
 *
 * So four columns instead of five, `in_progress` and `in_review` together under
 * `started`, which is the same answer `KAN-28` gave for the app's own cross-team lists.
 * The entry still carries its team's word for whoever reads a single card.
 */
data class RoadmapGroup(val category: StatusCategory, val tickets: List<RoadmapEntry>) {
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
	/** The owning team's own word for where it is — printed, not grouped on. */
	val status: String,
	/** What that word means — the same reason [RoadmapEntry.category] carries it. */
	val category: StatusCategory,
	val votes: Int,
	/** Nobody is on it. The drawing's last badge, and a fact, not a label. */
	val unclaimed: Boolean,
	/**
	 * The ticket's own labels, by name — `design system`, `good first step`. The drawing's
	 * middle badges. Names only: a public surface prints no ids, and neither badge draws
	 * the colour the label carries.
	 */
	val labels: List<String>,
	val whereToLook: List<FilePointer>,
	val helpers: List<Helper>,
	/** Other first steps to pick up. Three of them, as drawn. */
	val otherFirstSteps: List<RoadmapEntry>,
	/**
	 * The label the first-step list was narrowed to, or null when no team has defined one
	 * and it is every unclaimed ticket instead.
	 *
	 * On the wire because the eyebrow says which question it answered — `Good first step ·
	 * 12 available` against `Unclaimed · 12 available`. A page that printed the first
	 * sentence over the second list would be claiming a maintainer picked these out.
	 */
	val firstStepLabel: String?,
	/** How many there are to pick up in total, this one included when it qualifies. */
	val availableCount: Int,
)
