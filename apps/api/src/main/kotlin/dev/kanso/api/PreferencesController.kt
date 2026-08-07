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
	val defaultTeamId: UUID?,
	val onboardedAt: OffsetDateTime?,
) {
	companion object {
		fun of(preferences: Preferences) = PreferencesResponse(
			theme = preferences.theme.wire,
			accent = preferences.accent.wire,
			density = preferences.density.wire,
			sidebarVisible = preferences.sidebarVisible,
			showSyncBadges = preferences.showSyncBadges,
			showStatusBar = preferences.showStatusBar,
			defaultTeamId = preferences.defaultTeamId,
			onboardedAt = preferences.onboardedAt,
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

	/** Partial: an absent field stays as it was, so the UI can save one toggle. */
	@PutMapping
	fun update(@RequestBody patch: PreferencesPatch): PreferencesResponse =
		PreferencesResponse.of(preferences.save(currentUser.requireId(), patch))
}
