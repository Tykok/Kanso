package dev.kanso.api

import dev.kanso.MockMvcTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test

/**
 * `GET /api/tickets/grouped` — that the path resolves at all.
 *
 * `/api/tickets/{id}` is declared beside it and takes a `UUID`, so if Spring preferred
 * the template this would be a 400 reading `Failed to convert 'id' with value: 'grouped'`
 * rather than a list of buckets. A literal segment wins over a template; `/filters` next
 * door relies on the same rule and `ServedFiltersTest` says why it is worth asserting
 * rather than assuming.
 *
 * Everything the route then *does* is `GroupedListTest`, which drives the controller
 * directly and can build the rows to group.
 */
class GroupedRouteTest : MockMvcTest() {

	@Autowired
	lateinit var mvc: MockMvc

	@Test
	fun `the literal path answers, not the id template beside it`() {
		mvc.get("/api/tickets/grouped").andExpect {
			status { isOk() }
			jsonPath("$.groupBy") { value("status") }
			jsonPath("$.groups") { isArray() }
		}
	}

	/** The gate is the same one, on this door too. */
	@Test
	fun `refuses a filter nobody serves, as the flat list does`() {
		mvc.get("/api/tickets/grouped") { param("labelColour", "indigo") }.andExpect {
			status { isBadRequest() }
		}
	}
}
