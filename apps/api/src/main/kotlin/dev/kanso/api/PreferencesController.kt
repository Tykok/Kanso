package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.Preferences
import dev.kanso.settings.PreferencesPatch
import dev.kanso.settings.PreferencesService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

data class PreferencesResponse(
	val theme: String,
	val accent: String,
	val density: String,
	val sidebarVisible: Boolean,
	val showSyncBadges: Boolean,
	val showStatusBar: Boolean,
	val openTicket: String,
	val defaultTeamId: UUID?,
	val onboardedAt: OffsetDateTime?,
	/**
	 * Points per working day, as declared. Null means never declared — the settings field
	 * renders empty for that, not as a 0 the person would then have to correct.
	 *
	 * Whether it is the number actually in force is a different question, and not one this
	 * response answers: it depends on a team's closed cycles. `/api/me/velocity` answers it.
	 */
	val declaredVelocity: Double?,
) {
	companion object {
		fun of(preferences: Preferences) = PreferencesResponse(
			theme = preferences.theme.wire,
			accent = preferences.accent.wire,
			density = preferences.density.wire,
			sidebarVisible = preferences.sidebarVisible,
			showSyncBadges = preferences.showSyncBadges,
			showStatusBar = preferences.showStatusBar,
			openTicket = preferences.openTicket.wire,
			defaultTeamId = preferences.defaultTeamId,
			onboardedAt = preferences.onboardedAt,
			declaredVelocity = preferences.declaredVelocity,
		)
	}
}

@RestController
@RequestMapping("/api/me/preferences")
class PreferencesController(
	private val currentUser: CurrentUser,
	private val preferences: PreferencesService,
) {

	@GetMapping
	fun get(): PreferencesResponse = PreferencesResponse.of(preferences.get(currentUser.requireId()))

	/**
	 * Partial: an absent field stays as it was, so the UI can save one toggle.
	 *
	 * Exempt from the read-only seat as `ReadOnlySeat.OWN_SCREEN`, which is a constraint on
	 * what may be added to [PreferencesPatch] rather than a fact about it: a viewer may set
	 * every field this body carries, so a field that decides something about *the work*
	 * would arrive already exempted, and does not belong here.
	 */
	@PutMapping
	fun update(@RequestBody patch: PreferencesPatch): PreferencesResponse =
		PreferencesResponse.of(preferences.save(currentUser.requireId(), patch))
}
