package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.auth.DynamicClientRegistrationRepository
import dev.kanso.auth.LocalAuthService
import dev.kanso.config.KansoProperties
import dev.kanso.settings.PreferencesService
import dev.kanso.repo.DocRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import jakarta.validation.Valid
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/users")
class UserController(private val users: UserRepository) {

	@GetMapping
	@Transactional(readOnly = true)
	fun list(): List<UserResponse> = users.findAll().map(UserResponse::of)

	/**
	 * Binding a Kanso user to their Notion workspace identity. Without it the
	 * mirror cannot fill the `people` property for that person and falls back to
	 * plain text.
	 */
	@PutMapping("/{id}/notion-person")
	@Transactional
	fun setNotionPerson(@PathVariable id: UUID, @RequestBody body: Map<String, String?>): UserResponse {
		users.setNotionPersonId(id, body["notionPersonId"]?.takeIf { it.isNotBlank() })
		return UserResponse.of(requireNotNull(users.findById(id)))
	}
}

@RestController
@RequestMapping("/api/docs")
class DocController(private val docs: DocRepository) {

	@GetMapping
	@Transactional(readOnly = true)
	fun list(): List<DocResponse> = docs.findAll().map(DocResponse::of)

	/**
	 * Docs are Notion pages Kanso references but never authors, so registering one
	 * is an upsert on the Notion page id rather than a create.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	fun register(@Valid @RequestBody request: DocRequest): DocResponse =
		DocResponse.of(docs.upsert(request.notionPageId, request.title, request.url))
}

@RestController
@RequestMapping("/api")
class AuthController(
	private val props: KansoProperties,
	private val currentUser: CurrentUser,
	private val teams: TeamRepository,
	private val registrations: DynamicClientRegistrationRepository,
	private val localAuth: LocalAuthService,
	private val preferences: PreferencesService,
	private val build: BuildProperties,
) {

	/**
	 * Lets the sign-in screen render only the buttons that will actually work.
	 *
	 * Read from the live registration repository rather than from the environment:
	 * the setup wizard can add Google at runtime, and a provider the user just
	 * configured has to appear without a restart.
	 */
	@GetMapping("/auth/mode")
	@Transactional(readOnly = true)
	fun mode(): AuthModeResponse = AuthModeResponse(
		mode = props.auth.effectiveMode,
		passwordLoginEnabled = localAuth.passwordLoginEnabled(),
		providers = registrations.current().map {
			AuthProvider(
				id = it.registrationId,
				label = it.clientName ?: it.registrationId,
				authorizeUrl = "/oauth2/authorization/${it.registrationId}",
			)
		},
	)

	@GetMapping("/auth/providers")
	fun providers(): List<AuthProvider> = mode().providers

	@GetMapping("/me")
	@Transactional(readOnly = true)
	fun me(): MeResponse {
		val user = currentUser.require()
		return MeResponse(
			user = UserResponse.of(user),
			teamIds = teams.teamIdsFor(user.id),
			preferences = PreferencesResponse.of(preferences.get(user.id)),
			// BuildProperties.getVersion() is @Nullable: only absent if buildInfo()
			// never ran, which MeVersionTest catches at the bean level.
			version = build.version ?: "unknown",
		)
	}
}
