package dev.kanso.service

import dev.kanso.domain.TemplateBody
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketTemplate
import dev.kanso.repo.CustomFieldRepository
import dev.kanso.repo.LabelRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * What a template asked for and this team cannot give.
 *
 * Carried out with the answer rather than logged and forgotten, the way `unestimated` travels
 * with every points total in this package. The composer prints it — "this template also sets
 * the label `regression`, which this team does not have" — and that sentence is the whole
 * difference between a template that degrades honestly and one that silently does less than
 * it claims.
 *
 * Names, not ids, because there is no id: these are precisely the ones that matched nothing.
 */
data class Unresolved(
	val labels: List<String> = emptyList(),
	val fields: List<String> = emptyList(),
) {
	val any: Boolean get() = labels.isNotEmpty() || fields.isNotEmpty()
}

/** A template read against one team: ids where there were names, and what stayed a name. */
data class ResolvedTemplate(
	val title: String?,
	val description: String?,
	val priority: TicketPriority?,
	val estimate: Int?,
	val labelIds: List<UUID>,
	/** Field id to the value `FieldValueCodec` normalised — never the raw one from the body. */
	val fieldValues: Map<UUID, Any>,
	val unresolved: Unresolved,
)

/**
 * The one place a template's names become a team's rows.
 *
 * **On the server rather than in the composer, and that is the load-bearing choice in this
 * feature.** Chantier A extends the MCP over the whole product, and an agent creating a
 * ticket from a template needs this same rule. Written in TypeScript in the browser it would
 * have to be written a second time in Kotlin for the agent, and the two would disagree within
 * a release — probably about case, probably about what happens to a field whose type changed
 * under a template that names it.
 *
 * Resolution at the point of use is not a new pattern being smuggled in. `TriageService`
 * already takes a `StatusCategory` and asks a team's catalogue which of its own words means
 * that; this asks a team's catalogue which of its own labels is called `bug`.
 *
 * A null team resolves nothing and reports everything, which is `V35`'s ruling about drafts
 * carried forward: a ticket nobody has filed is not yet part of any team's agreement about
 * how work is described.
 */
@Service
class TemplateResolver(
	private val labels: LabelRepository,
	private val fields: CustomFieldRepository,
) {

	@Transactional(readOnly = true)
	fun resolve(template: TicketTemplate, teamId: UUID?): ResolvedTemplate {
		val body = template.body
		if (teamId == null) {
			return ResolvedTemplate(
				title = body.title,
				description = body.description,
				priority = body.priority,
				estimate = body.estimate,
				labelIds = emptyList(),
				fieldValues = emptyMap(),
				unresolved = Unresolved(body.labels, body.fields.keys.toList()),
			)
		}

		val (labelIds, missingLabels) = resolveLabels(body, teamId)
		val (fieldValues, missingFields) = resolveFields(body, teamId)

		return ResolvedTemplate(
			title = body.title,
			description = body.description,
			priority = body.priority,
			estimate = body.estimate,
			labelIds = labelIds,
			fieldValues = fieldValues,
			unresolved = Unresolved(missingLabels, missingFields),
		)
	}

	/**
	 * Case-insensitively, because a team that wrote `Bug` and a template that says `bug` mean
	 * the same word, and a reader told otherwise would conclude the feature is broken. The
	 * codec already lower-cased the template's side, so only the team's needs folding.
	 */
	private fun resolveLabels(body: TemplateBody, teamId: UUID): Pair<List<UUID>, List<String>> {
		if (body.labels.isEmpty()) return emptyList<UUID>() to emptyList()
		val byName = labels.forTeam(teamId).associateBy { it.name.lowercase() }
		val found = mutableListOf<UUID>()
		val missing = mutableListOf<String>()
		body.labels.forEach { name ->
			byName[name]?.let { found += it.id } ?: run { missing += name }
		}
		return found to missing
	}

	/**
	 * A field resolves only if the team defines it **and** the template's value is one that
	 * definition can hold. Both failures land in the same list under the field's name, which is
	 * the honest reading: from the composer's side "this team has no Severity" and "this team's
	 * Severity cannot be `major`" are the same sentence — the value is not going to appear —
	 * and splitting them would put a distinction on screen that helps nobody standing in front
	 * of a form.
	 */
	private fun resolveFields(
		body: TemplateBody,
		teamId: UUID,
	): Pair<Map<UUID, Any>, List<String>> {
		if (body.fields.isEmpty()) return emptyMap<UUID, Any>() to emptyList()
		val byName = fields.forTeam(teamId).associateBy { it.name.lowercase() }
		val found = mutableMapOf<UUID, Any>()
		val missing = mutableListOf<String>()
		body.fields.forEach { (name, value) ->
			val definition = byName[name.lowercase()]
			if (definition == null) {
				missing += name
				return@forEach
			}
			// The definition's own validator, not a second copy of it: a `select` whose options
			// changed and a `number` given a word have to fail the same way here as they would on
			// the ticket itself. It is an `object`, so there is nothing to inject.
			runCatching { FieldValueCodec.validate(definition, value) }
				// A null is "clear this field", which a template has nothing to pre-fill with. Not
				// unresolved either — the team has the field and the value was legal — so it is
				// simply absent, and the composer draws the field empty like any other.
				.onSuccess { normalised -> normalised?.let { found[definition.id] = it } }
				.onFailure { missing += name }
		}
		return found to missing
	}
}
