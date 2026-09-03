package dev.kanso.sync.inbound

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.RequestBaseRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Registering a requests base: the moment every fact the siphon cannot work out for itself
 * is supplied by a person, and therefore the only moment those facts can be got wrong.
 */
@Transactional
class RequestBaseServiceTest : PostgresTest() {

	@Autowired lateinit var bases: RequestBaseService
	@Autowired lateinit var rows: RequestBaseRepository
	@Autowired lateinit var meta: NotionMetaRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "reqbase-${UUID.randomUUID()}@kanso.test",
			displayName = "Requests admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Requests", "Q${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun other() =
		teams.create(admin, "Other", "O${UUID.randomUUID().toString().take(4).uppercase()}", null)

	@Test
	fun `a base is registered against the team whose queue it feeds`() {
		val id = "ds-${UUID.randomUUID()}"

		val base = bases.register(id, "db-$id", team.id)

		assertEquals(team.id, base.teamId)
		assertEquals(team.id, rows.find(id)?.teamId, "and it is the row the poller reads")
	}

	/**
	 * The mistake worth refusing at registration rather than defending against per poll.
	 *
	 * `NotionDiscovery` keeps the one-shot import out of Kanso's own databases because
	 * "offering to import `Kanso · Tickets` back into Kanso would duplicate every ticket in
	 * the instance". A siphon would do it on a timer, forever, each pass adopting what the
	 * last one pushed.
	 */
	@Test
	fun `the mirror's own databases cannot be registered as a requests base`() {
		val mirrored = "ds-tickets-${UUID.randomUUID()}"
		meta.save("tickets", "db-$mirrored", mirrored, null)

		val bySource = assertFailsWith<BadRequestException> {
			bases.register(mirrored, "db-something-else", team.id)
		}
		assertTrue(bySource.message!!.contains("mirror"), "it says why: ${bySource.message}")

		assertFailsWith<BadRequestException>("and by the database id too, which is the other half of the pair") {
			bases.register("ds-something-else", "db-$mirrored", team.id)
		}
		assertEquals(emptyList(), rows.findAll().filter { it.dataSourceId == mirrored })
	}

	@Test
	fun `a base cannot name a team that does not exist`() {
		val id = "ds-${UUID.randomUUID()}"

		assertFailsWith<BadRequestException> { bases.register(id, "db-$id", UUID.randomUUID()) }

		assertEquals(null, rows.find(id), "nothing is registered, so no poll files against a dangling id")
	}

	@Test
	fun `re-registering re-points the base at another team instead of adding a second row`() {
		val id = "ds-${UUID.randomUUID()}"
		bases.register(id, "db-$id", team.id)
		val second = other()

		bases.register(id, "db-$id", second.id)

		assertEquals(1, rows.findAll().count { it.dataSourceId == id }, "one base, one team")
		assertEquals(second.id, rows.find(id)?.teamId)
	}

	@Test
	fun `unregistering stops the siphon and leaves the tickets it already filed`() {
		val id = "ds-${UUID.randomUUID()}"
		bases.register(id, "db-$id", team.id)

		bases.unregister(id)

		assertEquals(null, rows.find(id), "the poller has nothing to walk")
	}

	/**
	 * The other end of the same decision. `TicketService.create` takes a nullable actor for
	 * the siphon's sake, and a null actor with no team would write the dead letter box `V37`
	 * refuses: a draft owned by nobody, visible to instance admins only, in no queue and
	 * comparable to nothing.
	 */
	@Test
	fun `a ticket created by nobody must name the team whose queue it joins`() {
		val refused = assertFailsWith<BadRequestException> {
			tickets.create(
				actor = null,
				teamId = null,
				title = "A request from nowhere",
				description = null,
				status = TicketStatus.TODO,
				priority = TicketPriority.NONE,
				start = null,
				due = null,
				projectId = null,
				assigneeIds = emptyList(),
				docIds = emptyList(),
			)
		}
		assertTrue(refused.message!!.contains("team"), "it says which fact is missing: ${refused.message}")
	}
}
