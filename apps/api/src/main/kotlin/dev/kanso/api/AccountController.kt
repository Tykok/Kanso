package dev.kanso.api

import dev.kanso.auth.AccountService
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.InvitationService
import dev.kanso.domain.InstanceRole
import dev.kanso.repo.UserRepository
import dev.kanso.sync.notion.MyNotionIdentity
import dev.kanso.sync.notion.NotionPeople
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
	private val people: NotionPeople,
) {

	@PutMapping
	fun rename(@Valid @RequestBody request: RenameRequest): UserResponse =
		UserResponse.of(account.rename(currentUser.requireId(), request.displayName))

	@PutMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun changePassword(@Valid @RequestBody request: ChangePasswordRequest) =
		account.changePassword(currentUser.requireId(), request.currentPassword, request.newPassword)

	/**
	 * Which Notion person this account is, to look at and not to set.
	 *
	 * It used to be a `PUT`, and the hole that made was the mirror's: nothing checked that
	 * the id was unclaimed, so anybody could point their own row at a colleague's Notion
	 * identity and have the mirror attribute that colleague's work to them. Refusing the
	 * duplicate would have closed it, but the honest fix was to notice that this is not a
	 * field about you. Who you are in a Notion workspace is a fact about that workspace,
	 * and `NotionPeople.link` — the import's matching screen, a configurator's act — is
	 * where it is decided. An account holder reads the answer.
	 *
	 * When Notion is not connected there is no answer to read, and `connected = false` is
	 * what lets the screen omit the section rather than draw an empty one: a field that
	 * can never be filled teaches somebody to go looking for the button that fills it.
	 *
	 * The profile comes from the workspace rather than from Kanso's own row, so a name or
	 * an address changed in Notion reads correctly here without anything being synced.
	 */
	@GetMapping("/notion-identity")
	@Transactional(readOnly = true)
	fun notionIdentity(): MyNotionIdentity = people.mine(currentUser.require())

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
