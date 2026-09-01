package dev.kanso.oauth

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** RFC 7591's error shape, which is not one of Kanso's three. */
data class RegistrationError(val error: String, val error_description: String)

/**
 * RFC 7591 dynamic client registration, at the library's default path.
 *
 * Ours rather than the library's, because `OAuth2ClientRegistrationAuthenticationProvider`
 * demands a single-use initial access token bearing scope `client.create` and no MCP
 * client presents one — `claude mcp add` speaks RFC 7591 unauthenticated or not at all.
 * The alternatives were an access token pasted from the Kanso UI, which reintroduces the
 * pasted credential this whole branch exists to remove, and one pre-registered client per
 * instance, which makes the one-command install depend on client behaviour Kanso does not
 * control.
 *
 * Validate, delegate, format — the rules are [ClientRegistrationService]'s and the
 * address policy is [RedirectUriPolicy]'s.
 */
@RestController
class ClientRegistrationController(
	private val registrations: ClientRegistrationService,
	private val rateLimit: RegistrationRateLimit,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@PostMapping("/connect/register")
	fun register(
		@RequestBody request: ClientRegistrationRequest,
		servletRequest: HttpServletRequest,
	): ResponseEntity<Any> {
		if (!rateLimit.allow(servletRequest.remoteAddr)) {
			log.warn("Rate-limited a client registration from {}", servletRequest.remoteAddr ?: "unknown")
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
				RegistrationError(
					"invalid_client_metadata",
					"Too many registrations from this address. Try again later.",
				),
			)
		}

		return try {
			ResponseEntity.status(HttpStatus.CREATED).body(registrations.register(request))
		} catch (refusal: ClientRegistrationRefused) {
			ResponseEntity.badRequest().body(RegistrationError(refusal.code, refusal.reason))
		}
	}
}
