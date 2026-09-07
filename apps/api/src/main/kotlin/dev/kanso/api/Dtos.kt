package dev.kanso.api

import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionContents
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.MemberRole
import dev.kanso.domain.NotionDoc
import dev.kanso.domain.Project
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.Team
import dev.kanso.domain.TeamMember
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.github.TicketPullRequest
import dev.kanso.service.BadRequestException
import dev.kanso.service.ProjectDetail
import dev.kanso.service.TicketDetail
import dev.kanso.service.TicketGroup
import dev.kanso.service.TimelineView
import dev.kanso.service.ViewGroupBy
import dev.kanso.service.ViewSortBy
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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

/**
 * A date on the wire. `hasTime` false means the value names a day: the client renders
 * it without timezone conversion, so it reads identically everywhere.
 */
data class InstantDto(val at: OffsetDateTime, val hasTime: Boolean = false) {
	fun toDomain() = KansoInstant(at, hasTime)

	companion object {
		fun of(instant: KansoInstant?) = instant?.let { InstantDto(it.at, it.hasTime) }
	}
}

// --- disposition -------------------------------------------------------------

data class DispositionCountsResponse(val subTeams: Int, val projects: Int, val tickets: Int) {
	companion object {
		fun of(counts: DispositionCounts) =
			DispositionCountsResponse(counts.subTeams, counts.projects, counts.tickets)
	}
}

/**
 * Both readings, so the modal can repaint the instant the sub-teams radio moves
 * without asking again — see [dev.kanso.domain.DispositionContents].
 */
data class DispositionContentsResponse(
	val direct: DispositionCountsResponse,
	val subtree: DispositionCountsResponse,
) {
	companion object {
		fun of(contents: DispositionContents) = DispositionContentsResponse(
			direct = DispositionCountsResponse.of(contents.direct),
			subtree = DispositionCountsResponse.of(contents.subtree),
		)
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
	/** The server's answer, so the composer's team select obeys it rather than re-deriving it. */
	val editable: Boolean,
) {
	companion object {
		fun of(team: Team, editable: Boolean) = TeamResponse(
			id = team.id,
			name = team.name,
			key = team.key,
			parentTeamId = team.parentTeamId,
			archived = team.archived,
			ticketCount = team.ticketCounter,
			mirror = MirrorDto(team.mirror.notionPageId, team.mirror.syncState.wire, team.mirror.notionSyncedAt),
			createdAt = team.createdAt,
			updatedAt = team.updatedAt,
			editable = editable,
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
	/**
	 * What this person may do to the instance — configure it, write in it, or only read
	 * it. Not a role inside a team.
	 *
	 * The client draws from this and the server does not trust it: `viewer` is what makes
	 * the web app leave out a composer it knows will be refused, and `ReadOnlySeat` is what
	 * refuses it whether or not the composer was drawn.
	 */
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
	val start: InstantDto? = null,
	val end: InstantDto? = null,
	val leadUserId: UUID? = null,
	val teamId: UUID? = null,
	val docIds: List<UUID>? = null,
)

data class ProjectResponse(
	val id: UUID,
	val name: String,
	val status: String,
	val start: InstantDto?,
	val end: InstantDto?,
	val leadUserId: UUID?,
	val teamId: UUID?,
	val docIds: List<UUID>,
	/**
	 * The newest health anybody posted, or absent if nobody has. Absent is not
	 * `on_track` — see `ProjectDetail.health` — so the client must draw "not assessed"
	 * rather than a green pill, and this is null rather than a default for exactly that
	 * reason. Never sent alongside a health on the project row: there is no such column.
	 */
	val health: String?,
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
				start = InstantDto.of(p.start),
				end = InstantDto.of(p.end),
				leadUserId = p.leadUserId,
				teamId = p.teamId,
				docIds = detail.docIds,
				health = detail.health?.wire,
				archived = p.archived,
				mirror = MirrorDto(p.mirror.notionPageId, p.mirror.syncState.wire, p.mirror.notionSyncedAt),
				createdAt = p.createdAt,
				updatedAt = p.updatedAt,
			)
		}
	}
}

// --- tickets -----------------------------------------------------------------

/**
 * The body of a dependency write. Only the predecessor: the successor is the ticket in
 * the path, which is the direction the arrow is drawn in the timeline.
 */
data class DependencyRequest(val predecessorId: UUID)

/** What a write returns: the tickets the cascade moved, so the client settles at once. */
data class CascadeResponse(val movedTicketIds: List<UUID>)

data class TicketCreateRequest(
	/**
	 * Absent files a draft. Absent *and* a [projectId] that belongs to a team files it into
	 * that team, because a ticket's project belongs to its team.
	 */
	val teamId: UUID? = null,
	@field:NotBlank val title: String,
	val description: String? = null,
	val status: String = DefaultStatus.TODO.wire,
	val priority: String = TicketPriority.NONE.wire,
	/** Points, off `EffortPoints.SCALE`. Absent means unsized, which is not zero. */
	val estimate: Int? = null,
	val start: InstantDto? = null,
	val due: InstantDto? = null,
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
	val estimate: Int? = null,
	val start: InstantDto? = null,
	val due: InstantDto? = null,
	val projectId: UUID? = null,
	val teamId: UUID? = null,
	val archived: Boolean? = null,
	val assigneeIds: List<UUID>? = null,
	val docIds: List<UUID>? = null,
	val unset: Set<String> = emptySet(),
) {
	companion object {
		/**
		 * `estimate` is here because it is nullable and its null carries a meaning of its
		 * own: un-estimating a ticket is a decision — "we no longer know how big this is" —
		 * and without a name to put in `unset` there would be no way back to it.
		 */
		val CLEARABLE = setOf("description", "start", "due", "projectId", "estimate")
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
	/**
	 * `KAN-142` — what people type and say out loud, and null for a ticket no team has
	 * claimed. A draft is addressed by [id] instead, which is the address that never
	 * changes; the client draws a "no team" badge where this would have gone.
	 */
	val identifier: String?,
	val number: Int?,
	val teamId: UUID?,
	val title: String,
	val description: String?,
	val status: String,
	val priority: String,
	/** Null means nobody has sized it. Never 0 — see `EffortPoints`. */
	val estimate: Int?,
	val start: InstantDto?,
	val due: InstantDto?,
	val projectId: UUID?,
	/**
	 * The ticket this one is a part of, null for a top-level one. On the row rather than
	 * behind `GET /{id}/children`, because the main list folds children under their parent
	 * and would otherwise need a second query per page to know which rows those are.
	 */
	val parentId: UUID?,
	val assigneeIds: List<UUID>,
	val docIds: List<UUID>,
	val archived: Boolean,
	/**
	 * `V35`'s values, keyed by custom field id — `{}` for a ticket whose team has defined no
	 * fields, and for every draft.
	 *
	 * **Always present, never omitted.** That is the entire reason this shipped ahead of
	 * anybody asking for a custom field: `V27` opened a bearer-token door and `/api/mcp` put
	 * an agent behind it, so this class stopped being ours and became a contract. A reader
	 * that has always seen the key can learn to render it; one that meets it for the first
	 * time after pinning the shape cannot.
	 *
	 * Ids and not names, like [assigneeIds] and [docIds] beside it and for the same reason
	 * [TicketGroupsResponse] sends no labels: a name is what a *screen* calls a thing, and the
	 * screen fetches `GET /api/teams/{id}/fields` once to draw its inputs anyway. Keying by
	 * name would also cost every reader a rename.
	 *
	 * A value is one of three JSON scalars — a string, a number or a boolean — which is what
	 * `ticket_field_values_value_chk` narrows the column to and what `FieldValueCodec` is the
	 * rest of. It is never null: a field with no value has no key here at all, because the
	 * table keeps no row for one.
	 */
	val customFields: Map<String, Any>,
	/**
	 * `V36`'s pull requests. **Always present**, empty for an instance with no GitHub App —
	 * the same promise `customFields` makes and for the same reason: a shape a script pins
	 * must not change the day the feature is switched on.
	 *
	 * A list and not a nullable field, deliberately. The shared Jackson mapper omits nulls,
	 * so a nullable field is *absent* from the JSON rather than `null`, and a hand-written TS
	 * type saying `| null` would then be wrong in a way no compiler catches — which has
	 * already produced one "Invalid Date" on a screen in this repository. An always-present
	 * array has one spelling for "none".
	 */
	val pullRequests: List<PullRequestDto>,
	/**
	 * The branch to create for this ticket, derived on read from the identifier and the
	 * title. Null for a draft, which has no identifier — and null is *absent* here, so the
	 * TS mirror spells it `branchName?: string`.
	 */
	val branchName: String?,
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
				estimate = t.estimate,
				start = InstantDto.of(t.start),
				due = InstantDto.of(t.due),
				projectId = t.projectId,
				parentId = t.parentId,
				assigneeIds = detail.assigneeIds,
				docIds = detail.docIds,
				archived = t.archived,
				// UUID keys as text, because a JSON object has no other kind.
				customFields = detail.customFields.entries.associate { (id, value) -> id.toString() to value },
				pullRequests = detail.pullRequests.map {
					PullRequestDto.of(it, it.authorUserId?.let(detail.pullRequestAuthors::get))
				},
				branchName = detail.branchName,
				mirror = MirrorDto(t.mirror.notionPageId, t.mirror.syncState.wire, t.mirror.notionSyncedAt),
				createdAt = t.createdAt,
				updatedAt = t.updatedAt,
			)
		}
	}
}

/**
 * One pull request on a ticket.
 *
 * Flat and small on purpose — the opposite of [TicketLinkResponse], which nests a whole
 * `TicketResponse` because the other end of a link *is* a ticket. What a person reads off a
 * row here is the repository, the number, the title and a pill, so that is what crosses the
 * wire, and nothing here needs a second request to be useful.
 *
 * **The pill is not a field.** `state`, `draft` and `reviewState` go out as they are stored
 * and the label is computed by the client, because it is a presentation rule over three
 * columns — a `pill: "Changes requested"` on the wire would be a derived value stored in a
 * response, which is the thing this codebase does not do, and it would need translating
 * before it could ever be translated.
 */
data class PullRequestDto(
	val repo: String,
	val number: Int,
	val title: String,
	val url: String,
	val state: String,
	val draft: Boolean,
	/** Absent when nobody has reviewed yet, which the pill reads as "In review". */
	val reviewState: String?,
	val authorLogin: String?,
	/**
	 * The Kanso member behind [authorLogin], when that member has linked their GitHub
	 * account — and **absent, not null**, when they have not.
	 *
	 * `KAN-74`'s half of the feature, in the one place a GitHub-authored thing is drawn
	 * today. [authorLogin] stays exactly as it was and is what the row shows on its own:
	 * an author nobody has linked reads `@tykok`, which is the documented fallback and not
	 * a degradation. This is the name that replaces it once somebody consents.
	 *
	 * The shared mapper is `default-property-inclusion: non_null`, so this key is *missing*
	 * from the JSON rather than `null` in it, and the TypeScript mirror therefore spells it
	 * `author?: User` and never `author: User | null`. That distinction is not pedantry:
	 * a `| null` type here is a lie no compiler catches, and the last one produced a
	 * "Last used Invalid Date" on a screen.
	 *
	 * A whole [UserResponse] and not a bare display name, because it is the shape every
	 * other person on the wire has — `ActivityResponse.actor` included — and a screen that
	 * wants an avatar beside the name should not need a second endpoint to get one.
	 */
	val author: UserResponse?,
	val headRef: String,
	/** Whether this pull request may move the ticket, as opposed to merely naming it. */
	val closes: Boolean,
	/**
	 * True when a member drew this link by hand.
	 *
	 * On the wire because the screen has to be able to say so: a detected link and a
	 * deliberate one are removed by different things, and a person who does not know which
	 * kind they are looking at cannot predict what an edit to the branch name will do.
	 */
	val linkedByMember: Boolean,
) {
	companion object {
		/**
		 * @param author the member [TicketPullRequest.authorUserId] names, or null when the
		 *   author never linked their GitHub account — which is the ordinary case, and the
		 *   one that leaves the row reading `@login` exactly as it did before.
		 */
		fun of(link: TicketPullRequest, author: User?): PullRequestDto {
			val pr = link.pullRequest
			return PullRequestDto(
				repo = pr.repoFullName,
				number = pr.number,
				title = pr.title,
				url = pr.url,
				state = pr.state.wire,
				draft = pr.draft,
				reviewState = pr.reviewState?.wire,
				authorLogin = pr.authorLogin,
				author = author?.let(UserResponse::of),
				headRef = pr.headRef,
				closes = link.closes,
				linkedByMember = link.linkedByMember,
			)
		}
	}
}

/**
 * A grouped list on the wire: buckets, not a flat list the reader re-buckets.
 *
 * A flat answer was the other candidate and it is half a move. The reason to send groups
 * is [TicketGroupResponse.count]: it is the count of the whole match, so a header can say
 * `Todo · 29` while carrying twenty rows — which a flat page cannot express at all,
 * because counting it can only ever count itself. It is also what makes paging *inside* a
 * bucket possible later: the client already knows how many are missing and where.
 *
 * [total] is the sum of the buckets rather than a query of its own, so the number in the
 * header and the numbers beside each group cannot disagree.
 *
 * No labels. `Todo`, `A. Okonkwo` and `Design system` are what a *screen* calls these
 * keys, and the server has no screen — the web app resolves every one of those names
 * today for its own chips. What the server owns is which buckets exist and how big they
 * are.
 */
data class TicketGroupsResponse(
	val groupBy: String,
	val sortBy: String,
	val total: Int,
	val groups: List<TicketGroupResponse>,
) {
	companion object {
		fun of(groupBy: ViewGroupBy, sortBy: ViewSortBy, groups: List<TicketGroup>) = TicketGroupsResponse(
			groupBy = groupBy.wire,
			sortBy = sortBy.wire,
			total = groups.sumOf { it.count },
			groups = groups.map(TicketGroupResponse::of),
		)
	}
}

/**
 * One bucket. [count] is every row that matches, [tickets] only the ones this page
 * reached — a bucket the page stopped short of comes back with its count and an empty
 * list rather than being dropped, so a header can be drawn above rows still to load.
 *
 * [key] is the empty string for the rows that have none: nobody assigned, no project, and
 * the single bucket `groupBy=none` answers with.
 */
data class TicketGroupResponse(val key: String, val count: Int, val tickets: List<TicketResponse>) {
	companion object {
		fun of(group: TicketGroup) =
			TicketGroupResponse(group.key, group.count, group.tickets.map(TicketResponse::of))
	}
}

/**
 * The filter names `GET /api/tickets` and a saved view both answer to, sorted.
 *
 * A list and not a map of shapes: what a facet takes is already enforced by the parser
 * that reads it, and a second description of it here would be a second thing to keep
 * true. A client that wants to draw a control for `status` knows what a status is.
 */
data class ServedFiltersResponse(val served: List<String>)

// --- timeline ----------------------------------------------------------------

/** A bound plus where it came from: without [derived], nothing says what may be edited. */
data class TimelineBoundDto(val at: OffsetDateTime, val hasTime: Boolean, val derived: Boolean)

data class TimelineProjectResponse(
	val id: UUID,
	val name: String,
	val start: TimelineBoundDto?,
	val end: TimelineBoundDto?,
)

data class TimelineTicketResponse(
	val id: UUID,
	val identifier: String,
	val title: String,
	/** Whose work this is: a context row belongs to another team and says so. */
	val teamKey: String,
	val projectId: UUID?,
	val status: String,
	val start: InstantDto?,
	val due: InstantDto?,
	val slackMinutes: Long?,
	val critical: Boolean,
	val late: Boolean,
	val context: Boolean,
	/** The server's answer, so the client holds no membership graph to re-derive it from. */
	val editable: Boolean,
)

data class TimelineDependencyResponse(
	val predecessorId: UUID,
	val successorId: UUID,
	val violated: Boolean,
	val overlap: Boolean,
	val outOfScope: Boolean,
)

data class TimelineUnscheduledResponse(val id: UUID, val identifier: String, val title: String)

data class TimelineResponse(
	val projects: List<TimelineProjectResponse>,
	val tickets: List<TimelineTicketResponse>,
	val dependencies: List<TimelineDependencyResponse>,
	val unscheduled: List<TimelineUnscheduledResponse>,
	/** A cap was hit: the drawing is incomplete, and a Gantt has no next page to offer. */
	val truncated: Boolean,
) {
	companion object {
		fun of(view: TimelineView) = TimelineResponse(
			projects = view.projects.map { project ->
				TimelineProjectResponse(
					id = project.id,
					name = project.name,
					start = project.start?.let { TimelineBoundDto(it.at, it.hasTime, project.startDerived) },
					end = project.end?.let { TimelineBoundDto(it.at, it.hasTime, project.endDerived) },
				)
			},
			tickets = view.tickets.map {
				TimelineTicketResponse(
					id = it.id,
					identifier = it.identifier,
					title = it.title,
					teamKey = it.teamKey,
					projectId = it.projectId,
					status = it.status.wire,
					start = InstantDto.of(it.start),
					due = InstantDto.of(it.due),
					slackMinutes = it.slackMinutes,
					critical = it.critical,
					late = it.late,
					context = it.context,
					editable = it.editable,
				)
			},
			dependencies = view.dependencies.map {
				TimelineDependencyResponse(
					it.predecessorId,
					it.successorId,
					it.violated,
					it.overlap,
					it.outOfScope,
				)
			},
			unscheduled = view.unscheduled.map {
				TimelineUnscheduledResponse(it.id, it.identifier, it.title)
			},
			truncated = view.truncated,
		)
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
	/**
	 * Whether any ticket in the instance has been moved past where the composer leaves
	 * it. Screen 08's "Move it along", answered here for the same reason [preferences]
	 * is: the sidebar draws its checklist on the first paint, and the alternative was a
	 * 200-row ticket list fetched beside every grouped one (KAN-65).
	 *
	 * About the instance and not about this reader, like [version] and unlike everything
	 * above it. That is the honest scope of the question — the step is "has anything
	 * moved", not "did *you* move it" — and it is why this is derived per request rather
	 * than stored next to `onboardedAt`.
	 */
	val workMovedAlong: Boolean,
	val version: String,
)
