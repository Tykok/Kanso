package dev.kanso.oauth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadata
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder
import org.springframework.security.web.DefaultRedirectStrategy
import org.springframework.security.web.RedirectStrategy
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.web.util.UriComponentsBuilder
import java.util.function.Consumer

/**
 * RFC 9207 — `iss` on every authorisation response, errors included.
 *
 * A **MUST** in the MCP specification, and one the library does not claim: SAS 7.1.0 has
 * no builder method for it, no name in `ConfigurationSettingNames`, no `iss` on the 302
 * and no `authorization_response_iss_parameter_supported` in its metadata. All four were
 * read out of the jar rather than inferred.
 *
 * What it buys is narrow and worth the file: a client that receives a code can tell
 * *which* server sent it. Without it, a code from a malicious authorisation server is
 * indistinguishable from a code from this one — the mix-up attack.
 *
 * The issuer is read from `AuthorizationServerContextHolder` per request, not from a
 * property, so an instance reached on a second hostname says the truth on both.
 */
private val currentIssuer: () -> String = { AuthorizationServerContextHolder.getContext().issuer }

/** Advertised, or no client knows to check what we now send. */
val ISS_PARAMETER_ADVERTISED: Consumer<OAuth2AuthorizationServerMetadata.Builder> =
	Consumer { it.claim("authorization_response_iss_parameter_supported", true) }

/** The granted case: the library's redirect, with `iss` on it. */
class IssuerAppendingSuccessHandler(
	private val issuer: () -> String = currentIssuer,
	private val redirects: RedirectStrategy = DefaultRedirectStrategy(),
) : AuthenticationSuccessHandler {

	override fun onAuthenticationSuccess(
		request: HttpServletRequest,
		response: HttpServletResponse,
		authentication: Authentication,
	) {
		val granted = authentication as OAuth2AuthorizationCodeRequestAuthenticationToken
		val redirect = UriComponentsBuilder.fromUriString(granted.redirectUri!!)
			.queryParam(OAuth2ParameterNames.CODE, granted.authorizationCode!!.tokenValue)
			.also { if (!granted.state.isNullOrBlank()) it.queryParam(OAuth2ParameterNames.STATE, granted.state) }
			.queryParam("iss", issuer())
			.build()
			.toUriString()
		redirects.sendRedirect(request, response, redirect)
	}
}

/**
 * The refused case, which the MUST covers just as much.
 *
 * With no usable `redirect_uri` the error is *answered*, not redirected: the library
 * rejects a bad redirect target before this runs, and sending an error to an address we
 * just refused is the open redirect the rule exists to prevent.
 */
class IssuerAppendingFailureHandler(
	private val issuer: () -> String = currentIssuer,
	private val redirects: RedirectStrategy = DefaultRedirectStrategy(),
) : AuthenticationFailureHandler {

	override fun onAuthenticationFailure(
		request: HttpServletRequest,
		response: HttpServletResponse,
		exception: AuthenticationException,
	) {
		val refusal = exception as? OAuth2AuthorizationCodeRequestAuthenticationException
		val token = refusal?.authorizationCodeRequestAuthentication
		val error = refusal?.error ?: OAuth2Error("server_error", null, null)

		val target = token?.redirectUri
		if (target.isNullOrBlank()) {
			response.status = HttpStatus.BAD_REQUEST.value()
			response.contentType = MediaType.APPLICATION_JSON_VALUE
			response.writer.write("""{"error":"${error.errorCode}"}""")
			return
		}

		val redirect = UriComponentsBuilder.fromUriString(target)
			.queryParam(OAuth2ParameterNames.ERROR, error.errorCode)
			.also { builder ->
				if (!error.description.isNullOrBlank()) {
					builder.queryParam(OAuth2ParameterNames.ERROR_DESCRIPTION, error.description)
				}
				if (!error.uri.isNullOrBlank()) builder.queryParam(OAuth2ParameterNames.ERROR_URI, error.uri)
				if (!token.state.isNullOrBlank()) builder.queryParam(OAuth2ParameterNames.STATE, token.state)
			}
			.queryParam("iss", issuer())
			.build()
			.toUriString()
		redirects.sendRedirect(request, response, redirect)
	}
}
