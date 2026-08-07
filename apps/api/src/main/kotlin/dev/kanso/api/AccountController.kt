package dev.kanso.api

import dev.kanso.auth.AccountService
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.InvitationService
import dev.kanso.domain.InstanceRole
import dev.kanso.repo.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime
import java.util.UUID

data class RenameRequest(@field:NotBlank val displayName: String)

data class ChangePasswordRequest(
	@field:NotBlank val currentPassword: String,
	@field:NotBlank val newPassword: String,
)

data class RoleRequest(@field:NotBlank val role: String)

data class PendingInvitationResponse(
	val id: UUID,
	val email: String?,
	val role: String,
	val createdAt: OffsetDateTime,
	val expiresAt: OffsetDateTime,
	val expired: Boolean,
)

/**
 * What someone can change about their own account after the wizard is long past.
 */
@RestController
@RequestMapping("/api/me")
class AccountController(
	private val currentUser: CurrentUser,
	private val account: AccountService,
	private val users: UserRepository,
) {

	@PutMapping
	fun rename(@Valid @RequestBody request: RenameRequest): UserResponse =
		UserResponse.of(account.rename(currentUser.requireId(), request.displayName))

	@PutMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun changePassword(@Valid @RequestBody request: ChangePasswordRequest) =
		account.changePassword(currentUser.requireId(), request.currentPassword, request.newPassword)

	/**
	 * Binds this account to a Notion workspace member, which is what lets the mirror
	 * put them in a `people` property instead of a line of plain text.
	 */
	@PutMapping("/notion-identity")
	@Transactional
	fun setNotionIdentity(@RequestBody body: Map<String, String?>): UserResponse {
		val id = currentUser.requireId()
		users.setNotionPersonId(id, body["notionPersonId"]?.trim()?.takeIf { it.isNotEmpty() })
		return UserResponse.of(requireNotNull(users.findById(id)))
	}

	@DeleteMapping("/identities/{provider}")
	fun unlink(@PathVariable provider: String): UserResponse =
		UserResponse.of(account.unlinkProvider(currentUser.requireId(), provider))
}

/**
 * Who is on this instance and what they may do. Reading the list is open to anyone
 * signed in — you have to be able to assign a ticket to someone — but changing a
 * role or handing out an invitation is not.
 */
@RestController
@RequestMapping("/api/people")
class PeopleController(
	private val currentUser: CurrentUser,
	private val account: AccountService,
	private val invitations: InvitationService,
	private val users: UserRepository,
) {

	@GetMapping
	@Transactional(readOnly = true)
	fun list(): List<UserResponse> = users.findAll().map(UserResponse::of)

	@PutMapping("/{id}/role")
	fun setRole(@PathVariable id: UUID, @Valid @RequestBody request: RoleRequest): UserResponse =
		UserResponse.of(account.setInstanceRole(currentUser.require(), id, InstanceRole.from(request.role)))

	@GetMapping("/invitations")
	@Transactional(readOnly = true)
	fun pendingInvitations(): List<PendingInvitationResponse> {
		requireConfigurator()
		return invitations.pending().map {
			PendingInvitationResponse(it.id, it.email, it.role.wire, it.createdAt, it.expiresAt, it.expired)
		}
	}

	@DeleteMapping("/invitations/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun revokeInvitation(@PathVariable id: UUID) {
		requireConfigurator()
		if (!invitations.revoke(id)) {
			throw dev.kanso.service.NotFoundException("No pending invitation $id")
		}
	}

	private fun requireConfigurator() {
		if (!currentUser.require().instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can manage invitations")
		}
	}
}
