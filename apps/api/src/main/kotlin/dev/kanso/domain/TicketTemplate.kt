package dev.kanso.domain

import java.time.OffsetDateTime
import java.util.UUID

/**
 * What a template puts in the composer.
 *
 * **Every field is optional and null means "do not pre-fill this".** That is not the same as
 * an empty string: `title = ""` says the composer's title starts blank, and `title = null`
 * says the composer's own seeding decides. The distinction is what lets a template carry a
 * description and leave everything else to the ordinary creation path, and a codec that
 * collapsed the two would take that away.
 *
 * [labels] and [fields] name **words, not identifiers**, and that is forced rather than
 * chosen: an instance template is written before any team exists, so there is no `label_id`
 * for it to hold. It is also the better answer for a team template, because an id would have
 * to be either cascaded on delete — silently losing part of the template — or blocked, making
 * a label undeletable because a template mentions it. `TemplateResolver` is where a name
 * becomes a row.
 *
 * There is no `status`, no `projectId` and no `assigneeIds`. Status because a template spans
 * teams whose vocabularies differ and "which column does a new ticket start in" is already
 * answered by the team's first status. Project and assignee because both are decisions about
 * this particular piece of work — a template that pre-assigns somebody files work onto a
 * person who never saw it.
 */
data class TemplateBody(
	val title: String? = null,
	val description: String? = null,
	val priority: TicketPriority? = null,
	/** Points, off [EffortPoints.SCALE]. */
	val estimate: Int? = null,
	/** Label names, lower-cased and de-duplicated by the codec. */
	val labels: List<String> = emptyList(),
	/**
	 * Custom field name to its value, as a **JSON scalar** — string, number or boolean.
	 *
	 * Not stringified, and that is not a detail. `FieldValueCodec.validate` deliberately
	 * refuses `"true"` for a boolean field — "a field that accepted either would be a text
	 * field with a checkbox drawn on it" — so a template whose values were all strings could
	 * never tick a checkbox or set a number, and both would surface as `unresolved` with
	 * nothing wrong with them. The type is loose here because it is exactly as loose as what
	 * `validate` accepts, and narrowing it would mean re-deciding per type on this side.
	 */
	val fields: Map<String, Any?> = emptyMap(),
)

/**
 * A template, at whichever of the two levels it lives.
 *
 * [teamId] null is the instance level — see `V43`'s header for why that is permitted here
 * when `V35` refused it for a custom field.
 */
data class TicketTemplate(
	val id: UUID,
	val teamId: UUID?,
	val name: String,
	val summary: String?,
	val body: TemplateBody,
	val categories: List<String>,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)
