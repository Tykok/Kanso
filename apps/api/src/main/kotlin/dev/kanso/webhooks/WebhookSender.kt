package dev.kanso.webhooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The far side answered something. What it answered is [status]'s business, not this type's.
 *
 * `WebhookHttpResponse` rather than the obvious `WebhookResponse`, which is taken: that name
 * belongs to the subscription DTO the settings screen receives. Two types called the same
 * thing in one package would be a compile error today and, worse, a coin flip in a reader's
 * head — one of these is somebody else's HTTP reply and the other is Kanso's own JSON.
 */
data class WebhookHttpResponse(val status: Int, val body: String)

/**
 * Nothing answered: a timeout, a refused connection, DNS, TLS. Distinct from a 500 because
 * `V31` keeps them distinct — `response_status` is null for these, and a configurator
 * chasing "the endpoint is not there" should not be reading HTTP semantics into it.
 */
class WebhookUnreachable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The one HTTP call this slice makes, behind an interface.
 *
 * The seam is the house rule and `GoogleCredentialProbeTest` states the reason: "the one
 * HTTP call is an interface here, answered by hand — the same seam `NotionClient` has, for
 * the same reason". A subscriber's endpoint is not reachable from a test and must not be;
 * a test that POSTs to somebody's server passes or fails on their uptime.
 *
 * It is deliberately dumb. No retry, no backoff, no rate limit, no signing — all four live
 * elsewhere (the first two in `OutboundWorker`, and they are not to be duplicated; the
 * third in `WebhookRateLimit`; the fourth in `WebhookSignature`). What is left is "POST
 * these bytes to this URL and tell me what came back", which is the only part that needs a
 * real socket and therefore the only part worth faking.
 */
interface WebhookSender {
	suspend fun post(url: String, body: String, headers: Map<String, String>): WebhookHttpResponse
}

/**
 * The real sender.
 *
 * @param timeout per request, and short — `KansoProperties.Webhooks.requestTimeout` argues
 *   why a receiver gets five seconds where Notion gets twenty, and what that bounds.
 */
class HttpWebhookSender(private val timeout: Duration) : WebhookSender {

	private val http: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		// **Redirects are not followed**, unlike `HttpNotionClient`, and the difference is
		// not an oversight. Notion's base URL is a constant in this repository; a webhook
		// URL is typed by a configurator, and following a redirect would let whoever
		// controls that host move a signed body to an address the CHECK in `V31` never
		// examined — including an http:// one, which is how a 307 launders the https rule.
		// A receiver that has moved should be re-registered at its new address.
		.followRedirects(HttpClient.Redirect.NEVER)
		.build()

	override suspend fun post(url: String, body: String, headers: Map<String, String>): WebhookHttpResponse =
		// The blocking send moved off the caller's thread, as `HttpNotionClient` does: the
		// drain is a scheduler thread and the pool `application.yml` sizes is shared with
		// the heartbeat, which must not be waiting behind somebody's slow endpoint.
		withContext(Dispatchers.IO) {
			val request = HttpRequest.newBuilder(URI.create(url))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.apply { headers.forEach { (name, value) -> header(name, value) } }
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build()

			val response = try {
				http.send(request, HttpResponse.BodyHandlers.ofString())
			} catch (e: Exception) {
				throw WebhookUnreachable(e.message ?: e.javaClass.simpleName, e)
			}

			// Truncated: the body is only ever read to put a sentence in the delivery log,
			// and a receiver that answers a 500 with a stack trace should not put a
			// megabyte of it in `webhook_deliveries.error`.
			WebhookHttpResponse(response.statusCode(), response.body().orEmpty().take(BODY_SNIPPET))
		}

	private companion object {
		const val BODY_SNIPPET = 500
	}
}
