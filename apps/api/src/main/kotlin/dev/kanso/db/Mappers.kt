package dev.kanso.db

import dev.kanso.domain.MemberRole
import dev.kanso.domain.MirrorInfo
import dev.kanso.domain.NotionDoc
import dev.kanso.domain.Project
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.SyncState
import dev.kanso.domain.Team
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toTeam() = Team(
	id = this[Teams.id],
	name = this[Teams.name],
	key = this[Teams.key],
	parentTeamId = this[Teams.parentTeamId],
	archived = this[Teams.archived],
	ticketCounter = this[Teams.ticketCounter],
	mirror = MirrorInfo(
		notionPageId = this[Teams.notionPageId],
		syncState = SyncState.from(this[Teams.syncState]),
		notionSyncedAt = this[Teams.notionSyncedAt],
		notionLastEditedTime = this[Teams.notionLastEditedTime],
	),
	createdAt = this[Teams.createdAt],
	updatedAt = this[Teams.updatedAt],
)

fun ResultRow.toUser() = User(
	id = this[Users.id],
	email = this[Users.email],
	displayName = this[Users.displayName],
	notionPersonId = this[Users.notionPersonId],
	avatarUrl = this[Users.avatarUrl],
	oidcProvider = this[Users.oidcProvider],
	oidcSubject = this[Users.oidcSubject],
	active = this[Users.active],
	lastLoginAt = this[Users.lastLoginAt],
	createdAt = this[Users.createdAt],
)

fun ResultRow.toProject() = Project(
	id = this[Projects.id],
	name = this[Projects.name],
	status = ProjectStatus.from(this[Projects.status]),
	startDate = this[Projects.startDate],
	endDate = this[Projects.endDate],
	leadUserId = this[Projects.leadUserId],
	teamId = this[Projects.teamId],
	archived = this[Projects.archived],
	mirror = MirrorInfo(
		notionPageId = this[Projects.notionPageId],
		syncState = SyncState.from(this[Projects.syncState]),
		notionSyncedAt = this[Projects.notionSyncedAt],
		notionLastEditedTime = this[Projects.notionLastEditedTime],
	),
	createdAt = this[Projects.createdAt],
	updatedAt = this[Projects.updatedAt],
)

fun ResultRow.toTicket() = Ticket(
	id = this[Tickets.id],
	number = this[Tickets.number],
	teamId = this[Tickets.teamId],
	title = this[Tickets.title],
	description = this[Tickets.description],
	status = TicketStatus.from(this[Tickets.status]),
	priority = this[Tickets.priority]?.let(TicketPriority::from) ?: TicketPriority.NONE,
	startDate = this[Tickets.startDate],
	dueDate = this[Tickets.dueDate],
	projectId = this[Tickets.projectId],
	archived = this[Tickets.archived],
	mirror = MirrorInfo(
		notionPageId = this[Tickets.notionPageId],
		syncState = SyncState.from(this[Tickets.syncState]),
		notionSyncedAt = this[Tickets.notionSyncedAt],
		notionLastEditedTime = this[Tickets.notionLastEditedTime],
	),
	createdAt = this[Tickets.createdAt],
	updatedAt = this[Tickets.updatedAt],
)

fun ResultRow.toDoc() = NotionDoc(
	id = this[NotionDocs.id],
	notionPageId = this[NotionDocs.notionPageId],
	title = this[NotionDocs.title],
	url = this[NotionDocs.url],
	mirrorPageId = this[NotionDocs.mirrorPageId],
	syncState = SyncState.from(this[NotionDocs.syncState]),
	notionSyncedAt = this[NotionDocs.notionSyncedAt],
)

fun ResultRow.toMemberRole() = MemberRole.from(this[TeamMembers.role])
