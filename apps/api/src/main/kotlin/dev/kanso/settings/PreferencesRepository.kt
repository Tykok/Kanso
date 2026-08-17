package dev.kanso.settings

import dev.kanso.db.UserPreferences
import dev.kanso.domain.Accent
import dev.kanso.domain.Density
import dev.kanso.domain.OpenTicket
import dev.kanso.domain.Preferences
import dev.kanso.domain.Theme
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class PreferencesRepository {

	/** Null means the user never changed anything, not that the user is unknown. */
	fun find(userId: UUID): Preferences? =
		UserPreferences.selectAll().where { UserPreferences.userId eq userId }.singleOrNull()?.toPreferences()

	fun upsert(userId: UUID, preferences: Preferences) {
		UserPreferences.upsert(UserPreferences.userId) {
			it[UserPreferences.userId] = userId
			it[theme] = preferences.theme.wire
			it[accent] = preferences.accent.wire
			it[density] = preferences.density.wire
			it[sidebarVisible] = preferences.sidebarVisible
			it[showSyncBadges] = preferences.showSyncBadges
			it[showStatusBar] = preferences.showStatusBar
			it[openTicket] = preferences.openTicket.wire
			it[defaultTeamId] = preferences.defaultTeamId
			it[onboardedAt] = preferences.onboardedAt
			it[updatedAt] = OffsetDateTime.now()
		}
	}
}

private fun ResultRow.toPreferences() = Preferences(
	theme = Theme.from(this[UserPreferences.theme]),
	accent = Accent.from(this[UserPreferences.accent]),
	density = Density.from(this[UserPreferences.density]),
	sidebarVisible = this[UserPreferences.sidebarVisible],
	showSyncBadges = this[UserPreferences.showSyncBadges],
	showStatusBar = this[UserPreferences.showStatusBar],
	openTicket = OpenTicket.from(this[UserPreferences.openTicket]),
	defaultTeamId = this[UserPreferences.defaultTeamId],
	onboardedAt = this[UserPreferences.onboardedAt],
)
