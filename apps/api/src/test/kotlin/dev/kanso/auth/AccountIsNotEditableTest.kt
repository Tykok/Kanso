package dev.kanso.auth

import dev.kanso.MockMvcTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two facts about an account that nobody, including its holder, may write.
 *
 * **Its address.** It arrives with the invitation and stays: it is the sign-in, and it is
 * how an OIDC provider's account is matched to this one, so changing it is changing who
 * you are to two systems at once. There is no endpoint for it and this test is what keeps
 * it that way — the rule is easy to break by accident, because "let people fix a typo in
 * their email" is a reasonable-sounding request that quietly makes an account transferable.
 *
 * **Which Notion person it is.** That was a `PUT` until `KAN-54`, and it never checked the
 * id was unclaimed: anybody could point their row at a colleague's Notion identity and have
 * the mirror attribute that colleague's work to them. Refusing duplicates would have closed
 * the hole; removing the write closed the question. Who you are in a Notion workspace is a
 * fact about that workspace, decided on the import's matching screen, which is a
 * configurator's act — `NotionPeople.link`. An account holder reads it.
 *
 * Asserted against the mappings Spring actually registered rather than by reading the
 * controller, for the same reason `ReadOnlySeatLeakTest` enumerates: the route that comes
 * back is the one nobody thought to grep for.
 */
class AccountIsNotEditableTest : MockMvcTest() {

	// Qualified by name: the actuator contributes a second one, as `ReadOnlySeatLeakTest`
	// found before this.
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	lateinit var mappings: RequestMappingHandlerMapping

	private fun registered(): Set<String> = mappings.handlerMethods.keys.flatMap { info ->
		val paths = info.pathPatternsCondition?.patternValues.orEmpty()
		val methods = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("ANY") }
		methods.flatMap { method -> paths.map { "$method $it" } }
	}.toSet()

	@Test
	fun `nothing writes an account's email`() {
		val routes = registered()
		assertTrue(routes.isNotEmpty(), "an empty enumeration would make this a green light")

		// `PUT /api/me` is the display name, and its request carries nothing else — the
		// check that matters is that no route anywhere claims to write an address.
		val suspects = routes.filter { route ->
			route.substringBefore(' ') in setOf("PUT", "POST", "PATCH") &&
				route.contains("email", ignoreCase = true)
		}
		assertTrue(suspects.isEmpty(), "these would let an address be rewritten: $suspects")
	}

	@Test
	fun `the Notion identity is readable and not writable`() {
		val routes = registered()

		assertTrue(
			"GET /api/me/notion-identity" in routes,
			"the account screen has to be able to show which Notion person it is",
		)
		for (verb in listOf("PUT", "POST", "PATCH", "DELETE")) {
			assertTrue(
				"$verb /api/me/notion-identity" !in routes,
				"$verb came back: an account may read its Notion identity, never set it — " +
					"that is `NotionPeople.link`'s, and a self-service write let one account " +
					"claim another's",
			)
		}
	}
}
