package dev.kanso.tokens

import dev.kanso.oauth.OAuthScopes
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A token as the settings screen lists it.
 *
 * There is no `secret` field, and [ApiToken] has no such property to map from — see that
 * type for why the plaintext is absent from the type system rather than merely absent from
 * this constructor call.
 *
 * `scopeProse` beside `scopes` for the reason `GrantSummary` gives about connected
 * applications: the scope strings are what a CLI's own configuration prints and what
 * somebody matches this row against, and the sentences are `OAuthScopes`'s own — rendered
 * here rather than in TypeScript so there is one copy of wording that also appears on the
 * OAuth consent screen.
 */
data class ApiTokenResponse(
	val id: UUID,
	val name: String,
	/** `kanso_pat_AbCdEf` — enough to recognise, not enough to use. */
	val prefix: String,
	val scopes: List<String>,
	val scopeProse: List<String>,
	/** Null means never used, which is a different thing from used long ago. */
	val lastUsedAt: OffsetDateTime?,
	val createdAt: OffsetDateTime,
) {
	companion object {
		fun of(token: ApiToken) = ApiTokenResponse(
			id = token.id,
			name = token.name,
			prefix = token.prefix,
			scopes = token.scopes.sorted(),
			scopeProse = token.scopes.sorted().map(OAuthScopes::prose),
			lastUsedAt = token.lastUsedAt,
			createdAt = token.createdAt,
		)
	}
}

/**
 * The response to a creation, and the only one that ever carries a secret.
 *
 * [warning] is prose in the payload, which is unusual for this API and deliberate. Every
 * other endpoint returns data and lets the client say what it means, but this response is
 * consumed by things that have no screen at all — `curl`, a script, an SDK printing a
 * JSON blob to a terminal — and the one fact a person needs at this exact moment is that
 * there is no second chance to copy it. A client with a UI is free to ignore the field and
 * write its own sentence; a client without one has it in hand.
 */
data class NewApiTokenResponse(
	val token: ApiTokenResponse,
	/** Shown once. Kanso keeps a SHA-256 digest and cannot show it again. */
	val secret: String,
	val warning: String,
) {
	companion object {
		const val SHOWN_ONCE =
			"Copy this token now. Kanso stores only a hash of it and can never show it again — " +
				"if you lose it, revoke this one and create another."

		fun of(created: NewApiToken) = NewApiTokenResponse(
			token = ApiTokenResponse.of(created.token),
			secret = created.secret,
			warning = SHOWN_ONCE,
		)
	}
}

/**
 * What a member asked for. `scopes` is not defaulted: a token whose permissions were
 * decided by a missing field is a token nobody chose, and `ApiTokenScopes.requested`
 * refuses the empty case with a sentence naming the two that exist.
 */
data class CreateApiTokenRequest(val name: String, val scopes: List<String>?)

/**
 * Under `/api/me`, beside preferences and favourites, because that is what a token is: a
 * fact about the person asking. There is no path here that names a user and there will not
 * be one — an admin does not read, and cannot revoke, somebody else's credentials, any
 * more than they can read their password.
 *
 * `/api/me/tokens` and not `/api/tokens`, so the resource path itself carries the scoping
 * that `ApiTokenService`'s argument-free signatures enforce.
 */
@RestController
@RequestMapping("/api/me/tokens")
class ApiTokenController(private val tokens: ApiTokenService) {

	@GetMapping
	fun list(): List<ApiTokenResponse> = tokens.list().map(ApiTokenResponse::of)

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@RequestBody request: CreateApiTokenRequest): NewApiTokenResponse =
		NewApiTokenResponse.of(tokens.create(request.name, request.scopes))

	/**
	 * The token stops working when this returns — `V27` argues why revocation is a delete
	 * and not a flag, and `ApiTokenLeakTest` holds it to that with a request made after
	 * this call.
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun revoke(@PathVariable id: UUID) {
		tokens.revoke(id)
	}
}
