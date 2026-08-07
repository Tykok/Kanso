package dev.kanso.api

import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.MemberRole
import dev.kanso.domain.NotionDoc
import dev.kanso.domain.Project
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.Team
import dev.kanso.domain.TeamMember
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.service.BadRequestException
import dev.kanso.service.ProjectDetail
import dev.kanso.service.TicketDetail
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * How the mirror is doing for one row. The UI shows it as a small badge, which is
 * the only honest way to run an asynchronous mirror: when Notion is behind, say so
 * instead of pretending the write already landed there.
 */
data class MirrorDto(
	val notionPageId: String?,
	val state: String,
	val syncedAt: OffsetDateTime?,
)

// --- disposition -------------------------------------------------------------

data class DispositionCountsResponse(val subTeams: Int, val projects: Int, val tickets: Int) {
	companion object {
		fun of(counts: DispositionCounts) =
			DispositionCountsResponse(counts.subTeams, counts.projects, counts.tickets)
	}
}

/**
 * What happens to the contents. Every category defaults to `keep`: the destructive
 * reading of a missing field is never the safe one.
 */
data class DispositionPlanRequest(
	val subTeams: String = "keep",
	val projects: String = "keep",
	val tickets: String = "keep",
	val ticketsTargetTeamId: UUID? = null,
	val counts: DispositionCountsResponse? = null,
) {
	fun toPlan() = DispositionPlan(
		subTeams = DispositionChoice.from(subTeams),
		projects = DispositionChoice.from(projects),
		tickets = DispositionChoice.from(tickets),
		ticketsTargetTeamId = ticketsTargetTeamId,
		counts = counts?.let { DispositionCounts(it.subTeams, it.projects, it.tickets) },
	)
}

// --- teams -------------------------------------------------------------------

data class TeamRequest(
	@field:NotBlank val name: String,
	/** Ticket prefix, e.g. `KAN`. Derived from the name when omitted. */
	@field:Size(min = 2, max = 8) val key: String? = null,
	val parentTeamId: UUID? = null,
)

data class TeamResponse(
	val id: UUID,
	val name: String,
	val key: String,
	val parentTeamId: UUID?,
	val archived: Boolean,
	val ticketCount: Int,
	val mirror: MirrorDto,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
) {
	companion object {
		fun of(team: Team) = TeamResponse(
			id = team.id,
			name = team.name,
			key = team.key,
			parentTeamId = team.parentTeamId,
			archived = team.archived,
			ticketCount = team.ticketCounter,
			mirror = MirrorDto(team.mirror.notionPageId, team.mirror.syncState.wire, team.mirror.notionSyncedAt),
			createdAt = team.createdAt,
			updatedAt = team.updatedAt,
		)
	}
}

data class UserResponse(
	val id: UUID,
	val email: String,
	val displayName: String,
	val avatarUrl: String?,
	/** Null when this person has no Notion account, so `people` can't mirror them. */
	val notionPersonId: String?,
	/** Whether this person may configure the instance. Not a role inside a team. */
	val instanceRole: String,
	/** The two ways in. The settings screen refuses to remove the last one. */
	val hasPassword: Boolean,
	val linkedProvider: String?,
) {
	companion object {
		fun of(user: User) = UserResponse(
			id = user.id,
			email = user.email,
			displayName = user.displayName,
			avatarUrl = user.avatarUrl,
			notionPersonId = user.notionPersonId,
			instanceRole = user.instanceRole.wire,
			hasPassword = user.hasPassword,
			linkedProvider = user.oidcProvider,
		)
	}
}

data class MemberResponse(val user: UserResponse, val role: String) {
	companion object {
		fun of(member: TeamMember) = MemberResponse(UserResponse.of(member.user), member.role.wire)
	}
}

data class AddMemberRequest(val userId: UUID, val role: String = MemberRole.MEMBER.wire)

// --- projects ----------------------------------------------------------------

data class ProjectRequest(
	@field:NotBlank val name: String,
	val status: String = ProjectStatus.PLANNED.wire,
	val startDate: LocalDate? = null,
	val endDate: LocalDate? = null,
	val leadUserId: UUID? = null,
	val teamId: UUID? = null,
	val docIds: List<UUID>? = null,
	val archived: Boolean = false,
)

data class ProjectResponse(
	val id: UUID,
	val name: String,
	val status: String,
	val startDate: LocalDate?,
	val endDate: LocalDate?,
	val leadUserId: UUID?,
	val teamId: UUID?,
	val docIds: List<UUID>,
	val archived: Boolean,
	val mirror: MirrorDto,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
) {
	companion object {
		fun of(detail: ProjectDetail): ProjectResponse {
			val p: Project = detail.project
			return ProjectResponse(
				id = p.id,
				name = p.name,
				status = p.status.wire,
				startDate = p.startDate,
				endDate = p.endDate,
				leadUserId = p.leadUserId,
				teamId = p.teamId,
				docIds = detail.docIds,
				archived = p.archived,
				mirror = MirrorDto(p.mirror.notionPageId, p.mirror.syncState.wire, p.mirror.notionSyncedAt),
				createdAt = p.createdAt,
				updatedAt = p.updatedAt,
			)
		}
	}
}

// --- tickets -----------------------------------------------------------------

data class TicketCreateRequest(
	val teamId: UUID,
	@field:NotBlank val title: String,
	val description: String? = null,
	val status: String = TicketStatus.TODO.wire,
	val priority: String = TicketPriority.NONE.wire,
	val startDate: LocalDate? = null,
	val dueDate: LocalDate? = null,
	val projectId: UUID? = null,
	val assigneeIds: List<UUID> = emptyList(),
	val docIds: List<UUID> = emptyList(),
)

/**
 * Absent field means "leave unchanged". To clear a nullable field, list its name
 * in [unset] — JSON alone cannot tell an omitted key from an explicit null, and
 * guessing either way makes one of the two operations impossible.
 */
data class TicketPatchRequest(
	val title: String? = null,
	val description: String? = null,
	val status: String? = null,
	val priority: String? = null,
	val startDate: LocalDate? = null,
	val dueDate: LocalDate? = null,
	val projectId: UUID? = null,
	val teamId: UUID? = null,
	val archived: Boolean? = null,
	val assigneeIds: List<UUID>? = null,
	val docIds: List<UUID>? = null,
	val unset: Set<String> = emptySet(),
) {
	companion object {
		val CLEARABLE = setOf("description", "startDate", "dueDate", "projectId")
	}

	fun validated(): TicketPatchRequest {
		val unknown = unset - CLEARABLE
		if (unknown.isNotEmpty()) {
			throw BadRequestException(
				"Cannot unset ${unknown.joinToString()}; clearable fields are ${CLEARABLE.joinToString()}"
			)
		}
		return this
	}
}

data class TicketResponse(
	val id: UUID,
	/** `KAN-142` — what people type and say out loud. */
	val identifier: String,
	val number: Int,
	val teamId: UUID,
	val title: String,
	val description: String?,
	val status: String,
	val priority: String,
	val startDate: LocalDate?,
	val dueDate: LocalDate?,
	val projectId: UUID?,
	val assigneeIds: List<UUID>,
	val docIds: List<UUID>,
	val archived: Boolean,
	val mirror: MirrorDto,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
) {
	companion object {
		fun of(detail: TicketDetail): TicketResponse {
			val t = detail.ticket
			return TicketResponse(
				id = t.id,
				identifier = detail.identifier,
				number = t.number,
				teamId = t.teamId,
				title = t.title,
				description = t.description,
				status = t.status.wire,
				priority = t.priority.wire,
				startDate = t.startDate,
				dueDate = t.dueDate,
				projectId = t.projectId,
				assigneeIds = detail.assigneeIds,
				docIds = detail.docIds,
				archived = t.archived,
				mirror = MirrorDto(t.mirror.notionPageId, t.mirror.syncState.wire, t.mirror.notionSyncedAt),
				createdAt = t.createdAt,
				updatedAt = t.updatedAt,
			)
		}
	}
}

// --- docs --------------------------------------------------------------------

data class DocRequest(
	@field:NotBlank val notionPageId: String,
	val title: String? = null,
	val url: String? = null,
)

data class DocResponse(val id: UUID, val notionPageId: String, val title: String?, val url: String?) {
	companion object {
		fun of(doc: NotionDoc) = DocResponse(doc.id, doc.notionPageId, doc.title, doc.url)
	}
}

// --- auth --------------------------------------------------------------------

data class AuthModeResponse(
	val mode: String,
	/** Local email and password, available as soon as an account has one. */
	val passwordLoginEnabled: Boolean,
	val providers: List<AuthProvider>,
)

data class AuthProvider(val id: String, val label: String, val authorizeUrl: String)

data class MeResponse(
	val user: UserResponse,
	val teamIds: List<UUID>,
	/**
	 * Preferences travel with the session so the first paint costs one round trip
	 * rather than two — the second one would be the one deciding the theme.
	 */
	val preferences: PreferencesResponse,
)
