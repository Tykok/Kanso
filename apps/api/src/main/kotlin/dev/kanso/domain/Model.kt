package dev.kanso.domain

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Closed vocabularies. Notion will happily invent a select option for anything
 * you send it, so an unknown value coming back from the mirror is an error to
 * report, never a new status to adopt.
 */
interface Wire {
	val wire: String
}

internal inline fun <reified E> parse(values: Array<E>, raw: String): E where E : Enum<E>, E : Wire =
	values.firstOrNull { it.wire == raw }
		?: throw IllegalArgumentException(
			"Unknown ${E::class.simpleName} '$raw' (expected one of ${values.joinToString { it.wire }})"
		)

/**
 * What a status *means*, as opposed to what it is called.
 *
 * Not a [Wire]: nothing writes a category to the database or sends one to Notion, so
 * there is no raw string to parse back and no `CHECK` constraint to keep honest. It is a
 * reading of the status column, and the status column already has both.
 *
 * The point is that "is this finished", "has anybody started" and "is this still open"
 * are questions four screens ask — the burndown, the board, the workload chart, the
 * public roadmap — and each one used to answer by naming statuses. Naming a category
 * instead is what lets a seventh status exist without those four quietly going wrong.
 */
enum class StatusCategory {
	BACKLOG,
	UNSTARTED,
	STARTED,
	COMPLETED,
	CANCELED,
}

enum class TicketStatus(override val wire: String) : Wire {
	BACKLOG("backlog"),
	TODO("todo"),
	IN_PROGRESS("in_progress"),
	IN_REVIEW("in_review"),
	DONE("done"),
	CANCELED("canceled");

	/** Label shown in the Notion mirror, where humans read it. */
	val label: String get() = wire.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

	/**
	 * Derived, never stored: the mapping is the definition, so there is no second copy on
	 * disk that could disagree with this one. `in_review` is [StatusCategory.STARTED]
	 * because somebody is holding it — a reviewer is work in flight, and a burndown that
	 * called review "not started" would draw the wrong day.
	 */
	val category: StatusCategory get() = when (this) {
		BACKLOG -> StatusCategory.BACKLOG
		TODO -> StatusCategory.UNSTARTED
		IN_PROGRESS, IN_REVIEW -> StatusCategory.STARTED
		DONE -> StatusCategory.COMPLETED
		CANCELED -> StatusCategory.CANCELED
	}

	companion object {
		fun from(raw: String): TicketStatus = parse(entries.toTypedArray(), raw)
		fun fromLabel(label: String): TicketStatus? =
			entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
	}
}

enum class TicketPriority(override val wire: String) : Wire {
	NONE("none"), LOW("low"), MEDIUM("medium"), HIGH("high"), URGENT("urgent");

	val label: String get() = wire.replaceFirstChar(Char::uppercase)

	companion object {
		fun from(raw: String): TicketPriority = parse(entries.toTypedArray(), raw)
		fun fromLabel(label: String): TicketPriority? =
			entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
	}
}

enum class ProjectStatus(override val wire: String) : Wire {
	PLANNED("planned"), IN_PROGRESS("in_progress"), PAUSED("paused"), COMPLETED("completed"), CANCELED("canceled");

	val label: String get() = wire.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

	companion object {
		fun from(raw: String): ProjectStatus = parse(entries.toTypedArray(), raw)
		fun fromLabel(label: String): ProjectStatus? =
			entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
	}
}

/**
 * Whether a project will land — which is a different question from [ProjectStatus], and
 * deliberately not derivable from it.
 *
 * Status says where the work *is*: planned, in progress, paused, completed. Health says
 * whether it will arrive. A project can sit at `in_progress` for eleven weeks while
 * quietly becoming undeliverable, and no reading of the status column can say so, because
 * nothing about the status has changed. So health is posted by a person, with a sentence
 * under it, and nothing here maps one vocabulary onto the other in either direction.
 *
 * There is no fourth value and no `UNKNOWN`. A project nobody has assessed answers null —
 * see `ProjectDetail.health` — because "nobody has said" is the absence of a judgement
 * rather than a judgement of its own, and giving it a name on this enum would invite
 * somebody to store it.
 */
enum class ProjectHealth(override val wire: String) : Wire {
	ON_TRACK("on_track"), AT_RISK("at_risk"), OFF_TRACK("off_track");

	val label: String get() = wire.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

	companion object {
		fun from(raw: String): ProjectHealth = parse(entries.toTypedArray(), raw)
	}
}

enum class SyncState(override val wire: String) : Wire {
	PENDING("pending"), SYNCED("synced"), FAILED("failed"), DISABLED("disabled");

	companion object {
		fun from(raw: String): SyncState = parse(entries.toTypedArray(), raw)
	}
}

enum class MemberRole(override val wire: String) : Wire {
	MEMBER("member"), ADMIN("admin");

	companion object {
		fun from(raw: String): MemberRole = parse(entries.toTypedArray(), raw)
	}
}

/**
 * What an activity row is about. Four kinds of thing, one table: a feed reads them
 * together, and four tables would be four queries to merge in the client.
 */
enum class ActivityEntity(override val wire: String) : Wire {
	TICKET("ticket"), PROJECT("project"), TEAM("team"), DOC("doc");

	companion object {
		fun from(raw: String): ActivityEntity = parse(entries.toTypedArray(), raw)
	}
}

/**
 * What happened. Closed, and enforced twice: here, so a typo in a service is a
 * compile error, and by a CHECK on `activity.kind`, so a row written by anything else
 * is refused — the same two-sided guard `user_preferences` already has.
 *
 * A row per *scalar* that changed, which is why there is no generic `updated`: a feed
 * that can only say "Tykok updated KAN-142" is a feed nobody reads.
 */
enum class ActivityKind(override val wire: String) : Wire {
	CREATED("created"),
	STATUS_CHANGED("status_changed"),
	PRIORITY_CHANGED("priority_changed"),
	ASSIGNED("assigned"),
	UNASSIGNED("unassigned"),
	RENAMED("renamed"),
	SCHEDULED("scheduled"),
	ARCHIVED("archived"),
	COMMENTED("commented"),
	LABELLED("labelled"),
	MIRROR_PUSHED("mirror_pushed"),

	/**
	 * Work that outlived the cycle it was committed to. Not a `STATUS_CHANGED` and not
	 * a second `CREATED`: nothing about the ticket changed, only the plan it belongs
	 * to, and the feed has to be able to say that in the ticket's own history.
	 */
	CARRIED_OVER("carried_over"),

	/**
	 * Somebody said how a project is going. The first kind written against
	 * [ActivityEntity.PROJECT] — until this, the feed on the project page had nothing to
	 * draw — and the one whose row is worth most later: "when did this start being at
	 * risk, and who said so" is a question about history, and this is the history.
	 *
	 * Not a [STATUS_CHANGED]: no status changed, and the two are kept apart everywhere
	 * else for the reason [ProjectHealth] gives. The payload carries `to`, `from` when
	 * there was a previous health, and the update's id — never its body, following
	 * [COMMENTED]: an activity row outlives what it describes.
	 */
	HEALTH_POSTED("health_posted"),

	/**
	 * A ticket re-sized. Its own kind for the reason there is no generic `updated`:
	 * `estimate` is a scalar of a ticket like the status and the priority, and going
	 * from a 3 to a 13 is the decision somebody comes back looking for three weeks
	 * later. Both directions and both ends of the range, including un-sizing — an
	 * estimate withdrawn is as much a judgement as one made.
	 */
	ESTIMATED("estimated");

	companion object {
		fun from(raw: String): ActivityKind = parse(entries.toTypedArray(), raw)
	}
}

/** Mirror bookkeeping shared by every entity Kanso pushes to Notion. */
data class MirrorInfo(
	val notionPageId: String? = null,
	val syncState: SyncState = SyncState.PENDING,
	val notionSyncedAt: OffsetDateTime? = null,
	val notionLastEditedTime: OffsetDateTime? = null,
)

/**
 * An instant with an explicit granularity.
 *
 * [hasTime] false means the value names a *day*, not a moment: it is stored as an
 * instant so the scheduler can subtract it, and rendered without conversion so it
 * reads as the same day for every reader. True means it names a moment and is
 * converted to the reader's timezone.
 */
data class KansoInstant(val at: OffsetDateTime, val hasTime: Boolean)

data class Team(
	val id: UUID,
	val name: String,
	val key: String,
	val parentTeamId: UUID?,
	val archived: Boolean,
	val ticketCounter: Int,
	val mirror: MirrorInfo,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)

/**
 * Who may configure the instance itself — distinct from [MemberRole], which is
 * about belonging to a team. The owner is whoever completed the first-run setup.
 */
enum class InstanceRole(override val wire: String) : Wire {
	OWNER("owner"), ADMIN("admin"), MEMBER("member");

	val canConfigureInstance: Boolean get() = this == OWNER || this == ADMIN

	companion object {
		fun from(raw: String): InstanceRole = parse(entries.toTypedArray(), raw)
	}
}

data class User(
	val id: UUID,
	val email: String,
	val displayName: String,
	val notionPersonId: String?,
	val avatarUrl: String?,
	val oidcProvider: String?,
	val oidcSubject: String?,
	val active: Boolean,
	val lastLoginAt: OffsetDateTime?,
	val createdAt: OffsetDateTime,
	val instanceRole: InstanceRole = InstanceRole.MEMBER,
	/** Present only for accounts that can sign in with a password. */
	val hasPassword: Boolean = false,
)

enum class Theme(override val wire: String) : Wire {
	SYSTEM("system"), LIGHT("light"), DARK("dark");

	companion object {
		fun from(raw: String): Theme = parse(entries.toTypedArray(), raw)
	}
}

enum class Accent(override val wire: String) : Wire {
	INDIGO("indigo"), BLUE("blue"), GREEN("green"), AMBER("amber"), ROSE("rose"), VIOLET("violet");

	companion object {
		fun from(raw: String): Accent = parse(entries.toTypedArray(), raw)
	}
}

enum class Density(override val wire: String) : Wire {
	COMFORTABLE("comfortable"), COMPACT("compact");

	companion object {
		fun from(raw: String): Density = parse(entries.toTypedArray(), raw)
	}
}

/**
 * What `↵` does to the selected row: open the detail panel beside the list, or leave
 * the list for the ticket's own page.
 *
 * A preference rather than a per-press choice — `⇧↵` already names the page
 * explicitly, so this only decides which of the two the unmodified key is.
 */
enum class OpenTicket(override val wire: String) : Wire {
	PANEL("panel"), PAGE("page");

	companion object {
		fun from(raw: String): OpenTicket = parse(entries.toTypedArray(), raw)
	}
}

data class Preferences(
	val theme: Theme = Theme.SYSTEM,
	val accent: Accent = Accent.INDIGO,
	val density: Density = Density.COMFORTABLE,
	val sidebarVisible: Boolean = true,
	val showSyncBadges: Boolean = true,
	val showStatusBar: Boolean = true,
	val openTicket: OpenTicket = OpenTicket.PANEL,
	val defaultTeamId: UUID? = null,
	val onboardedAt: OffsetDateTime? = null,
	/**
	 * Points per working day, as this person estimates their own pace. Null means they
	 * have not said — never zero, which would be a claim that they deliver nothing.
	 *
	 * A seed, not a setting: `EffectiveVelocityService` stops consulting it once two
	 * closed cycles can measure the same person, and nothing in Kanso ever writes it
	 * except the person themselves. `V24` argues both halves.
	 */
	val declaredVelocity: Double? = null,
)

data class TeamMember(val teamId: UUID, val user: User, val role: MemberRole)

data class Project(
	val id: UUID,
	val name: String,
	val status: ProjectStatus,
	val start: KansoInstant?,
	val end: KansoInstant?,
	val leadUserId: UUID?,
	val teamId: UUID?,
	val archived: Boolean,
	val mirror: MirrorInfo,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)

/**
 * The effort scale: a truncated Fibonacci sequence, and nothing between its values.
 *
 * A closed vocabulary like the statuses, but of numbers, so it is a [SCALE] and a check
 * rather than an enum — `THIRTEEN` would name nothing the number does not already say,
 * and every reader of this column adds it up. The gaps are the point: a free integer
 * invites someone to write 7, and 7 is an argument about half a point rather than an
 * estimate, while a short scale forces the choice and keeps two people's 5 comparable.
 *
 * Enforced here so a bad value is one sentence with the vocabulary in it, and again by
 * `tickets_estimate_chk` so a writer that never came through Kotlin is refused too — the
 * same two-sided guard the statuses have.
 *
 * Null is not on the scale and is not zero. "Not estimated yet" and "estimated at zero"
 * are different states, and the day they are conflated every average computed out of
 * this column starts reading as measured when it is invented.
 */
object EffortPoints {

	val SCALE = listOf(1, 2, 3, 5, 8, 13)

	/** Null passes: an absent estimate is a legitimate value, and the only way back to one. */
	fun from(value: Int?): Int? = value?.also {
		if (it !in SCALE) {
			throw IllegalArgumentException(
				"Unknown estimate '$it' (expected one of ${SCALE.joinToString()})",
			)
		}
	}
}

/**
 * A unit of work, which may not belong to anybody yet.
 *
 * [teamId] and [number] are null together or not at all — `tickets_team_number_together_chk`
 * refuses every other pairing, because `KAN-142` is the pair and neither half names
 * anything alone. A ticket in that state is a draft: it has no identifier to print, it is
 * in no team-scoped list, and [createdBy] is the only thing left that says who may touch
 * it. Attaching a team takes that team's next number and ends all three at once.
 *
 * [createdBy] is history rather than a permission once a team is named — see `TicketAccess`,
 * which stops reading it the moment [teamId] is set — and null for every row written before
 * `V20`, which no rule can hand to anyone.
 */
data class Ticket(
	val id: UUID,
	val number: Int?,
	val teamId: UUID?,
	val createdBy: UUID?,
	val title: String,
	val description: String?,
	val status: TicketStatus,
	val priority: TicketPriority,
	/** Points, off [EffortPoints.SCALE]. Null means nobody has sized it — never zero. */
	val estimate: Int?,
	val start: KansoInstant?,
	val due: KansoInstant?,
	val completedAt: OffsetDateTime?,
	val projectId: UUID?,
	val archived: Boolean,
	val mirror: MirrorInfo,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)

/**
 * A word a team puts on its work.
 *
 * Team-scoped, so two teams may both own `sync` without arguing about which of them
 * means it. [colour] is an [Accent] rather than a string of hex: the closed vocabulary
 * the theme already speaks is the one a pill can be drawn in, and the database refuses
 * anything outside it.
 */
data class Label(
	val id: UUID,
	val teamId: UUID,
	val name: String,
	val colour: Accent,
)

data class NotionDoc(
	val id: UUID,
	/** The real page someone wrote; Kanso only references it. */
	val notionPageId: String,
	val title: String?,
	val url: String?,
	/**
	 * Kanso's index row in the mirrored Docs database. A Notion relation can only
	 * target pages inside its own data source, so projects and tickets relate to
	 * this row rather than to [notionPageId] directly.
	 */
	val mirrorPageId: String? = null,
	val syncState: SyncState = SyncState.PENDING,
	val notionSyncedAt: OffsetDateTime? = null,
)
