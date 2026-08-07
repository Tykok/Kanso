package dev.kanso.auth

import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Reads the Kanso identity out of the security context, whatever produced it. */
@Component
class CurrentUser(private val users: UserRepository) {

	fun principalOrNull(): KansoAuthenticatedUser? =
		SecurityContextHolder.getContext().authentication?.principal as? KansoAuthenticatedUser

	fun idOrNull(): UUID? = principalOrNull()?.kansoUserId

	fun requireId(): UUID = idOrNull()
		?: throw AccessDeniedException("No authenticated Kanso user on this request")

	@Transactional(readOnly = true)
	fun require(): User {
		val id = requireId()
		return users.findById(id)
			?: throw AccessDeniedException("Authenticated user $id has no row in users")
	}
}
