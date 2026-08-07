package dev.kanso.auth

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component

/**
 * Provisioning happens during the login exchange rather than on first API call,
 * so by the time a session exists the user row exists too.
 */
@Component
class KansoOidcUserService(private val provisioning: UserProvisioning) : OidcUserService() {

	override fun loadUser(userRequest: OidcUserRequest): OidcUser {
		val oidcUser = super.loadUser(userRequest)
		val email = oidcUser.email
			?: throw OAuth2AuthenticationException(
				OAuth2Error("email_required"),
				"The provider returned no email address; Kanso identifies users by email."
			)
		val subject = oidcUser.subject
			?: throw OAuth2AuthenticationException(OAuth2Error("subject_required"), "No 'sub' claim in id token")
		val user = provisioning.provision(
			provider = userRequest.clientRegistration.registrationId,
			subject = subject,
			email = email,
			displayName = oidcUser.fullName ?: oidcUser.preferredUsername ?: email,
			avatarUrl = oidcUser.picture,
		)
		return KansoOidcUser(oidcUser, user.id, user.email)
	}
}

/**
 * GitHub is OAuth2 without an id token, and it hides the email when the user has
 * marked it private. Rather than fail the login, we fall back to GitHub's own
 * no-reply address: stable, unique, and mergeable later if the real address ever
 * becomes visible.
 */
@Component
class KansoOAuth2UserService(
	private val provisioning: UserProvisioning,
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	private val delegate = DefaultOAuth2UserService()

	override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
		val oauthUser = delegate.loadUser(userRequest)
		val attributes = oauthUser.attributes
		val registrationId = userRequest.clientRegistration.registrationId
		val subject = attributes["id"]?.toString()
			?: throw OAuth2AuthenticationException(OAuth2Error("subject_required"), "No user id in provider response")
		val login = attributes["login"]?.toString()
		val email = attributes["email"]?.toString()
			?: login?.let { "$it@users.noreply.github.com" }
			?: throw OAuth2AuthenticationException(
				OAuth2Error("email_required"),
				"No email and no login in provider response"
			)

		val user = provisioning.provision(
			provider = registrationId,
			subject = subject,
			email = email,
			displayName = attributes["name"]?.toString() ?: login ?: email,
			avatarUrl = attributes["avatar_url"]?.toString(),
		)
		return KansoOAuth2User(oauthUser, user.id, user.email)
	}
}
