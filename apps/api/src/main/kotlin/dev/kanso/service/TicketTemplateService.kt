package dev.kanso.service

import dev.kanso.domain.TemplateBody
import dev.kanso.domain.TicketTemplate
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketTemplateRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The catalogue, at two levels, and who may change each.
 *
 * **The guard splits on the level and reuses what already exists.** A team template goes
 * through [TicketAccess.requireTeam], the same boundary `LabelService.create` and
 * `CustomFieldService.define` use, and that method already lets an instance admin through —
 * so "a Kanso admin may add templates at any level" needed no new rule. An instance template
 * requires `InstanceRole.canConfigureInstance`, which is the only new sentence in this file.
 *
 * Both refuse a viewer before either check — `requireTeam` asks `requireSeatThatWrites`
 * first, and [requireConfigurator] asks `mayWrite` first — so the read-only seat is enforced
 * here without this file naming it, including for a viewer's agent, since every writing tool
 * reaches a service that reaches here.
 *
 * Reading takes no actor at all. A template's name is a word a screen prints, like a label or
 * a status, and `docs/architecture.md`'s rule is that reads are open and writes are scoped.
 */
@Service
class TicketTemplateService(
	private val templates: TicketTemplateRepository,
	private val teams: TeamRepository,
	private val access: TicketAccess,
	private val resolver: TemplateResolver,
) {

	@Transactional(readOnly = true)
	fun list(teamId: UUID?): List<TicketTemplate> = templates.available(teamId)

	@Transactional(readOnly = true)
	fun get(id: UUID): TicketTemplate =
		templates.findById(id) ?: throw NotFoundException("No template $id")

	/**
	 * A template read against the team the composer is currently pointed at, which is not
	 * necessarily the template's own — that is the whole point of an instance template.
	 */
	@Transactional(readOnly = true)
	fun resolved(id: UUID, teamId: UUID?): ResolvedTemplate = resolver.resolve(get(id), teamId)

	@Transactional
	fun create(
		actor: User,
		teamId: UUID?,
		name: String,
		summary: String?,
		body: TemplateBody,
		categories: List<String>,
	): TicketTemplate {
		// First, as every write in this package does, and before the name is even looked at.
		requireLevel(actor, teamId)
		teamId?.let { if (teams.findById(it) == null) throw BadRequestException("No team $it") }

		val trimmed = requireName(name)
		// Pre-checked rather than left to the partial index, so a second `Bug` is the 409 it is
		// instead of a 500 from the driver. The index is still the backstop, and it is the only
		// thing that holds under a concurrent create.
		if (templates.findByName(teamId, trimmed) != null) {
			throw ConflictException(
				if (teamId == null) "Kanso already ships a template called $trimmed"
				else "${teams.findById(teamId)?.name ?: "That team"} already has a template called $trimmed",
			)
		}

		val now = OffsetDateTime.now()
		return templates.insert(
			TicketTemplate(
				id = UUID.randomUUID(),
				teamId = teamId,
				name = trimmed,
				summary = summary?.trim()?.ifEmpty { null },
				body = body,
				categories = categories,
				createdAt = now,
				updatedAt = now,
			),
		)
	}

	/**
	 * A template does not change level. Moving one from the instance into a team, or the other
	 * way, is a different object with a different audience and a different guard, and
	 * expressing it as an edit would mean one request that has to pass *both* checks and a name
	 * that has to be free on *both* sides. Delete and re-create says the same thing in two
	 * gestures that are each obviously safe.
	 */
	@Transactional
	fun update(
		actor: User,
		id: UUID,
		name: String,
		summary: String?,
		body: TemplateBody,
		categories: List<String>,
	): TicketTemplate {
		val existing = get(id)
		requireLevel(actor, existing.teamId)

		val trimmed = requireName(name)
		if (trimmed != existing.name) {
			templates.findByName(existing.teamId, trimmed)?.let {
				throw ConflictException("There is already a template called $trimmed at that level")
			}
		}
		return templates.update(id, trimmed, summary?.trim()?.ifEmpty { null }, body, categories)
	}

	@Transactional
	fun delete(actor: User, id: UUID) {
		val existing = get(id)
		requireLevel(actor, existing.teamId)
		// Nothing points at a template — no ticket remembers being made from one, by design — so
		// there is no count to print and nothing to cascade but its own categories.
		templates.delete(id)
	}

	/**
	 * The one new rule in this feature, and it is one line.
	 *
	 * A null team means the instance catalogue, which belongs to whoever configures the
	 * instance. Everything else is the team boundary this product already has.
	 */
	private fun requireLevel(actor: User, teamId: UUID?) {
		if (teamId == null) requireConfigurator(actor) else access.requireTeam(actor, teamId)
	}

	private fun requireConfigurator(actor: User) {
		// The seat first and said differently, following `TicketAccess.requireSeatThatWrites`:
		// telling a viewer they are not an admin invites them to ask to become one, which would
		// change nothing.
		if (!actor.instanceRole.mayWrite) {
			throw AccessDeniedException(TicketAccess.READS_NOT_WRITES)
		}
		if (!actor.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Kanso's own templates are an instance administrator's")
		}
	}

	private fun requireName(name: String): String {
		val trimmed = name.trim()
		if (trimmed.isEmpty()) throw BadRequestException("A template needs a name")
		if (trimmed.length > 80) {
			throw BadRequestException("A template name is at most 80 characters")
		}
		return trimmed
	}
}
