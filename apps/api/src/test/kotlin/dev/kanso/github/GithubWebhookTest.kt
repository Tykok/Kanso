package dev.kanso.github

import dev.kanso.MockMvcTest
import dev.kanso.config.KansoProperties
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.ActivityRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inbound half, through the filter chain, with payloads GitHub would recognise.
 *
 * **The signature tests come first because the endpoint is unauthenticated and writes
 * rows.** One HMAC stands between `/api/github/webhook` and a stranger moving somebody's
 * tickets, and every one of those tests asserts two things rather than one: that the request
 * was refused, *and* that nothing was written. "It answered 401" and "no row moved" are two
 * claims, and only the second is the one that matters — the same discipline
 * `UnguardedWriteTest` records for the routes it guarded.
 *
 * `MockMvcTest` and not `PostgresTest`, and it is not a preference: the controller takes the
 * **raw body** because an HMAC of a body Jackson has parsed and re-serialised does not match,
 * so a test that called the controller method directly would hand it bytes the framework
 * never touched and prove nothing about the path a delivery actually takes.
 *
 * Every payload is a committed fixture in `src/test/resources/github/`, signed with the
 * secret the test profile pins, read back off `KansoProperties` so the two cannot drift. The
 * ticket key is substituted into each one — see [payload] — because a fixture that named a
 * ticket this test did not seed would assert about somebody else's row.
 */
@Transactional
class GithubWebhookTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var teamService: TeamService
	@Autowired lateinit var ticketService: TicketService
	@Autowired lateinit var tickets: TicketRepository
	@Autowired lateinit var github: GithubRepository
	@Autowired lateinit var accounts: GithubAccountRepository
	@Autowired lateinit var activityLog: ActivityRepository
	@Autowired lateinit var props: KansoProperties
	@Autowired lateinit var objectMapper: ObjectMapper

	private val admin: User by lazy {
		users.createLocalUser(
			email = "webhook-${UUID.randomUUID()}@kanso.test",
			displayName = "An administrator",
			passwordHash = "not-a-real-hash",
			role = InstanceRole.ADMIN,
		)
	}

	/** Letters only, so the substituted branch name cannot surprise `PrLinkParser`'s regex. */
	private val team by lazy {
		teamService.create(admin, "GitHub", (1..4).map { ('A'..'Z').random() }.joinToString(""), null)
	}

	private val ticket by lazy {
		ticketService.create(
			actor = admin,
			teamId = team.id,
			title = "Warn when two cycles overlap",
			description = null,
			status = "todo",
			priority = TicketPriority.MEDIUM,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)
	}

	/** `KANQ-1`, the identifier the fixtures are rewritten to name. */
	private val identifier: String by lazy { "${team.key}-${ticket.ticket.number}" }

	/**
	 * A fixture, with `KAN-142` rewritten to the ticket this test actually seeded.
	 *
	 * Both cases are rewritten, and that is not tidiness: the fixtures carry `KAN-142` in a
	 * body and `kan-142` in a branch name, because that is how a real pull request looks —
	 * branches are lowercase in practice and keys are uppercase in Kanso. Rewriting only one
	 * would silently drop the branch signal, which is the strongest one the parser has, and
	 * the suite would still pass on the keyword alone.
	 */
	private fun payload(name: String): String =
		requireNotNull(javaClass.getResourceAsStream("/github/$name.json")) { "no fixture $name" }
			.use { it.readBytes().decodeToString() }
			.replace("KAN-142", identifier)
			.replace("kan-142", identifier.lowercase())

	private fun deliver(
		event: String,
		body: String,
		deliveryId: UUID = UUID.randomUUID(),
		signWith: String? = props.github.webhookSecret,
	) = mvc.perform(
		MockMvcRequestBuilders.post(GITHUB_WEBHOOK)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.also { request ->
				// No `DevAuthenticationFilter` header anywhere in this file: GitHub has no
				// session, and a 204 from here is therefore also the proof that
				// `SecurityConfig` opened the route.
				signWith?.let { request.header(GithubSignature.HEADER, GithubSignature.sign(body.toByteArray(), it)) }
			}
			.header(GithubSignature.DELIVERY_HEADER, deliveryId.toString())
			.header(GithubSignature.EVENT_HEADER, event),
	).andReturn().response

	private fun statusOf() = tickets.findById(ticket.ticket.id)?.status

	private fun activityOf(kind: ActivityKind) =
		activityLog.forEntity(ActivityEntity.TICKET, ticket.ticket.id, 50).filter { it.kind == kind }

	/**
	 * The `status_changed` rows that say they reached [status], read as JSON rather than
	 * matched as text.
	 *
	 * **`payload` is `jsonb`, so what comes back is not the string that went in.** Postgres
	 * reserialises it — `": "` instead of `":"`, and the keys in its own order — so a
	 * `contains("\"to\":\"done\"")` matches nothing and does it silently, reporting zero rows
	 * for a transition that happened. Worth the helper: it is the shape of assertion that
	 * would pass for the wrong reason on the next payload somebody adds a key to.
	 */
	private fun movedTo(status: String) = activityOf(ActivityKind.STATUS_CHANGED)
		.filter { objectMapper.readTree(it.payload).path("to").asText(null) == status }

	private fun pullRequest(number: Int = 418) = github.findByRepoAndNumber("tykok/kanso", number)

	// --- the door ------------------------------------------------------------

	/**
	 * The first test written, and the one the whole endpoint rests on.
	 *
	 * Red without `GithubSignature.verify` in the controller: the delivery is accepted, the
	 * pull request row appears and the ticket moves — which is a stranger moving somebody's
	 * ticket with a `curl`.
	 */
	@Test
	fun `an unsigned delivery is refused and writes nothing`() {
		val response = deliver("pull_request", payload("pull_request_opened"), signWith = null)

		assertEquals(401, response.status, "an unsigned delivery is refused")
		assertEquals("", response.contentAsString, "and it is refused with no body, which tells a stranger nothing")
		assertNull(pullRequest(), "nothing was written, which is the half that matters")
		assertEquals("todo", statusOf(), "and the ticket did not move")
	}

	/**
	 * The same claim for the case that actually happens: a signature that is present and
	 * wrong, which is what a rotated secret and a forgery both look like.
	 */
	@Test
	fun `a delivery signed with the wrong secret is refused and writes nothing`() {
		val response = deliver(
			"pull_request",
			payload("pull_request_opened"),
			signWith = "not-the-secret-this-instance-holds",
		)

		assertEquals(401, response.status, "a wrong digest is refused")
		assertNull(pullRequest(), "and wrote nothing")
		assertEquals("todo", statusOf(), "and moved nothing")
	}

	/**
	 * A signed delivery from a caller with no session at all is answered — which is the only
	 * assertion in this file that proves `SecurityConfig` opened the route, since a route it
	 * had *not* opened would answer the unsigned case above with the same 401.
	 */
	@Test
	fun `a correctly signed delivery is answered without a session`() {
		val response = deliver("pull_request", payload("pull_request_opened"))

		assertEquals(204, response.status, "GitHub gets a 204 and no body: ${response.contentAsString}")
		assertNotNull(pullRequest(), "and the pull request is stored")
	}

	/** `X-GitHub-Delivery` is a UUID because `github_deliveries.delivery_id` is one. */
	@Test
	fun `a signed delivery with an unusable delivery id is refused`() {
		val body = payload("pull_request_opened")
		val response = mvc.perform(
			MockMvcRequestBuilders.post(GITHUB_WEBHOOK)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)
				.header(GithubSignature.HEADER, GithubSignature.sign(body.toByteArray(), props.github.webhookSecret))
				.header(GithubSignature.DELIVERY_HEADER, "not-a-uuid")
				.header(GithubSignature.EVENT_HEADER, "pull_request"),
		).andReturn().response

		assertEquals(400, response.status, "a malformed delivery id is refused by the type, not inserted")
		assertNull(pullRequest(), "and nothing was written")
	}

	/**
	 * **A redelivery produces nothing** — the outcome the owner asked for, asserted end to
	 * end.
	 *
	 * What this test does *not* prove is worth writing down, because it took disabling
	 * `firstDelivery` to find out: **it stays green without the delivery table.** A
	 * sequential replay is already inert for two reasons that have nothing to do with
	 * `github_deliveries` — guard one refuses a move to a rank the ticket already holds, so
	 * the second merge cannot write a second `status_changed`; and
	 * `GithubRepository.linkedTickets` refuses a second `pull_request_linked` for a link
	 * that already exists.
	 *
	 * So `V36`'s sentence — *the feed pays for this table* — is right about the danger and
	 * one layer off about who stops it in the sequential case. What the table actually earns
	 * its keep against is **concurrent** redelivery: two deliveries of one event in flight
	 * at once, both reading the ticket before either writes, which is exactly why
	 * `firstDelivery` is an insert-or-skip rather than a `SELECT` then an `INSERT` and is
	 * not something a single-threaded test can produce. The isolating assertion is
	 * [a second delivery bearing an id already seen is dropped whatever it carries] below.
	 */
	@Test
	fun `a replayed delivery writes nothing twice`() {
		linkGithub("maintainer", 5150)
		deliver("pull_request", payload("pull_request_opened"))
		val replayed = UUID.randomUUID()
		val merge = payload("pull_request_merged")

		assertEquals(204, deliver("pull_request", merge, deliveryId = replayed).status)
		assertEquals(204, deliver("pull_request", merge, deliveryId = replayed).status, "a redelivery is still a 204")

		assertEquals("done", statusOf(), "the merge moved it once")
		assertEquals(
			1,
			movedTo("done").size,
			"exactly one status_changed to done, however many times GitHub sends the delivery",
		)
		assertEquals(
			1,
			activityOf(ActivityKind.PULL_REQUEST_LINKED).size,
			"and one pull_request_linked, not one per delivery",
		)
	}

	/**
	 * The delivery id, isolated — and it is the only test here that can fail if
	 * `firstDelivery` is removed.
	 *
	 * **The payload pair is deliberately unrealistic**, and that is the whole design of the
	 * test: GitHub never reuses a delivery id for a different pull request. But a *realistic*
	 * replay is absorbed by guard one before the delivery table is ever consulted, so a test
	 * built from one cannot fail and therefore proves nothing. Handing the second call an
	 * effect that guard one has no opinion about — a different pull request, number 419 — is
	 * what makes the delivery id the only thing standing between it and a written row.
	 *
	 * Red without `firstDelivery`: 419 is stored and linked.
	 */
	@Test
	fun `a second delivery bearing an id already seen is dropped whatever it carries`() {
		val seen = UUID.randomUUID()
		assertEquals(204, deliver("pull_request", payload("pull_request_opened"), deliveryId = seen).status)
		assertNotNull(pullRequest(), "the first delivery was handled")

		assertEquals(
			204,
			deliver("pull_request", payload("pull_request_mention"), deliveryId = seen).status,
			"a redelivery is answered exactly like a first delivery, which is what stops it being an oracle",
		)

		assertNull(pullRequest(419), "and nothing it carried was written, because its id had been seen")
	}

	/** `synchronize` changes no field the parser reads and no field the row stores. */
	@Test
	fun `a synchronize delivery is answered and stores nothing`() {
		val body = payload("pull_request_opened").replace("\"action\": \"opened\"", "\"action\": \"synchronize\"")

		assertEquals(204, deliver("pull_request", body).status)
		assertNull(pullRequest(), "new commits are not a reason to write a row")
	}

	// --- the fan-out ---------------------------------------------------------

	/**
	 * The whole of the inbound half on one delivery: a row, a closing link, a feed line and
	 * a forward move.
	 *
	 * `opened` transitions on purpose, and the clause is easy to miss:
	 * `ready_for_review` fires only when a draft is *promoted*, so a pull request opened
	 * ready would otherwise move nothing — which is most pull requests.
	 */
	@Test
	fun `an opened pull request stores itself, links its ticket and moves it to in review`() {
		linkGithub("elie", 4021)
		assertEquals(204, deliver("pull_request", payload("pull_request_opened")).status)

		val stored = assertNotNull(pullRequest(), "the row exists, which it could not before this ticket")
		assertEquals(PrState.OPEN, stored.state)
		assertEquals("elie", stored.authorLogin)
		assertEquals("main", stored.baseRef)
		assertEquals(identifier.lowercase(), stored.headRef.removePrefix("feat/").removeSuffix("-overlap-warning"))

		assertEquals(
			listOf(ticket.ticket.id),
			github.ticketsClosedBy(stored.id),
			"the branch and the `Fixes` both name it, so the link closes",
		)
		assertEquals("in_review", statusOf(), "and a pull request opened ready is in review")
		assertEquals(1, activityOf(ActivityKind.PULL_REQUEST_LINKED).size, "the feed can answer why it is there")
	}

	/**
	 * Links `admin` to a GitHub login, which is what makes a transition possible at all.
	 *
	 * Called by the tests that are about *something else* — storage, replay, review state —
	 * because a delivery whose sender resolves to nobody now moves no ticket, and a fixture
	 * that left them unlinked would have them asserting the refusal instead of their subject.
	 * `elie` is the sender of the opened fixture and `maintainer` of the merged one, so a
	 * test that delivers both links both.
	 */
	private fun linkGithub(login: String, githubUserId: Long) = accounts.link(
		userId = admin.id,
		githubUserId = githubUserId,
		githubLogin = login,
		token = GithubToken("gho-not-a-real-token", null, null),
	)

	/**
	 * **The transition's authorisation, at the one door that is not a Kanso request.**
	 *
	 * What arrives here is text the pull request's *author* wrote — `PrLinkParser` reads the
	 * branch name, the title and the body — and `opened` requires no relationship with the
	 * repository. So on a public repository this is a stranger typing `Fixes KAN-142` into a
	 * pull request title and moving a ticket in a team they are not in, on an instance where
	 * they have no account. `actorFor` already answered null for them; `TicketService.patch`
	 * skipped `access.require` entirely on a null actor, so that null was a way past the
	 * check rather than a note about attribution.
	 *
	 * The link and its `PULL_REQUEST_LINKED` row are still written, and that is the half
	 * `V36`'s nameless sentence was always about: a reader can still follow the pull request
	 * from the ticket. Only the *move* is refused.
	 */
	@Test
	fun `a pull request from nobody Kanso knows links the ticket and moves nothing`() {
		deliver("pull_request", payload("pull_request_opened"))
		deliver("pull_request", payload("pull_request_merged"))

		assertEquals("todo", statusOf(), "neither delivery names a member Kanso may act as")
		assertEquals(0, movedTo("done").size, "and nothing moved it, with or without a name")
		assertEquals(
			1,
			activityOf(ActivityKind.PULL_REQUEST_LINKED).size,
			"the link is still drawn — following the pull request never needed an actor",
		)
	}

	/**
	 * `via_pr` on both rows, which is what the feed reads to say *why* a ticket moved.
	 *
	 * The sender is linked here because the assertion is about the **string**, and a delivery
	 * that moves nothing has no `status_changed` row to carry it. `#418` and not
	 * `tykok/kanso#418`: `project-copy.ts`'s `viaPr` prepends a `#` to anything that does not
	 * start with one, so the qualified form prints *via #tykok/kanso#418*. This producer and
	 * that consumer were built by different tickets and only meet here.
	 */
	@Test
	fun `both rows name the pull request the way the feed prints it`() {
		linkGithub("maintainer", 5150)
		deliver("pull_request", payload("pull_request_opened"))
		deliver("pull_request", payload("pull_request_merged"))

		assertEquals("done", statusOf(), "a merge finishes the ticket its branch named")

		val moved = movedTo("done").single()
		assertEquals(
			"#418",
			objectMapper.readTree(moved.payload).path("via_pr").asText(null),
			"the row says why it moved, which is what `via_pr` is for: ${moved.payload}",
		)
		assertEquals(
			"#418",
			objectMapper.readTree(activityOf(ActivityKind.PULL_REQUEST_LINKED).single().payload)
				.path("via_pr").asText(null),
			"and the link's own row names the pull request the same way",
		)
	}

	/**
	 * The other side of that, and what KAN-74's consent flow bought: a name.
	 *
	 * Linked against `maintainer` — the **sender** — rather than the author, because a merge
	 * is performed by whoever pressed the button and that is the case the design singles out.
	 */
	@Test
	fun `a merge by a member who consented is credited to them`() {
		accounts.link(
			userId = admin.id,
			githubUserId = 5150,
			githubLogin = "maintainer",
			token = GithubToken("gho-not-a-real-token", null, null),
		)

		deliver("pull_request", payload("pull_request_opened"))
		deliver("pull_request", payload("pull_request_merged"))

		assertEquals("done", statusOf())
		assertEquals(
			admin.id,
			movedTo("done").single().actorId,
			"the sender resolved through github_accounts, so the feed names them",
		)
	}

	/**
	 * Guard three, enforced in the `WHERE` clause of `ticketsClosedBy` rather than in Kotlin.
	 *
	 * The mention fixture's branch names nothing and its body reads *Unlike KAN-142, this one
	 * is small* — the exact sentence the design says must not close KAN-142. The link is
	 * drawn, because a reader following it is the point; it just cannot act.
	 */
	@Test
	fun `a bare mention links without closing and moves nothing`() {
		assertEquals(204, deliver("pull_request", payload("pull_request_mention")).status)

		val stored = assertNotNull(pullRequest(419))
		assertEquals(listOf(ticket.ticket.id), github.linkedTickets(stored.id), "the link exists, so a reader can follow it")
		assertEquals(emptyList(), github.ticketsClosedBy(stored.id), "and it is inert")
		assertEquals("todo", statusOf(), "so the ticket did not move")
	}

	/**
	 * **Guard two, and it is the only guard that needs a query to answer.**
	 *
	 * The merge fixture's `merged_at` is 2026-09-02. A person moves the ticket back to In
	 * progress *after* that instant, and the merge must not pull it forward again: the
	 * comparison is against the event's own timestamp rather than `now()`, so a delivery
	 * retried an hour later does not win a race it lost.
	 *
	 * Red without `lastHumanStatusChangeAt`: the ticket ends Done, over a decision somebody
	 * made by hand while the pull request sat open.
	 */
	@Test
	fun `a merge does not move a ticket a person moved after the event`() {
		deliver("pull_request", payload("pull_request_opened"))
		// By hand, and after the merge instant the fixture carries. `IN_PROGRESS` is *behind*
		// `IN_REVIEW`, which only a person is allowed to do.
		ticketService.patch(admin, ticket.ticket.id, TicketPatch(status = "in_progress"))

		deliver("pull_request", payload("pull_request_merged"))

		assertEquals("in_progress", statusOf(), "the person's decision stands")
		assertEquals(emptyList(), movedTo("done"), "and no row claims it reached done")
	}

	/**
	 * Guard one, on the merge that arrives after somebody already finished the ticket.
	 *
	 * Two claims in one, both from the design's fourth assertion: still Done, and **no
	 * duplicate activity** — a ticket does not reach Done twice.
	 */
	@Test
	fun `a merge on a ticket already done neither reverses nor repeats it`() {
		deliver("pull_request", payload("pull_request_opened"))
		ticketService.patch(admin, ticket.ticket.id, TicketPatch(status = "done"))
		val before = activityOf(ActivityKind.STATUS_CHANGED).size

		deliver("pull_request", payload("pull_request_merged"))

		assertEquals("done", statusOf())
		assertEquals(before, activityOf(ActivityKind.STATUS_CHANGED).size, "equal rank is not a move")
	}

	/**
	 * A review sets the pill, and **a comment does not clear it.**
	 *
	 * `PrReviewState.from` maps `commented`, `dismissed` and `pending` to null, and the null
	 * means *no change* rather than *no approval* — writing it would erase an approval every
	 * time somebody left a comment, which is the one way this column can lie.
	 */
	@Test
	fun `an approval is stored and a later comment does not clear it`() {
		linkGithub("elie", 4021)
		deliver("pull_request", payload("pull_request_opened"))
		deliver("pull_request_review", payload("pull_request_review"))

		assertEquals(PrReviewState.APPROVED, pullRequest()?.reviewState, "the approval is stored")

		val commented = payload("pull_request_review").replace("\"state\": \"approved\"", "\"state\": \"commented\"")
		deliver("pull_request_review", commented)

		assertEquals(PrReviewState.APPROVED, pullRequest()?.reviewState, "and a comment is not a withdrawal")
		assertEquals("in_review", statusOf(), "a review transitions nothing either way")
	}

	/**
	 * The installation row arrives from a `pull_request` payload, which is the only reason
	 * the foreign key on `github_pull_requests` can be satisfied at all: Kanso may have been
	 * installed before `V36` ran, so a `pull_request` delivery can be the first one that ever
	 * arrives. `V36` states it; the alternative presents as "GitHub events do nothing".
	 */
	@Test
	fun `the installation is remembered from a pull request payload, not only from its own event`() {
		deliver("pull_request", payload("pull_request_opened"))

		assertEquals(
			51_234_567L,
			pullRequest()?.installationId,
			"the row exists, so the installation it references does too",
		)
	}

	/**
	 * The compensation on the actorless write, asserted where it is cheapest to read.
	 *
	 * Not reachable from the webhook — nothing there builds a patch with a title in it — and
	 * that is the point: this is the refusal that keeps the *next* actorless caller from
	 * being handed every field on the ticket.
	 */
	@Test
	fun `a patch by nobody may only change the status`() {
		val refused = runCatching {
			ticketService.patch(null, ticket.ticket.id, TicketPatch(status = "done", title = "Renamed"))
		}.exceptionOrNull()

		assertNotNull(refused, "an actorless patch carrying a title is refused")
		assertTrue(
			refused.message?.contains("title") == true,
			"and the refusal names the field, so the next caller knows what to drop: ${refused.message}",
		)
		assertEquals("todo", statusOf(), "and nothing was written")

		// The permitted half, or the refusal above would be a removal dressed as a guard.
		ticketService.patch(null, ticket.ticket.id, TicketPatch(status = "in_progress"))
		assertEquals("in_progress", statusOf(), "the status alone still goes through")
		assertNull(
			activityOf(ActivityKind.STATUS_CHANGED).last().actorId,
			"recorded with nobody to credit, which is what `activity.actor_id` being nullable is for",
		)
	}

	/** An unparseable body that is correctly signed is dropped, not answered with a 500. */
	@Test
	fun `a signed delivery whose body is not JSON is dropped quietly`() {
		val body = "this is not json"
		val response = mvc.perform(
			MockMvcRequestBuilders.post(GITHUB_WEBHOOK)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)
				.header(GithubSignature.HEADER, GithubSignature.sign(body.toByteArray(), props.github.webhookSecret))
				.header(GithubSignature.DELIVERY_HEADER, UUID.randomUUID().toString())
				.header(GithubSignature.EVENT_HEADER, "pull_request"),
		).andReturn().response

		assertEquals(204, response.status, "GitHub retries a 5xx for three days; there is nothing to retry here")
		assertNull(pullRequest())
	}

	/**
	 * Unknown keys are not links, and they are certainly not refusals.
	 *
	 * `ARCH-12` in a body is somebody else's tracker. The parser is liberal on purpose and
	 * `GithubWebhookService.ticketFor` is where a candidate meets the real team list.
	 */
	@Test
	fun `a pull request naming another tracker is stored and links nothing`() {
		val body = payload("pull_request_opened")
			.replace(identifier, "ARCH-12")
			.replace(identifier.lowercase(), "arch-12")

		assertEquals(204, deliver("pull_request", body).status)

		val stored = assertNotNull(pullRequest(), "the pull request is still stored")
		assertEquals(emptyList(), github.linkedTickets(stored.id), "and it links nothing")
		assertEquals("todo", statusOf())
	}

	/**
	 * A sanity check on the fixture rewriting itself, because every assertion above depends
	 * on it: a fixture still carrying `KAN-142` would test a ticket nobody seeded, and most
	 * of this file would pass for the wrong reason.
	 */
	@Test
	fun `the fixtures name the ticket this test seeded`() {
		val body = payload("pull_request_opened")

		assertTrue(identifier in body, "the body's `Fixes` names it")
		assertTrue(identifier.lowercase() in body, "and the branch does too")
		assertTrue("KAN-142" !in body, "and nothing still names the design document's example")
		assertTrue(OffsetDateTime.now().isAfter(OffsetDateTime.parse("2026-09-02T14:03:11Z")), "the merge instant is in the past, which guard two's test depends on")
	}
}
