package dev.kanso.github

import dev.kanso.settings.InstanceSettingsService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * One spelling of the path, shared by the mapping below and by `SecurityConfig`, following
 * `CONSENT_PAGE`. Two spellings of a route that has to be opened in the filter chain is one
 * rename away from an endpoint that is either unreachable or authenticated — and this is the
 * route where that mistake is worth the most.
 */
const val GITHUB_WEBHOOK: String = "/api/github/webhook"

/**
 * `POST /api/github/webhook` — unauthenticated, and it writes rows.
 *
 * One HMAC stands between this endpoint and a stranger moving somebody's tickets, which is
 * why the design names it as the first test written and why this file does exactly three
 * things: it takes the bytes, it proves them, and it hands them on. Every decision about
 * what a delivery *means* is in `GithubWebhookService`, and every decision about what a
 * signature means is in `GithubSignature`.
 *
 * **It holds no `CurrentUser`, and it is on `UnguardedWriteTest.ACTORLESS` for it.** There is
 * no actor to name: the caller is GitHub, it has no session by construction, and the
 * identity that matters — whose merge this was — is resolved from the payload against
 * `github_accounts` one layer in. A `CurrentUser` field here would be an unused dependency
 * whose only effect is to satisfy a sweep, which that file already says is the wrong
 * direction to move a guard in.
 *
 * Separate from `GithubLinkController` rather than a method on it, sharing only the path
 * prefix. That controller is a member's own consent flow and holds a `CurrentUser`; putting
 * an unauthenticated write on the same class would put the one route with no actor inside
 * the one class that has one.
 */
@RestController
class GithubWebhookController(
	private val settings: InstanceSettingsService,
	private val webhooks: GithubWebhookService,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * **`ByteArray`, and there is no overload that takes anything else.**
	 *
	 * `X-Hub-Signature-256` is an HMAC of the *raw* body. A body Jackson has parsed and
	 * re-serialised has different whitespace and a different key order, so the digest will
	 * not match — and the symptom is "GitHub is sending bad signatures", which sends the
	 * next person to look at GitHub. `GithubSignature` states the trap; this is the half of
	 * it that has to be true at the edge.
	 *
	 * Answers `204` and never a body. There is no reader: GitHub records the status code on
	 * the delivery and a person reads it on a page Kanso does not draw. A body would only be
	 * an oracle telling a stranger which payloads got somewhere — which is also why a
	 * refused signature, an unparseable payload and a redelivery are indistinguishable from
	 * outside, differing only in the log.
	 */
	@PostMapping(GITHUB_WEBHOOK)
	fun receive(
		@RequestBody(required = false) body: ByteArray?,
		@RequestHeader(name = GithubSignature.HEADER, required = false) signature: String?,
		@RequestHeader(name = GithubSignature.DELIVERY_HEADER, required = false) delivery: String?,
		@RequestHeader(name = GithubSignature.EVENT_HEADER, required = false) event: String?,
	): ResponseEntity<Void> {
		val bytes = body ?: ByteArray(0)
		// Read per request rather than injected once: the secret arrives from a settings
		// screen or from the environment and can change while the application is up, and a
		// value captured at construction would keep refusing every delivery after a rotation.
		// `InstanceSettingsService` caches, so this is not a query per delivery.
		val secret = settings.resolved().githubWebhookSecret
		if (secret.isNullOrBlank()) {
			// Not a 500, and not a 401 either as far as the log is concerned: nobody has
			// connected GitHub, so a delivery arriving here is a misconfiguration on the
			// sending side or a stranger. The answer on the wire is the same 401 a bad
			// signature gets, because telling an unauthenticated caller *why* it failed is
			// the one thing this endpoint must never do.
			log.warn("A GitHub delivery arrived but this instance has no webhook secret")
			return unauthorized()
		}
		// **Nothing is parsed before this passes.** Not the body, not the delivery id — the
		// signature is the only guard on the endpoint, so it runs first and on the bytes as
		// they arrived.
		if (!GithubSignature.verify(bytes, secret, signature)) {
			log.warn("A GitHub delivery failed signature verification and was refused")
			return unauthorized()
		}
		// A UUID, because `github_deliveries.delivery_id` is one: `V36` chose the type so a
		// malformed header is refused by it rather than inserted as a row that can never
		// collide with anything, which would quietly turn every retry into a fresh delivery.
		// Refused after the signature and not before, so an attacker learns nothing from the
		// difference.
		val deliveryId = delivery?.let { runCatching { UUID.fromString(it) }.getOrNull() }
		if (deliveryId == null || event.isNullOrBlank()) {
			log.warn("A signed GitHub delivery carried no usable {} or {}", GithubSignature.DELIVERY_HEADER, GithubSignature.EVENT_HEADER)
			return ResponseEntity.badRequest().build()
		}
		webhooks.receive(event, deliveryId, bytes)
		return ResponseEntity.noContent().build()
	}

	/** No body, ever. See [receive]. */
	private fun unauthorized(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
}
