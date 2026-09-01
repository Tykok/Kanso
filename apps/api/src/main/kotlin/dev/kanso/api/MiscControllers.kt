package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.auth.DynamicClientRegistrationRepository
import dev.kanso.auth.LocalAuthService
import dev.kanso.config.KansoProperties
import dev.kanso.settings.PreferencesService
import dev.kanso.repo.DocRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.NotFoundException
import dev.kanso.sync.notion.NotionPeople
import jakarta.validation.Valid
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/users")
class UserController(
	private val users: UserRepository,
	private val people: NotionPeople,
	private val currentUser: CurrentUser,
) {

	@GetMapping
	@Transactional(readOnly = true)
	fun list(): List<UserResponse> = users.findAll().map(UserResponse::of)

	/**
	 * Binding a Kanso user to their Notion workspace identity. Without it the
	 * mirror cannot fill the `people` property for that person and falls back to
	 * plain text.
	 *
	 * Through [NotionPeople.link] rather than straight at the column, and that indirection
	 * is the whole of it: `link` is where "only an owner or an admin decides whose work a
	 * Notion edit lands on" is written, and it says of itself that nothing reaches
	 * [UserRepository.setNotionPersonId] without passing it first. This route was the
	 * counter-example — no actor, no check, a user id straight off the path — so any
	 * signed-in member could rewrite a colleague's Notion identity and have the mirror
	 * attribute that colleague's work to somebody else. One row is the batch of one `link`
	 * already speaks, so there was no second rule to write, and the one-to-one
	 * correspondence it keeps between Notion people and Kanso accounts now holds through
	 * this door too rather than being quietly breakable through it.
	 */
	@PutMapping("/{id}/notion-person")
	@Transactional
	fun setNotionPerson(@PathVariable id: UUID, @RequestBody body: Map<String, String?>): UserResponse {
		val target = users.findById(id) ?: throw NotFoundException("No user $id")
		val asked = body["notionPersonId"]?.trim()?.takeIf { it.isNotEmpty() }
		// `link` is keyed by the *Notion* id, so a clear has to be said as "the id this
		// account is holding now belongs to nobody"; an account holding none has nothing to
		// say, and asks for nothing — the same no-op `link` already lets through unguarded.
		val assignment = asked?.let { it to id } ?: target.notionPersonId?.let { it to null }
		assignment?.let { people.link(currentUser.require(), mapOf(it)) }
		return UserResponse.of(requireNotNull(users.findById(id)))
	}
}

@RestController
@RequestMapping("/api/docs")
class DocController(private val docs: DocRepository, private val currentUser: CurrentUser) {

	@GetMapping
	@Transactional(readOnly = true)
	fun list(): List<DocResponse> = docs.findAll().map(DocResponse::of)

	/**
	 * Docs are Notion pages Kanso references but never authors, so registering one
	 * is an upsert on the Notion page id rather than a create.
	 *
	 * Owner or admin, and by analogy rather than by precedent — this is the one guard in
	 * the sweep that reasons from a family instead of copying a rule already applied to
	 * the same kind of row. The family is every other write that decides *which Notion
	 * content this instance is bound to*: the token, the parent page, and the people
	 * mapping are all `canConfigureInstance`, and a `notion_docs` row is a binding of
	 * exactly that kind. It had none at all, and because it is an upsert keyed on the
	 * Notion page id, any signed-in member could repoint the title and URL of a page every
	 * ticket in the instance links to — which is a link a colleague clicks.
	 *
	 * Not `TicketAccess`: a doc has no team, so there is nothing for the team rule to
	 * answer about, and a rule that cannot be asked is not the rule.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	fun register(@Valid @RequestBody request: DocRequest): DocResponse {
		if (!currentUser.require().instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can register a Notion page with this instance")
		}
		return DocResponse.of(docs.upsert(request.notionPageId, request.title, request.url))
	}
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
