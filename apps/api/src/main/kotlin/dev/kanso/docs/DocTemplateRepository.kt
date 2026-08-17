package dev.kanso.docs

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

/**
 * The three templates `V9` seeds. Read-only: there is no screen that makes a fourth,
 * and inventing a writer for one would be inventing the screen too.
 */
@Repository
class DocTemplateRepository(private val json: ObjectMapper) {

	fun findAll(): List<DocTemplate> =
		DocTemplates.selectAll().orderBy(DocTemplates.name to SortOrder.ASC).map { it.toTemplate() }

	fun findBySlug(slug: String): DocTemplate? =
		DocTemplates.selectAll().where { DocTemplates.slug eq slug }.singleOrNull()?.toTemplate()

	private fun ResultRow.toTemplate() = DocTemplate(
		id = this[DocTemplates.id],
		slug = this[DocTemplates.slug],
		name = this[DocTemplates.name],
		summary = this[DocTemplates.summary],
		blocks = decodeBlocks(this[DocTemplates.blocks]),
	)

	/**
	 * A template's blocks, as `[{kind, content}, …]`.
	 *
	 * The unchecked casts are on a value `V9`'s own `INSERT` wrote and nothing else can
	 * change — the table has no writer. A malformed kind still goes through
	 * [DocBlockKind.from], which refuses it rather than adopting it.
	 */
	@Suppress("UNCHECKED_CAST")
	private fun decodeBlocks(raw: String): List<DocTemplateBlock> =
		(json.readValue(raw, List::class.java) as List<Map<String, Any?>>).map {
			DocTemplateBlock(
				kind = DocBlockKind.from(it["kind"] as String),
				content = it["content"] as? Map<String, Any?> ?: emptyMap(),
			)
		}
}
