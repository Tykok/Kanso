package dev.kanso.auth

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.OAuth2User
import java.io.Serializable
import java.security.Principal
import java.util.UUID

/**
 * Whatever the login path, the rest of the application only needs the Kanso user
 * id. Every principal type carries it so nothing downstream has to know whether
 * the session came from Google, GitHub or dev mode.
 */
interface KansoAuthenticatedUser {
	val kansoUserId: UUID
	val kansoEmail: String
}

/** Google (and any OIDC provider): wraps the provider's user, adds our id. */
class KansoOidcUser(
	private val delegate: OidcUser,
	override val kansoUserId: UUID,
	override val kansoEmail: String,
) : OidcUser by delegate, KansoAuthenticatedUser {
	override fun getName(): String = delegate.name
	override fun getAttributes(): Map<String, Any> = delegate.attributes
	override fun getAuthorities(): Collection<GrantedAuthority> = delegate.authorities
	override fun getClaims(): Map<String, Any> = delegate.claims
	override fun getUserInfo(): OidcUserInfo? = delegate.userInfo
	override fun getIdToken(): OidcIdToken = delegate.idToken
}

/** GitHub is plain OAuth2, not OIDC, so it needs its own wrapper. */
class KansoOAuth2User(
	private val delegate: OAuth2User,
	override val kansoUserId: UUID,
	override val kansoEmail: String,
) : OAuth2User by delegate, KansoAuthenticatedUser {
	override fun getName(): String = delegate.name
	override fun getAttributes(): Map<String, Any> = delegate.attributes
	override fun getAuthorities(): Collection<GrantedAuthority> = delegate.authorities
}

/**
 * Email and password. Serializable because, unlike the others, this principal is
 * only ever put in the session by hand — and the session is where it has to
 * survive.
 */
class KansoLocalUser(
	override val kansoUserId: UUID,
	override val kansoEmail: String,
	private val displayName: String,
) : Principal, KansoAuthenticatedUser, Serializable {
	override fun getName(): String = displayName

	val authorities: Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_USER"))
}

/** Dev mode only: identity asserted by a header, nothing verified. */
class KansoDevUser(
	override val kansoUserId: UUID,
	override val kansoEmail: String,
	private val displayName: String,
) : Principal, KansoAuthenticatedUser {
	override fun getName(): String = displayName

	val authorities: Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_USER"))
}

/**
 * A member, as reached through a token their agent holds.
 *
 * It implements the same interface the other four do, and that is the entire point of
 * this class: `CurrentUser` reads `kansoUserId` off whatever is in the security context,
 * so every service downstream sees a `User` and `TicketAccess` applies unchanged. An
 * agent is not a new kind of user — it is a member with a different way in.
 *
 * What it adds is provenance: the client that presented the token, so the activity feed
 * can say which application typed a change, and the scopes, so a writing tool can refuse
 * a read-only grant before it does anything.
 */
class KansoAgentUser(
	override val kansoUserId: UUID,
	override val kansoEmail: String,
	private val displayName: String,
	val clientId: String,
	val scopes: Set<String>,
) : Principal, KansoAuthenticatedUser {
	override fun getName(): String = displayName

	val authorities: Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_USER"))
}

/**
 * A member, as reached through a token *they* created — not through an application they
 * authorised.
 *
 * Its own class rather than a reuse of [KansoAgentUser], and the field it does not have is
 * the reason: an agent user carries a `clientId`, because an OAuth grant was given to a
 * registered application and the activity feed can name it. An API token was made by the
 * member for themselves, and there is no third party to name. Widening [KansoAgentUser]
 * with a nullable `clientId` would push "sometimes there is no application" into every
 * reader of `activity.via_client_id`, which is a question that column has already
 * answered.
 *
 * What the two share is the part that matters, and it is the same argument
 * [KansoAgentUser] makes: this implements [KansoAuthenticatedUser], so [CurrentUser] reads
 * `kansoUserId` off it exactly as it does off a cookie's principal, and every service,
 * every `TicketAccess` check and `ReadOnlySeatInterceptor` apply unchanged. A token is not
 * a new kind of user with a permission system of its own — it is a member with a different
 * way in, and it can no more exceed their rights than their browser can.
 *
 * [scopes] narrows it *further*, never wider: `ApiTokenScopes` can refuse a write the
 * owner would have been allowed, and there is no scope that permits one they would not.
 */
class KansoTokenUser(
	override val kansoUserId: UUID,
	override val kansoEmail: String,
	private val displayName: String,
	val tokenId: UUID,
	val scopes: Set<String>,
) : Principal, KansoAuthenticatedUser {
	override fun getName(): String = displayName

	val authorities: Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_USER"))
}
