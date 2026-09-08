package dev.kanso.api

import dev.kanso.PostgresTest
import dev.kanso.service.TicketFilterVocabulary
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.Test

/**
 * `GET /api/tickets/filters` — the vocabulary, published.
 *
 * It exists so that a client does not have to write the twelve names down a second time,
 * and the whole value of it is that what it publishes can actually be asked for. So the
 * test that matters is not "the endpoint returns the constant it reads" — that is a
 * sentence agreeing with itself — but the two below it.
 *
 * Its own web environment, for the reason [MeVersionTest] gives: [PostgresTest] runs with
 * `WebEnvironment.NONE` and has no MockMvc to drive. The container is the same started
 * singleton, so this costs a second Spring context and not a second Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServedFiltersTest {

	companion object {
		@JvmStatic
		@ServiceConnection
		val postgres: PostgreSQLContainer = PostgresTest.postgres
	}

	@Autowired
	lateinit var mvc: MockMvc

	/**
	 * The route, which is the one thing here that could silently not work.
	 *
	 * `/api/tickets/{id}` is declared beside it and takes a `UUID`, so if Spring preferred
	 * the template this would be a 400 reading `Failed to convert 'id' with value:
	 * 'filters'` — which is exactly what the endpoint answered before this mapping
	 * existed. A literal segment wins over a template, and that is worth an assertion
	 * rather than an assumption, because nothing else in the codebase relies on it.
	 */
	@Test
	fun `the literal path answers, not the id template beside it`() {
		mvc.get("/api/tickets/filters").andExpect {
			status { isOk() }
			jsonPath("$.served") { isArray() }
			jsonPath("$.served[0]") { value(TicketFilterVocabulary.SERVED.sorted().first()) }
		}
	}

	/**
	 * Every name published can be asked for — the promise the endpoint exists to make.
	 *
	 * A client that composes only from this list must never be refused, and the gate that
	 * could refuse it is `TicketFilterVocabulary.parseServed`, running on a different code
	 * path. Publishing a name the list endpoint then 400s on would be worse than
	 * publishing nothing: the client would draw a chip and the request behind it would
	 * fail, which is the exact failure the shared vocabulary was built to end.
	 *
	 * `unassigned` sent as the bare `?unassigned` a hand-written URL carries is deliberate
	 * too — it is one of the three spellings `flag` reads, and the value chosen here is
	 * the one a naive caller produces.
	 */
	@Test
	fun `every published name is one the list endpoint answers`() {
		for (name in TicketFilterVocabulary.SERVED) {
			mvc.get("/api/tickets") { param(name, valueFor(name)) }.andExpect {
				status { isOk() }
			}
		}
	}

	/**
	 * A value each facet's parser accepts, so that a 400 in the test above can only ever
	 * mean the *name* was refused. `status` and `priority` are closed vocabularies and the
	 * rest are ids, flags or whole numbers; a UUID that names nothing is a legitimate
	 * question with an empty answer, which is all this needs.
	 */
	private fun valueFor(name: String): String = when (name) {
		"status", "statusNot" -> "todo"
		// A category and not a status: `category` is the one facet whose vocabulary is the
		// five meanings — `KAN-90` — and this test exists to catch a name the endpoint
		// refuses, which is exactly what a UUID here looked like.
		"category" -> "started"
		"priority" -> "urgent"
		"unassigned", "unestimated" -> "true"
		"openedForDays", "estimateMin", "estimateMax" -> "3"
		else -> "00000000-0000-0000-0000-000000000000"
	}
}
