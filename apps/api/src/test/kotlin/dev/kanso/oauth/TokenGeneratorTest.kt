package dev.kanso.oauth

import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import org.springframework.test.util.ReflectionTestUtils
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The one bean that takes the library's default generator apart and puts it back.
 *
 * Declaring an `OAuth2TokenGenerator` bean at all switches `OAuth2ConfigurerUtils` off:
 * it builds its default only when no bean of that type exists. Everything that default
 * did, this bean now has to do — and the piece easiest to lose is the one nothing calls
 * today, because losing it fails nothing. `OAuth2TokenCustomizer` is how claims are added
 * to a token in this library, and it is the obvious next thing this branch's successor
 * declares; a customizer bean that is silently ignored is a claim missing from a token,
 * debugged from the wrong end.
 *
 * No Spring context: the bean method is a function of its arguments, and
 * [OidcChainWiringTest] owns whether the bean is the one the container hands out.
 */
class TokenGeneratorTest {

	private val config = AuthorizationServerConfig()

	/** Never encodes anything here; only its presence decides whether a JWT half exists. */
	private val encoder = JwtEncoder { throw UnsupportedOperationException("not encoded in this test") }

	private val accessTokenCustomizer = OAuth2TokenCustomizer<OAuth2TokenClaimsContext> { }
	private val jwtCustomizer = OAuth2TokenCustomizer<JwtEncodingContext> { }

	/**
	 * Every method on `ObjectProvider` has a default, so a stub is one override. Spring 7
	 * hands the real one to the bean; what it answers is the only thing this bean reads.
	 */
	private fun <T : Any> provided(value: T?) = object : ObjectProvider<T> {
		override fun getIfAvailable(): T? = value
	}

	private fun generate(
		jwt: JwtEncoder? = encoder,
		access: OAuth2TokenCustomizer<OAuth2TokenClaimsContext>? = null,
		claims: OAuth2TokenCustomizer<JwtEncodingContext>? = null,
	): List<*> {
		val generator = config.tokenGenerator(provided(jwt), provided(access), provided(claims))
		return ReflectionTestUtils.getField(
			generator as DelegatingOAuth2TokenGenerator,
			"tokenGenerators",
		) as List<*>
	}

	private fun customizerOn(delegate: Any?, field: String) = ReflectionTestUtils.getField(delegate!!, field)

	@Test
	fun `the three delegates the library would have built are all there`() {
		val delegates = generate()

		assertTrue(delegates[0] is JwtGenerator, "dropping the JWT half takes self-contained tokens with it")
		assertTrue(delegates[1] is OAuth2AccessTokenGenerator, "the opaque access token is what `/api/mcp` reads")
		assertTrue(
			delegates[2] is PublicClientRefreshTokenGenerator,
			"the library's own refresh delegate returns null for a public client, which is why this one exists",
		)
	}

	/**
	 * The claim in this bean's KDoc, held to: one delegate replaced, and the wiring around
	 * the other two carried rather than dropped. Both setters are what
	 * `OAuth2ConfigurerUtils.getTokenGenerator` and `getJwtGenerator` call, and constructing
	 * the two generators bare skips both.
	 */
	@Test
	fun `a customizer bean reaches the generator it customises`() {
		val delegates = generate(access = accessTokenCustomizer, claims = jwtCustomizer)

		assertSame(
			jwtCustomizer,
			customizerOn(delegates[0], "jwtCustomizer"),
			"a JwtEncodingContext customizer nobody wired is a claim silently missing from every JWT",
		)
		assertSame(
			accessTokenCustomizer,
			customizerOn(delegates[1], "accessTokenCustomizer"),
			"and an OAuth2TokenClaimsContext one is the same silence on the opaque token",
		)
	}

	/** With no such bean the generators keep the library's own defaults, which is null. */
	@Test
	fun `with no customizer declared nothing is set, so the defaults still stand`() {
		val delegates = generate()

		assertNull(customizerOn(delegates[0], "jwtCustomizer"), "a null customizer is the library's own state here")
		assertNull(customizerOn(delegates[1], "accessTokenCustomizer"), "and the same on the access token")
	}

	/**
	 * Boot autoconfigures a `JwtEncoder` from the JWKS this server publishes, so in the
	 * running application the branch above is the one taken. This is the other one: no
	 * encoder, no JWT delegate, and the two that do not need one still built.
	 */
	@Test
	fun `with no JwtEncoder the list is the two that do not need one`() {
		val delegates = generate(jwt = null, access = accessTokenCustomizer)

		assertTrue(delegates.size == 2, "a JwtGenerator with no encoder is a constructor that throws at startup")
		assertSame(
			accessTokenCustomizer,
			customizerOn(delegates[0], "accessTokenCustomizer"),
			"the access token's customizer is wired on this branch too, or one of the two branches is a trap",
		)
	}
}
