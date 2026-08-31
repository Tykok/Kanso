package dev.kanso.sync.notion

import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import kotlinx.coroutines.runBlocking
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * One Notion workspace member, next to what Kanso already knows about it.
 *
 * [userId] and [suggestedUserId] are kept apart on purpose: [userId] is what
 * `users.notion_person_id` already says, and [suggestedUserId] is a guess a screen
 * may offer as a one-click accept but must never apply on its own — a homonym
 * silently owning somebody's work is the failure [NotionPeople] exists to prevent.
 */
data class PeopleMatch(val notion: NotionMember, val userId: UUID?, val suggestedUserId: UUID?)

/**
 * The workspace's members, or the reason they could not be read.
 *
 * [available] is false rather than [people] being empty when the read is refused —
 * the same shape [dev.kanso.sync.importer.NotionImportService] answers with for its
 * own discovery, and for the same reason: an empty list here would read as "this
 * workspace has nobody in it", the one wrong conclusion available.
 */
data class PeopleView(val available: Boolean, val reason: String?, val people: List<PeopleMatch>)

/**
 * The standing correspondence between Notion people and Kanso accounts.
 *
 * `users.notion_person_id` is the only truth this reads or writes — [view] never
 * invents a link, and [link] never invents an account. Filling that column is what
 * lets [NotionSchema.people] finally write the `people` property the mirror stood up
 * but has never had anything to put in it.
 */
@Service
class NotionPeople(private val client: NotionClient, private val users: UserRepository) {

	/**
	 * The workspace's members and what Kanso already knows about each.
	 *
	 * `GET /users` needs the integration's "read user information" capability, which
	 * is off by default — a 403 is caught here and turned into the sentence naming
	 * that box, the same story [dev.kanso.sync.importer.NotionImportService] tells
	 * for a search Notion refuses. [NoopNotionClient] answers an empty list rather
	 * than throwing, which is why the "no token at all" case is told apart by
	 * checking [NotionClient.enabled] first, ahead of the call that would otherwise
	 * look like a workspace with nobody in it.
	 */
	fun view(): PeopleView {
		if (!client.enabled) return unavailable(NO_TOKEN)
		return try {
			val members = runBlocking { client.listUsers() }
			PeopleView(available = true, reason = null, people = match(members))
		} catch (e: NotionRateLimited) {
			unavailable("Notion is rate-limiting this integration. Try again in ${e.retryAfter.toSeconds()}s.")
		} catch (e: NotionApiException) {
			unavailable(if (e.status == 403) CAPABILITY_MISSING else "Notion refused the request: ${e.message}")
		}
	}

	/**
	 * Writes `users.notion_person_id`, one Notion id at a time. A `null` value
	 * clears; a non-null one first clears whoever else currently holds that Notion
	 * id — the column carries no unique constraint, but two accounts claiming one
	 * Notion person is exactly how the mirror ends up writing the wrong `people`
	 * value, so this keeps the correspondence one-to-one even though the schema
	 * does not enforce it.
	 */
	@Transactional
	fun link(assignments: Map<String, UUID?>) {
		val holders = users.findAll().filter { it.notionPersonId != null }
		assignments.forEach { (notionId, userId) ->
			holders.filter { it.notionPersonId == notionId && it.id != userId }
				.forEach { users.setNotionPersonId(it.id, null) }
			if (userId != null) users.setNotionPersonId(userId, notionId)
		}
	}

	/** Configuring the instance's identities is a configurator's job, like the connection itself. */
	fun requireConfigurator(actor: User) {
		if (!actor.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can match Notion people to Kanso accounts")
		}
	}

	/**
	 * A suggestion by email first, then by exact display name — computed for every
	 * member regardless of whether one is already linked, and never applied here;
	 * [link] is the only thing that ever writes `notion_person_id`.
	 *
	 * Sorted once, by name then email then id, so a screen reloading the same page
	 * twice sees the same order: `GET /users` promises no ordering of its own.
	 */
	private fun match(members: List<NotionMember>): List<PeopleMatch> {
		val accounts = users.findAll()
		val byNotionId = accounts.filter { it.notionPersonId != null }.associateBy { it.notionPersonId }
		val byEmail = accounts.associateBy { it.email.lowercase() }
		val byName = accounts.associateBy { it.displayName }
		return members.map { member ->
			val suggested = member.email?.let { byEmail[it.lowercase()] } ?: member.name?.let { byName[it] }
			PeopleMatch(notion = member, userId = byNotionId[member.id]?.id, suggestedUserId = suggested?.id)
		}.sortedWith(ORDER)
	}

	private fun unavailable(reason: String) = PeopleView(available = false, reason = reason, people = emptyList())

	private companion object {
		const val NO_TOKEN =
			"No Notion token is configured, so Kanso has no workspace member list to read yet. " +
				"Connect Notion in the setup wizard, then reload this list."

		const val CAPABILITY_MISSING =
			"This integration cannot read the workspace's members. Tick **read user information** on " +
				"its capabilities in Notion, then reload — Kanso needs it only to match Notion people to " +
				"Kanso accounts."

		/** Name, then email, then id — nulls sort last, so an unnamed guest does not jump to the top. */
		val ORDER: Comparator<PeopleMatch> =
			compareBy<PeopleMatch, String?>(nullsLast()) { it.notion.name }
				.thenBy(nullsLast()) { it.notion.email }
				.thenBy { it.notion.id }
	}
}
