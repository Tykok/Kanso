package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.settings.PreferencesService
import dev.kanso.auth.InvitationService
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.LocalAuthService
import dev.kanso.auth.TooManyLoginAttemptsException
import dev.kanso.config.KansoProperties
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class LoginRequest(
	@field:NotBlank val email: String,
	@field:NotBlank val password: String,
)

data class AcceptInvitationRequest(
	@field:NotBlank val token: String,
	@field:NotBlank val email: String,
	val displayName: String = "",
	@field:NotBlank val password: String,
)

data class ClaimOwnerRequest(
	@field:NotBlank val email: String,
	val displayName: String = "",
	@field:NotBlank val password: String,
)

data class InvitationRequest(val email: String? = null, val role: String? = null)

/** The link to copy. The token itself is never readable again after this response. */
data class InvitationResponse(val url: String, val expiresAt: OffsetDateTime)

/**
 * Password sign-in, invitations, and the link that creates the accounts after the
 * first one.
 */
@RestController
@RequestMapping("/api")
class LocalAuthController(
	private val localAuth: LocalAuthService,
	private val invitations: InvitationService,
	private val currentUser: CurrentUser,
	private val preferences: PreferencesService,
	private val props: KansoProperties,
	private val build: BuildProperties,
) {

	private val sessions = HttpSessionSecurityContextRepository()

	/**
	 * Claims a brand-new instance and signs the owner in.
	 *
	 * Public, because there is nobody to authenticate as yet, and safe to leave
	 * public afterwards: the second caller loses on the `users_single_owner` index
	 * rather than on a check this endpoint performs.
	 */
	@PostMapping("/setup/owner")
	fun claimOwner(
		@Valid @RequestBody request: ClaimOwnerRequest,
		http: HttpServletRequest,
		response: HttpServletResponse,
	): MeResponse {
		val user = localAuth.claimOwner(request.email, request.displayName, request.password)
		startSession(user, http, response)
		return MeResponse(
			user = UserResponse.of(user),
			teamIds = emptyList(),
			preferences = PreferencesResponse.of(preferences.get(user.id)),
			version = build.version ?: "unknown",
		)
	}

	@PostMapping("/auth/login")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun login(
		@Valid @RequestBody request: LoginRequest,
		http: HttpServletRequest,
		response: HttpServletResponse,
	) {
		val user = localAuth.authenticate(request.email, request.password, clientIp(http))
		startSession(user, http, response)
	}

	/**
	 * Answers in the shape of `/api/me` so the SPA can go straight to the app with
	 * what it already knows how to read. Team memberships are empty by definition:
	 * the account did not exist a moment ago.
	 */
	@PostMapping("/auth/accept-invitation")
	fun acceptInvitation(
		@Valid @RequestBody request: AcceptInvitationRequest,
		http: HttpServletRequest,
		response: HttpServletResponse,
	): MeResponse {
		val user = invitations.accept(request.token, request.email, request.displayName, request.password)
		startSession(user, http, response)
		return MeResponse(
			user = UserResponse.of(user),
			teamIds = emptyList(),
			preferences = PreferencesResponse.of(preferences.get(user.id)),
			version = build.version ?: "unknown",
		)
	}

	@PostMapping("/setup/invitations")
	fun invite(@RequestBody request: InvitationRequest): InvitationResponse {
		val me = currentUser.require()
		if (!me.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the instance owner or an admin can invite people")
		}
		val role = request.role?.takeIf { it.isNotBlank() }?.let(InstanceRole::from) ?: InstanceRole.MEMBER
		val (token, expiresAt) = invitations.create(me.id, request.email, role)
		return InvitationResponse("${props.webOrigin}/login?invite=$token", expiresAt)
	}

	/**
	 * Handled here rather than in [ApiExceptionHandler] because the rate limiter is
	 * this controller's concern alone. `Retry-After` as well as the body: it is what
	 * a proxy or a script would read, and the SPA wants to show a countdown.
	 */
	@ExceptionHandler(TooManyLoginAttemptsException::class)
	fun tooManyAttempts(e: TooManyLoginAttemptsException): ResponseEntity<ProblemDetail> {
		val seconds = e.retryAfter.seconds.coerceAtLeast(1)
		val problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.message ?: "Too many attempts")
		problem.setProperty("retryAfterSeconds", seconds)
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, seconds.toString())
			.body(problem)
	}

	/**
	 * Produces the same session an OIDC login produces, so there is one
	 * authenticated path downstream, WebSocket handshake included.
	 *
	 * Saving through the repository is the load-bearing line: since Spring Security
	 * 6 the context is no longer written back to the session at the end of the
	 * request, so assigning it to [SecurityContextHolder] alone would vanish with
	 * the response and the next call would be a 401.
	 */
	private fun startSession(user: User, http: HttpServletRequest, response: HttpServletResponse) {
		// A session id issued before sign-in must not become an authenticated one.
		http.getSession(false)?.invalidate()

		val principal = KansoLocalUser(user.id, user.email, user.displayName)
		val context = SecurityContextHolder.createEmptyContext().apply {
			authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
		}
		SecurityContextHolder.setContext(context)
		sessions.saveContext(context, http, response)

	}

	/**
	 * Behind a reverse proxy every request appears to come from the proxy, and one
	 * bucket would rate-limit the whole instance at once. The header is
	 * caller-supplied and therefore forgeable — which is why the per-address limit
	 * exists alongside it and does not depend on this.
	 */
	private fun clientIp(http: HttpServletRequest): String? =
		http.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
			?: http.remoteAddr
}
