package dev.kanso.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import jakarta.servlet.http.HttpSessionEvent
import jakarta.servlet.http.HttpSessionListener
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Which live sessions belong to which user, so a password change can end the others.
 *
 * Spring Security's own `SessionRegistry` was tried first and is not enough here:
 * `expireNow()` only sets a flag that `ConcurrentSessionFilter` turns into a
 * rejection, and that filter is only in the chain when concurrency control is
 * configured. A session marked expired but still answering 200 is worse than no
 * feature at all — this holds the sessions themselves and invalidates them, which
 * takes effect on the next request whatever the chain looks like.
 *
 * In memory, so it covers the sessions held by this instance. That is exact for the
 * single-instance deployment the compose file ships; behind several replicas the
 * others survive until they expire on their own, which is worth knowing before
 * scaling out.
 */
@Component
class UserSessions : HttpSessionListener {

	private val log = LoggerFactory.getLogger(javaClass)
	private val sessionsById = ConcurrentHashMap<String, HttpSession>()
	private val byUser = ConcurrentHashMap<UUID, MutableSet<String>>()

	override fun sessionCreated(event: HttpSessionEvent) {
		sessionsById[event.session.id] = event.session
	}

	override fun sessionDestroyed(event: HttpSessionEvent) {
		forget(event.session.id)
	}

	fun bind(userId: UUID, session: HttpSession) {
		sessionsById[session.id] = session
		byUser.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session.id)
	}

	/** Returns how many were ended. [keep] is the session asking, which stays. */
	fun invalidateOthers(userId: UUID, keep: String?): Int {
		val ids = byUser[userId]?.toList().orEmpty()
		var ended = 0
		for (id in ids) {
			if (id == keep) continue
			val session = sessionsById[id] ?: continue
			// Already invalid: the container may have collected it between the two lines.
			runCatching { session.invalidate() }.onSuccess { ended++ }
			forget(id)
		}
		if (ended > 0) log.info("Ended {} other session(s) for user {}", ended, userId)
		return ended
	}

	private fun forget(sessionId: String) {
		sessionsById.remove(sessionId)
		byUser.values.forEach { it.remove(sessionId) }
	}
}

/**
 * Binds whatever session the current request is using to its user.
 *
 * Doing it here rather than only at sign-in covers every way in — the password
 * form, Google, and the dev header — without each of them having to remember.
 */
@Component
class UserSessionBindingFilter(private val sessions: UserSessions) : OncePerRequestFilter() {

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		filterChain.doFilter(request, response)

		val principal = SecurityContextHolder.getContext().authentication?.principal
		if (principal is KansoAuthenticatedUser) {
			request.getSession(false)?.let { sessions.bind(principal.kansoUserId, it) }
		}
	}
}

@Configuration
class SessionListenerConfig {

	/**
	 * A bare `HttpSessionListener` bean is not picked up by the embedded container;
	 * it has to be registered explicitly.
	 */
	@Bean
	fun userSessionListener(sessions: UserSessions): ServletListenerRegistrationBean<UserSessions> =
		ServletListenerRegistrationBean(sessions)
}
