package dev.kanso.domain

import java.time.LocalDate
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

private inline fun <reified E> parse(values: Array<E>, raw: String): E where E : Enum<E>, E : Wire =
	values.firstOrNull { it.wire == raw }
		?: throw IllegalArgumentException(
			"Unknown ${E::class.simpleName} '$raw' (expected one of ${values.joinToString { it.wire }})"
		)

enum class TicketStatus(override val wire: String) : Wire {
	BACKLOG("backlog"),
	TODO("todo"),
	IN_PROGRESS("in_progress"),
	IN_REVIEW("in_review"),
	DONE("done"),
	CANCELED("canceled");

	/** Label shown in the Notion mirror, where humans read it. */
	val label: String get() = wire.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

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

/** Mirror bookkeeping shared by every entity Kanso pushes to Notion. */
data class MirrorInfo(
	val notionPageId: String? = null,
	val syncState: SyncState = SyncState.PENDING,
	val notionSyncedAt: OffsetDateTime? = null,
	val notionLastEditedTime: OffsetDateTime? = null,
)

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

data class Preferences(
	val theme: Theme = Theme.SYSTEM,
	val accent: Accent = Accent.INDIGO,
	val density: Density = Density.COMFORTABLE,
	val sidebarVisible: Boolean = true,
	val showSyncBadges: Boolean = true,
	val showStatusBar: Boolean = true,
	val defaultTeamId: UUID? = null,
	val onboardedAt: OffsetDateTime? = null,
)

data class TeamMember(val teamId: UUID, val user: User, val role: MemberRole)

data class Project(
	val id: UUID,
	val name: String,
	val status: ProjectStatus,
	val startDate: LocalDate?,
	val endDate: LocalDate?,
	val leadUserId: UUID?,
	val teamId: UUID?,
	val archived: Boolean,
	val mirror: MirrorInfo,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)

data class Ticket(
	val id: UUID,
	val number: Int,
	val teamId: UUID,
	val title: String,
	val description: String?,
	val status: TicketStatus,
	val priority: TicketPriority,
	val startDate: LocalDate?,
	val dueDate: LocalDate?,
	val projectId: UUID?,
	val archived: Boolean,
	val mirror: MirrorInfo,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
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
