package dev.kanso.api

import dev.kanso.webhooks.WebhooksNotConfigured
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

/**
 * Which refusals reach the caller as themselves, and which are flattened into a 500.
 *
 * Standalone rather than a Spring context: what is under test is the *dispatch* — that the
 * advice has a handler more specific than its own `Exception` catch-all, and that Spring
 * therefore picks it. That question needs a handler mapping and nothing else, so this
 * costs neither a context nor a Postgres, and `MockMvcTest`'s docstring says why that
 * matters.
 *
 * The route-level tests cannot ask it at all: they call controller methods directly, which
 * is already past the advice.
 */
class ApiExceptionHandlerTest {

	@RestController
	class Throwing {
		@PostMapping("/boom/webhooks")
		fun webhooks(): Nothing = throw WebhooksNotConfigured()

		@PostMapping("/boom/unknown")
		fun unknown(): Nothing = throw IllegalStateException("something nobody mapped")
	}

	private val mvc =
		MockMvcBuilders.standaloneSetup(Throwing()).setControllerAdvice(ApiExceptionHandler()).build()

	/**
	 * The sentence the exception already carries, all the way out.
	 *
	 * `WebhooksNotConfigured` says exactly what an operator has to set, and until this
	 * mapping existed it fell to the catch-all: creating the first webhook on an instance
	 * with no signing key answered `500 Unexpected server error`, and the only place the
	 * reason appeared was the container's log. That is the first gesture of a third-party
	 * integration, refused with nothing to act on.
	 */
	@Test
	fun `an unconfigured webhook names the setting that is missing`() {
		mvc.perform(post("/boom/webhooks"))
			.andExpect(status().isServiceUnavailable)
			.andExpect(jsonPath("$.detail").value("Webhooks need kanso.webhooks.signing-key set to 32 base64 bytes"))
	}

	/**
	 * 503 and not 500, and not 400 either: nothing is wrong with the request, and the
	 * caller retrying it unchanged after somebody sets the key is exactly right.
	 */
	@Test
	fun `it is the instance that is unavailable, not the request that is wrong`() {
		mvc.perform(post("/boom/webhooks")).andExpect(status().isServiceUnavailable)
	}

	/** The catch-all still catches, and still says nothing it should not. */
	@Test
	fun `an unmapped failure stays a five hundred that leaks no internals`() {
		mvc.perform(post("/boom/unknown"))
			.andExpect(status().isInternalServerError)
			.andExpect(jsonPath("$.detail").value("Unexpected server error"))
	}

	private fun post(path: String) =
		org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post(path)
			.contentType(MediaType.APPLICATION_JSON)
}
