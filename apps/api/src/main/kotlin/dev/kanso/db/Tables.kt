package dev.kanso.db

import dev.kanso.docs.JsonbColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
// Exposed 1.x `uuid()` yields kotlin.uuid.Uuid; `javaUUID()` keeps java.util.UUID,
// which is what JDBC, Jackson and the rest of Spring already speak.
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Exposed's view of the schema Flyway created. Nothing here generates DDL — the
 * migrations are the single definition of the database, and these objects only
 * describe it well enough to build queries.
 *
 * Foreign keys are declared as plain columns rather than [Table.reference]: the
 * references would only matter for DDL generation, which we never run, and the
 * self-reference on `teams.parent_team_id` would need the object to exist while
 * it is still being initialised.
 */

/**
 * The three jsonb columns declared in this file — `activity.payload`,
 * `outbound_jobs.payload`, `saved_views.filters` — are `text()` because they are written
 * only through raw SQL that casts explicitly. `user_preferences.shortcuts` is written
 * through the Exposed upsert alongside nine other columns, so it needs the cast in the
 * column type instead. `docs/DocTables.kt` already had that problem and solved it; this
 * is the same solution, addressed rather than copied.
 */
private fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())

object Teams : Table("teams") {
	val id = javaUUID("id")
	val name = text("name")
	val key = text("key")
	val parentTeamId = javaUUID("parent_team_id").nullable()
	val notionPageId = text("notion_page_id").nullable()
	val archived = bool("archived")
	val ticketCounter = integer("ticket_counter")
	val syncState = text("sync_state")
	val notionSyncedAt = timestampWithTimeZone("notion_synced_at").nullable()
	val notionLastEditedTime = timestampWithTimeZone("notion_last_edited_time").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

object Users : Table("users") {
	val id = javaUUID("id")
	val email = text("email")
	val displayName = text("display_name")
	val notionPersonId = text("notion_person_id").nullable()
	val avatarUrl = text("avatar_url").nullable()
	val oidcProvider = text("oidc_provider").nullable()
	val oidcSubject = text("oidc_subject").nullable()

	/** BCrypt. Null for an account that only ever signs in through a provider. */
	val passwordHash = text("password_hash").nullable()

	/** Who may configure the instance. Distinct from a role inside a team. */
	val instanceRole = text("instance_role")
	val active = bool("active")
	val lastLoginAt = timestampWithTimeZone("last_login_at").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(id)
}

/** One row. The configuration of this instance, not a collection of anything. */
object InstanceSettings : Table("instance_settings") {
	val id = bool("id")
	val setupCompletedAt = timestampWithTimeZone("setup_completed_at").nullable()
	val notionParentPageId = text("notion_parent_page_id").nullable()
	val notionTokenEnc = binary("notion_token_enc").nullable()
	val googleClientId = text("google_client_id").nullable()
	val googleClientSecretEnc = binary("google_client_secret_enc").nullable()

	// The public integration Kanso asks consent through, and what the consent screen
	// answered. See V14: the client pair is configuration, the workspace and bot are
	// facts about a connection that happened.
	val notionClientId = text("notion_client_id").nullable()
	val notionClientSecretEnc = binary("notion_client_secret_enc").nullable()
	val notionWorkspaceId = text("notion_workspace_id").nullable()
	val notionWorkspaceName = text("notion_workspace_name").nullable()
	val notionBotId = text("notion_bot_id").nullable()

	// The one of `V36`'s six GitHub columns anything reads today. The other five — app id,
	// slug, client id, client secret, private key — are the App Manifest flow's, and the
	// flow that writes them is part three of the design; a column mapped here that no code
	// reads is a column the next reader has to check for callers before touching. This one
	// is read because the webhook's HMAC is the only guard on an unauthenticated endpoint
	// that writes rows, so it has to work before any of the rest of the door exists.
	val githubWebhookSecretEnc = binary("github_webhook_secret_enc").nullable()

	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

object UserPreferences : Table("user_preferences") {
	val userId = javaUUID("user_id")
	val theme = text("theme")
	val accent = text("accent")
	val density = text("density")

	/**
	 * Pinned, hover or hidden — three words guarded by `user_preferences_sidebar_mode_chk`.
	 * Replaces `sidebar_visible`, which `V28` dropped: see [dev.kanso.domain.SidebarMode]
	 * for why two booleans could not have said the same thing.
	 */
	val sidebarMode = text("sidebar_mode").default("pinned")
	val showSyncBadges = bool("show_sync_badges")
	val showStatusBar = bool("show_status_bar")

	/** Whether the top bar draws Filter, Group and Order. Same `default` reasoning as below. */
	val showViewControls = bool("show_view_controls").default(true)

	/**
	 * Remapped keys as their jsonb text, parsed by the repository rather than by Exposed.
	 *
	 * Written through Exposed like every other column on this table, so the cast to jsonb
	 * has to live in the column type — pgjdbc sends a Kotlin String as `varchar` and
	 * Postgres refuses `varchar → jsonb` without one, which is why a plain `text()` here
	 * would compile and fail on the first save. [JsonbColumnType] is that cast; it is
	 * borrowed from `docs/DocTables.kt`, where it was first needed, rather than written
	 * out a second time. If the tree ever wants every table in one file again, the type
	 * comes with them.
	 */
	val shortcuts = jsonb("shortcuts").default("{}")
	val defaultTeamId = javaUUID("default_team_id").nullable()
	val onboardedAt = timestampWithTimeZone("onboarded_at").nullable()

	/**
	 * The reader's timezone. `default` mirrors the column default Flyway wrote — it
	 * lets Exposed fill the value on an insert that does not name it, and generates
	 * no DDL, which this file never does.
	 */
	val timezone = text("timezone").default("UTC")

	/** Which of the two ways `↵` opens a ticket. Same `default` reasoning as above. */
	val openTicket = text("open_ticket").default("panel")

	/**
	 * Points per working day, as the person declared it. Null means they never said —
	 * which is not zero, and `V24` says why the difference is the whole column.
	 *
	 * `NUMERIC(5, 2)` and so a `BigDecimal` here; the repository is the only place that
	 * sees one. Everything above it works in `Double`, like `VelocityService`.
	 */
	val declaredVelocity = decimal("declared_velocity", 5, 2).nullable()
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(userId)
}

object Invitations : Table("invitations") {
	val id = javaUUID("id")

	/** Hashed: a database dump must not hand out working invitation links. */
	val tokenHash = text("token_hash")
	val email = text("email").nullable()
	val instanceRole = text("instance_role")
	val createdBy = javaUUID("created_by").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val expiresAt = timestampWithTimeZone("expires_at")
	val acceptedAt = timestampWithTimeZone("accepted_at").nullable()
	val acceptedBy = javaUUID("accepted_by").nullable()
	override val primaryKey = PrimaryKey(id)
}

object LoginAttempts : Table("login_attempts") {
	val id = long("id").autoIncrement()
	val email = text("email")
	val ip = text("ip").nullable()
	val at = timestampWithTimeZone("at")
	val succeeded = bool("succeeded")
	override val primaryKey = PrimaryKey(id)
}

object TeamMembers : Table("team_members") {
	val teamId = javaUUID("team_id")
	val userId = javaUUID("user_id")
	val role = text("role")
	override val primaryKey = PrimaryKey(teamId, userId)
}

object Projects : Table("projects") {
	val id = javaUUID("id")
	val name = text("name")
	val status = text("status")
	val startAt = timestampWithTimeZone("start_at").nullable()
	val startHasTime = bool("start_has_time")
	val endAt = timestampWithTimeZone("end_at").nullable()
	val endHasTime = bool("end_has_time")
	val leadUserId = javaUUID("lead_user_id").nullable()
	val teamId = javaUUID("team_id").nullable()
	val notionPageId = text("notion_page_id").nullable()
	val archived = bool("archived")
	val syncState = text("sync_state")
	val notionSyncedAt = timestampWithTimeZone("notion_synced_at").nullable()
	val notionLastEditedTime = timestampWithTimeZone("notion_last_edited_time").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

/**
 * How a project is going, said on a date. No `updatedAt` because there is no writer for
 * one: an update is a dated statement, and the correction for a wrong one is the next
 * update. There is no `projects.health` to go with this — the current health is the
 * latest row here, and `V23` argues why a stored copy would be wrong on arrival.
 */
object ProjectUpdates : Table("project_updates") {
	val id = javaUUID("id")
	val projectId = javaUUID("project_id")
	val health = text("health")
	val body = text("body")
	/** Null once the account is gone. What was known about the project in August is not. */
	val authorId = javaUUID("author_id").nullable()
	val at = timestampWithTimeZone("at")
	override val primaryKey = PrimaryKey(id)
}

object Tickets : Table("tickets") {
	val id = javaUUID("id")

	/**
	 * Null together or not at all, which `tickets_team_number_together_chk` is what
	 * actually enforces: the number comes from the team's counter and is unique only
	 * within it, so half the pair names nothing.
	 */
	val number = integer("number").nullable()
	val teamId = javaUUID("team_id").nullable()

	/** Who may edit a ticket no team is deciding for. Null for every row older than `V20`. */
	val createdBy = javaUUID("created_by").nullable()
	val title = text("title")
	val description = text("description").nullable()
	val status = text("status")
	val priority = text("priority").nullable()

	/**
	 * Points, on a closed scale `tickets_estimate_chk` refuses anything off. Nullable
	 * because "not estimated yet" is its own state, and `SMALLINT` because the largest
	 * value the constraint admits is 13 — hence `Short` here and `Int?` in the domain,
	 * where nothing wants to widen a sum by hand.
	 */
	val estimate = short("estimate").nullable()
	val startAt = timestampWithTimeZone("start_at").nullable()
	val startHasTime = bool("start_has_time")
	val dueAt = timestampWithTimeZone("due_at").nullable()
	val dueHasTime = bool("due_has_time")
	val completedAt = timestampWithTimeZone("completed_at").nullable()
	val projectId = javaUUID("project_id").nullable()

	/**
	 * The ticket this one is a part of, one level deep and no deeper — `TicketService`
	 * refuses to build a tree, for the reasons `V34`'s header gives. `ON DELETE SET NULL`,
	 * so deleting a parent promotes its children instead of destroying their work.
	 */
	val parentId = javaUUID("parent_id").nullable()
	val notionPageId = text("notion_page_id").nullable()
	val archived = bool("archived")
	val syncState = text("sync_state")
	val notionSyncedAt = timestampWithTimeZone("notion_synced_at").nullable()
	val notionLastEditedTime = timestampWithTimeZone("notion_last_edited_time").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

object TicketAssignees : Table("ticket_assignees") {
	val ticketId = javaUUID("ticket_id")
	val userId = javaUUID("user_id")
	override val primaryKey = PrimaryKey(ticketId, userId)
}

/**
 * Every edge of the ticket graph, of whatever kind — `V33` generalised V7's
 * dependency table rather than adding a second one beside it.
 *
 * `from`/`to` name a direction without claiming what it means; [type] says. Only
 * `blocks` is a schedule, and the acyclicity that the scheduler relies on is guarded
 * on insert rather than by a constraint the database cannot express. `relates` is
 * symmetric and stored once, with the smaller uuid first — a reader of one ticket's
 * relates edges has to look in both columns, which is why nothing reads this table
 * directly except the two repositories that know that rule.
 *
 * Named after the table, like every object here. Not to be confused with
 * `sync.importer.TicketLinks`, which is the Notion import's "draw the arrows" pass.
 */
object TicketLinks : Table("ticket_links") {
	val fromTicketId = javaUUID("from_ticket_id")
	val toTicketId = javaUUID("to_ticket_id")
	val type = text("type")
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(fromTicketId, toTicketId, type)
}

object NotionDocs : Table("notion_docs") {
	val id = javaUUID("id")

	/** The real page someone wrote, which Kanso only references. */
	val notionPageId = text("notion_page_id")
	val title = text("title").nullable()
	val url = text("url").nullable()

	/** Kanso's index row inside the mirrored Docs database — what relations point at. */
	val mirrorPageId = text("mirror_page_id").nullable()
	val syncState = text("sync_state")
	val notionSyncedAt = timestampWithTimeZone("notion_synced_at").nullable()
	val notionLastEditedTime = timestampWithTimeZone("notion_last_edited_time").nullable()
	override val primaryKey = PrimaryKey(id)
}

object ProjectDocs : Table("project_docs") {
	val projectId = javaUUID("project_id")
	val docId = javaUUID("doc_id")
	override val primaryKey = PrimaryKey(projectId, docId)
}

object TicketDocs : Table("ticket_docs") {
	val ticketId = javaUUID("ticket_id")
	val docId = javaUUID("doc_id")
	override val primaryKey = PrimaryKey(ticketId, docId)
}

/**
 * A sentence somebody wrote, on exactly one thing.
 *
 * Both parents are nullable and a CHECK insists on one of them: the alternative shape
 * — a `parent_type` plus a `parent_id` with no foreign key at all — buys the third
 * case nothing and loses the cascade that keeps a deleted ticket from leaving its
 * discussion behind.
 */
object Comments : Table("comments") {
	val id = javaUUID("id")
	val ticketId = javaUUID("ticket_id").nullable()
	val docId = javaUUID("doc_id").nullable()
	val authorId = javaUUID("author_id")
	val body = text("body")
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

/**
 * Who a comment named, resolved when it was written. Re-parsing the body on read
 * would make a mention disappear the day its author's display name changes, after it
 * had already been delivered.
 */
object CommentMentions : Table("comment_mentions") {
	val commentId = javaUUID("comment_id")
	val userId = javaUUID("user_id")
	override val primaryKey = PrimaryKey(commentId, userId)
}

/** Team-scoped, so two teams may both own the word `sync` without fighting for it. */
object Labels : Table("labels") {
	val id = javaUUID("id")
	val teamId = javaUUID("team_id")
	val name = text("name")

	/** A token name from the accent vocabulary, not a hex string. */
	val colour = text("colour").default("indigo")
	override val primaryKey = PrimaryKey(id)
}

object TicketLabels : Table("ticket_labels") {
	val ticketId = javaUUID("ticket_id")
	val labelId = javaUUID("label_id")
	override val primaryKey = PrimaryKey(ticketId, labelId)
}

/**
 * What happened, written in the transaction that made it happen.
 *
 * [entityId] carries no foreign key on purpose: one table answers for tickets,
 * projects, teams and docs, and four nullable columns with four constraints would buy
 * nothing a feed can read. The consequence is that a row outlives the thing it
 * describes, which is what a log is for.
 */
object Activity : Table("activity") {
	val id = javaUUID("id")
	val entityType = text("entity_type")
	val entityId = javaUUID("entity_id")
	val actorId = javaUUID("actor_id").nullable()
	val kind = text("kind")

	// jsonb, read as text — Postgres hands it back as a PGobject whose toString is the
	// document. Written through raw SQL, which casts explicitly, because the driver
	// refuses a varchar parameter for a jsonb column: same split as `outbound_jobs.payload`.
	val payload = text("payload")
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(id)
}

object OutboundJobs : Table("outbound_jobs") {
	val id = long("id").autoIncrement()
	// Which system the job is going to; `entityType` says what it is about. Two axes,
	// not one compound kind — see `V26`.
	val destination = text("destination")
	val entityType = text("entity_type")
	val entityId = javaUUID("entity_id")
	val operation = text("operation")
	val status = text("status")
	val attempts = integer("attempts")
	val priority = integer("priority")
	val lastError = text("last_error").nullable()
	val nextAttemptAt = timestampWithTimeZone("next_attempt_at")
	val lockedAt = timestampWithTimeZone("locked_at").nullable()
	val lockedBy = text("locked_by").nullable()
	// When the holder was last known to be alive, refreshed while a push runs — not when
	// the push started, which is `lockedAt`. The stuck-job sweep reads this one, so that a
	// slow push and a dead worker stop looking alike; see `V29`.
	val heartbeatAt = timestampWithTimeZone("heartbeat_at").nullable()
	// jsonb, read as text. Written only through raw SQL, which casts explicitly.
	val payload = text("payload").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

/**
 * One row per person who has to be told, not one per change — see `V13`.
 *
 * `payload` is jsonb, declared here as text for the same reason `OutboundJobs.payload`
 * is: it is written only through raw SQL, which casts explicitly, and read back with
 * `::text` so Jackson parses it rather than Exposed.
 */
object Notifications : Table("notifications") {
	val id = javaUUID("id")
	val userId = javaUUID("user_id")
	val kind = text("kind")
	val entityType = text("entity_type")
	val entityId = javaUUID("entity_id")
	val actorId = javaUUID("actor_id").nullable()
	val payload = text("payload")
	val readAt = timestampWithTimeZone("read_at").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(id)
}

object NotionDatabases : Table("notion_databases") {
	val kind = text("kind")
	val databaseId = text("database_id")
	val dataSourceId = text("data_source_id")
	val parentPageId = text("parent_page_id").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(kind)
}

/**
 * The trash, for every kind of thing that can be thrown away.
 *
 * A row here *is* the deletion — there is no `deleted` flag on `tickets` to keep in
 * step with it — so a live read excludes the trash with a subquery on this primary key,
 * and a kind that lands later needs no column of its own. `entityType` is closed by a
 * `CHECK` in `V11`; [dev.kanso.trash.TrashKind] is the same vocabulary in Kotlin.
 */
object TrashEntries : Table("trash_entries") {
	val entityType = text("entity_type")
	val entityId = javaUUID("entity_id")
	val deletedAt = timestampWithTimeZone("deleted_at")
	val deletedBy = javaUUID("deleted_by").nullable()
	override val primaryKey = PrimaryKey(entityType, entityId)
}

object NotionSyncCursors : Table("notion_sync_cursors") {
	val dataSourceId = text("data_source_id")
	val lastEditTime = timestampWithTimeZone("last_edit_time").nullable()
	val lastRunAt = timestampWithTimeZone("last_run_at").nullable()
	val lastError = text("last_error").nullable()
	override val primaryKey = PrimaryKey(dataSourceId)
}

/**
 * A team's rhythm. [startsOn] and [endsOn] are `DATE`, not instants: a cycle is a run of
 * whole days everyone in the team agrees on, and giving it a timezone would make "six
 * days left" depend on who is asking.
 */
object Cycles : Table("cycles") {
	val id = javaUUID("id")
	val teamId = javaUUID("team_id")
	val number = integer("number")
	val startsOn = date("starts_on")
	val endsOn = date("ends_on")
	val state = text("state")
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

/** A ticket belongs to at most one cycle, so the ticket is the key. */
object TicketCycles : Table("ticket_cycles") {
	val ticketId = javaUUID("ticket_id")
	val cycleId = javaUUID("cycle_id")
	val addedAt = timestampWithTimeZone("added_at")
	override val primaryKey = PrimaryKey(ticketId)
}

/**
 * What triage decided, and therefore what has left the queue. The queue is every
 * untriaged ticket, so this table's absence of a row is the membership test — there is
 * no `in_triage` flag anywhere that could disagree with it.
 */
object TriageDecisions : Table("triage_decisions") {
	val ticketId = javaUUID("ticket_id")
	val decision = text("decision")
	val duplicateOf = javaUUID("duplicate_of").nullable()
	val decidedBy = javaUUID("decided_by").nullable()
	val decidedAt = timestampWithTimeZone("decided_at")
	override val primaryKey = PrimaryKey(ticketId)
}

object SavedViews : Table("saved_views") {
	val id = javaUUID("id")
	val teamId = javaUUID("team_id")
	val name = text("name")
	val shared = bool("shared")

	// jsonb, read as text and written through raw SQL that casts explicitly — the same
	// split `outbound_jobs.payload` already lives with, for the same reason: the driver
	// refuses a varchar parameter for a jsonb column.
	val filters = text("filters")
	val groupBy = text("group_by")
	val sortBy = text("sort_by")
	val createdBy = javaUUID("created_by").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

/**
 * The public read model's own view of `tickets`, and deliberately a second object on
 * the same table rather than three more columns on [Tickets].
 *
 * The public routes are the only ones that answer without a session, so they are the
 * only place where selecting one column too many is a disclosure rather than a
 * rendering bug. This object carries exactly what a stranger may read; the projection
 * cannot leak a description of a private neighbour, an assignee, or a Notion page id,
 * because those columns are not absent from its queries — they are absent from its
 * type. Widening this object is the edit a reviewer should stop at, which is the whole
 * reason it is small enough to read in one glance.
 *
 * [isPublic] is the flag itself, and it is on this object because every query that
 * reads this table has to name it. Nothing outside `dev.kanso.publik` uses this.
 */
object PublicTickets : Table("tickets") {
	val id = javaUUID("id")
	val number = integer("number")
	/** Not shown; needed to resolve the team's key into `KAN-142`. */
	val teamId = javaUUID("team_id")
	val title = text("title")
	val description = text("description").nullable()
	val status = text("status")
	/** When it shipped — the date the delivered column prints beside a ticket. */
	val completedAt = timestampWithTimeZone("completed_at").nullable()
	val isPublic = bool("public")
	val archived = bool("archived")
	override val primaryKey = PrimaryKey(id)
}

/**
 * Open voting, one row per voter per ticket. [voterKey] is a keyed hash of an address
 * and a day, never an address — see `VoterKeys` and V12's own comment for why that is
 * the better trade rather than the weaker one.
 */
object Votes : Table("votes") {
	val ticketId = javaUUID("ticket_id")
	val voterKey = text("voter_key")
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(ticketId, voterKey)
}

/** Where to look first, in the order a maintainer put the paths in. */
object TicketFiles : Table("ticket_files") {
	val ticketId = javaUUID("ticket_id")
	val path = text("path")
	val note = text("note").nullable()
	val position = integer("position")
	override val primaryKey = PrimaryKey(ticketId, path)
}

/** Which Notion page each imported row came from. See `V15__notion_import_origin.sql`. */
object NotionImportOrigins : Table("notion_import_origin") {
	val notionPageId = text("notion_page_id")
	val entityType = text("entity_type")
	val entityId = javaUUID("entity_id")
	val dataSourceId = text("data_source_id")
	val importedAt = timestampWithTimeZone("imported_at")
	override val primaryKey = PrimaryKey(notionPageId)
}
