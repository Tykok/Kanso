package dev.kanso.sync.notion

import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import kotlinx.coroutines.runBlocking
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
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
 * One account's own Notion identity, read-only.
 *
 * [connected] is about the instance, not the person: false means Notion is not wired up at
 * all, and the screen omits the section instead of drawing a field nobody can ever fill.
 * [member] is null when Notion is connected but this account has not been matched to a
 * workspace member — a real state, and a different one from having no Notion at all.
 */
data class MyNotionIdentity(
	val connected: Boolean,
	val notionPersonId: String?,
	val member: NotionMember?,
	val reason: String?,
)

/**
 * The standing correspondence between Notion people and Kanso accounts.
 *
 * `users.notion_person_id` is the only truth this reads or writes — [view] never
 * invents a link, and [link] never invents an account. Filling that column is what
 * lets [NotionSchema.people] finally write the `people` property the mirror stood up
 * but has never had anything to put in it.
 */
@Service
class NotionPeople(
	private val client: NotionClient,
	private val users: UserRepository,
	private val tx: TransactionTemplate,
) {

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
	 * The profile is fetched from the workspace rather than read off Kanso's row, so a name
	 * or an address changed in Notion reads correctly here with nothing synced. If the
	 * workspace cannot be reached the id is still returned — knowing *which* id you are is
	 * the part that does not depend on Notion answering.
	 */
	@Transactional(readOnly = true)
	fun mine(actor: User): MyNotionIdentity {
		if (!client.enabled) return MyNotionIdentity(false, null, null, NO_TOKEN)
		val id = actor.notionPersonId
			?: return MyNotionIdentity(true, null, null, "This account is not matched to a Notion member yet.")
		return try {
			val member = runBlocking { client.listUsers() }.firstOrNull { it.id == id }
			MyNotionIdentity(true, id, member, if (member == null) "That id names nobody in the workspace any more." else null)
		} catch (e: NotionRateLimited) {
			MyNotionIdentity(true, id, null, "Notion is rate-limiting this integration. Try again in ${e.retryAfter.toSeconds()}s.")
		} catch (e: NotionApiException) {
			MyNotionIdentity(true, id, null, if (e.status == 403) CAPABILITY_MISSING else "Notion refused the request: ${e.message}")
		}
	}

	/**
	 * Writes `users.notion_person_id` for a whole batch in one call — a screen
	 * resends its whole table, not one row, and [Map] already makes that first
	 * class. The guard lives here, not beside the caller: a private check the
	 * controller remembers to call is a check a second caller can forget, so
	 * nothing reaches [UserRepository.setNotionPersonId] without going through
	 * [requireConfigurator] first, on the same stack, in the same call. And it guards the
	 * *change*, not the call: a batch restating links the table already holds writes nothing
	 * and so asks for nothing, which is what lets an import re-send its whole people table
	 * without turning every importer into a configurator.
	 *
	 * Two passes over one read, not one pass that re-reads as it goes: clearing
	 * one entry and then re-querying "who holds what" for the next would see the
	 * batch's own prior write and could undo it — a swap, or "move this account's
	 * link and clear that one" in the same body, is exactly the shape that loses a
	 * link this way. So every account this batch must clear is worked out first,
	 * against the table as it was before any of this call's writes, and only then
	 * are the new links applied — a clear can never run after, and so never
	 * undo, a set made earlier in the same call.
	 *
	 * A `null` value clears; a non-null one first clears whoever else currently
	 * holds that Notion id — the column carries no unique constraint, but two
	 * accounts claiming one Notion person is exactly how the mirror ends up
	 * writing the wrong `people` value, so this keeps the correspondence
	 * one-to-one even though the schema does not enforce it.
	 *
	 * **That last sentence is a divergence, and `KAN-54` is the reason it stays one.** This
	 * takes a claimed Notion id away from whoever holds it. The account holder's own side of
	 * the same question — "I am this Notion person" — does not exist at all: there is no
	 * `PUT /api/me/notion-identity`, [mine] reads and nothing writes, and
	 * `AccountIsNotEditableTest` is what keeps it that way. Two rules, on purpose, and
	 * neither is the other's oversight:
	 *
	 * - Here the actor is a configurator matching a whole workspace to a whole instance, and
	 *   moving a link is the correction they are on this screen to make. Refusing it would
	 *   leave an instance with a wrong match and no way to fix it.
	 * - There the actor would be claiming one identity, their own, with nobody checking. The
	 *   legitimate case that a self-service write served — recovering your own identity after
	 *   an email change re-matched you to nobody — is rare, visible, and repairable by hand
	 *   or by a configurator's reassignment. Claiming a colleague's is none of those: the
	 *   mirror silently credits their work to you in a workspace Kanso does not control, and
	 *   nothing anywhere says it happened.
	 *
	 * So do not harmonise the two. If a self-service write is ever wanted back, it refuses a
	 * taken id — 409, not a steal — because the two acts differ in who is checking, not in
	 * what they write.
	 */
	@Transactional
	fun link(actor: User, assignments: Map<String, UUID?>) {
		val accounts = users.findAll()
		val toClear = accounts.filter { account ->
			val notionId = account.notionPersonId
			notionId != null && assignments.containsKey(notionId) && assignments.getValue(notionId) != account.id
		}
		val byId = accounts.associateBy { it.id }
		val toSet = assignments.mapNotNull { (notionId, userId) ->
			userId?.takeIf { byId[it]?.notionPersonId != notionId }?.let { notionId to it }
		}
		// A batch that asks for the correspondence the table already holds changes nothing,
		// so it needs no rights. Not the same rule as "the map is empty", which is what this
		// used to check: `NotionImportService.perform` re-sends every already-confirmed link
		// for every row the reader left alone, so a non-configurator importing into a
		// workspace whose people are already matched sent a non-empty map that would change
		// nothing and got a 403 for the whole import. Checked against the table, once,
		// against the same read the two passes below use.
		if (toClear.isEmpty() && toSet.isEmpty()) return
		requireConfigurator(actor)
		toClear.forEach { users.setNotionPersonId(it.id, null) }
		toSet.forEach { (notionId, userId) -> users.setNotionPersonId(userId, notionId) }
	}

	/** Configuring the instance's identities is a configurator's job, like the connection itself. */
	private fun requireConfigurator(actor: User) {
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
		/*
		 * A transaction of its own, opened here and not around [view], because the call
		 * above it is an HTTP request to Notion: annotating the caller would hold a pooled
		 * connection for the whole of a round trip this service is rate-limited on. Opened
		 * at all because neither of this method's two callers is in one — `view` is read
		 * straight off the controller, and the `view` that follows a `link` runs after that
		 * write has committed. Exposed needs one, so both answered 500 while writing the
		 * rows they were asked to. `SetupController.currentState` carries the same note for
		 * the same reason.
		 */
		val accounts = tx.execute { users.findAll() }.orEmpty()
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
