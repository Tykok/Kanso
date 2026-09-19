package dev.kanso.repo

import dev.kanso.db.TicketTemplateCategories
import dev.kanso.db.TicketTemplates
import dev.kanso.domain.TemplateBody
import dev.kanso.domain.TicketTemplate
import dev.kanso.service.TemplateBodyCodec
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `ticket_templates` and its categories, in one repository because neither is readable
 * without the other — the shape `CustomFieldRepository` already has for its two tables.
 *
 * Categories are read in a second query and never as a join onto the templates, which would
 * return one row per category and make the caller de-duplicate a template it already has.
 * Two queries for a whole list, the same arithmetic `CustomFieldService.list` makes for its
 * value counts.
 */
@Repository
class TicketTemplateRepository(private val codec: TemplateBodyCodec) {

	fun findById(id: UUID): TicketTemplate? =
		TicketTemplates.selectAll().where { TicketTemplates.id eq id }
			.singleOrNull()
			?.let { it.toTemplate(categoriesFor(listOf(it[TicketTemplates.id]))) }

	/**
	 * Everything a composer in [teamId] may offer: the instance catalogue plus that team's own.
	 * A null [teamId] is a draft, or the settings screen editing the shipped catalogue, and
	 * gets the instance level alone.
	 *
	 * Ordered so the picker's two groups arrive already grouped and the client sorts nothing.
	 * Kanso's come first because they are the ones a fresh instance has.
	 */
	fun available(teamId: UUID?): List<TicketTemplate> {
		val rows = TicketTemplates.selectAll()
			.where {
				if (teamId == null) TicketTemplates.teamId.isNull()
				else TicketTemplates.teamId.isNull() or (TicketTemplates.teamId eq teamId)
			}
			.orderBy(TicketTemplates.name to SortOrder.ASC)
			.toList()
			// Sorted here rather than by SQL because "Kanso's first" is an ordering over a
			// nullable column, and the three dialects of NULLS FIRST are more surface than one
			// stable partition of a list this short ever pays for.
			.sortedBy { it[TicketTemplates.teamId] != null }
		val categories = categoriesFor(rows.map { it[TicketTemplates.id] })
		return rows.map { it.toTemplate(categories) }
	}

	/** Exactly the comparison the two partial indexes make, so a pre-check cannot miss. */
	fun findByName(teamId: UUID?, name: String): TicketTemplate? =
		TicketTemplates.selectAll()
			.where {
				val sameName = TicketTemplates.name eq name
				if (teamId == null) sameName and TicketTemplates.teamId.isNull()
				else sameName and (TicketTemplates.teamId eq teamId)
			}
			.singleOrNull()
			?.let { it.toTemplate(categoriesFor(listOf(it[TicketTemplates.id]))) }

	fun insert(template: TicketTemplate): TicketTemplate {
		TicketTemplates.insert {
			it[TicketTemplates.id] = template.id
			it[TicketTemplates.teamId] = template.teamId
			it[TicketTemplates.name] = template.name
			it[TicketTemplates.summary] = template.summary
			it[TicketTemplates.body] = codec.encode(template.body)
			it[TicketTemplates.createdAt] = template.createdAt
			it[TicketTemplates.updatedAt] = template.updatedAt
		}
		return template.copy(categories = writeCategories(template.id, template.categories))
	}

	/**
	 * Categories are deleted and re-inserted rather than diffed, which is the same ruling
	 * `TicketService.setAssignees` makes about a set that arrives whole: a diff is three
	 * statements and a correctness argument where this is two statements and none.
	 */
	fun update(
		id: UUID,
		name: String,
		summary: String?,
		body: TemplateBody,
		categories: List<String>,
	): TicketTemplate {
		TicketTemplates.update({ TicketTemplates.id eq id }) {
			it[TicketTemplates.name] = name
			it[TicketTemplates.summary] = summary
			it[TicketTemplates.body] = codec.encode(body)
			it[TicketTemplates.updatedAt] = OffsetDateTime.now()
		}
		TicketTemplateCategories.deleteWhere { templateId eq id }
		writeCategories(id, categories)
		return findById(id) ?: error("Template $id vanished inside its own update")
	}

	fun delete(id: UUID) {
		// The categories go with it through `ON DELETE CASCADE`, which is why there is no second
		// statement here — and why removing that cascade would silently orphan rows.
		TicketTemplates.deleteWhere { TicketTemplates.id eq id }
	}

	private fun writeCategories(templateId: UUID, categories: List<String>): List<String> {
		val clean = categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
		clean.forEach { category ->
			TicketTemplateCategories.insert {
				it[TicketTemplateCategories.templateId] = templateId
				it[TicketTemplateCategories.name] = category
			}
		}
		// Sorted to match what a later read returns, so a caller cannot tell an inserted
		// template from a re-read one — the property the update path depends on.
		return clean.sorted()
	}

	private fun categoriesFor(ids: List<UUID>): Map<UUID, List<String>> {
		if (ids.isEmpty()) return emptyMap()
		return TicketTemplateCategories.selectAll()
			.where { TicketTemplateCategories.templateId inList ids }
			.orderBy(TicketTemplateCategories.name to SortOrder.ASC)
			.groupBy(
				{ it[TicketTemplateCategories.templateId] },
				{ it[TicketTemplateCategories.name] },
			)
	}

	private fun ResultRow.toTemplate(categories: Map<UUID, List<String>>) = TicketTemplate(
		id = this[TicketTemplates.id],
		teamId = this[TicketTemplates.teamId],
		name = this[TicketTemplates.name],
		summary = this[TicketTemplates.summary],
		body = codec.decode(this[TicketTemplates.body]),
		categories = categories[this[TicketTemplates.id]] ?: emptyList(),
		createdAt = this[TicketTemplates.createdAt],
		updatedAt = this[TicketTemplates.updatedAt],
	)
}
