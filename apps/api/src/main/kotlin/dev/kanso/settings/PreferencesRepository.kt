package dev.kanso.settings

import dev.kanso.db.UserPreferences
import dev.kanso.domain.Accent
import dev.kanso.domain.Density
import dev.kanso.domain.OpenTicket
import dev.kanso.domain.Preferences
import dev.kanso.domain.SidebarMode
import dev.kanso.domain.Theme
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class PreferencesRepository(private val json: ObjectMapper) {

	/** Null means the user never changed anything, not that the user is unknown. */
	fun find(userId: UUID): Preferences? =
		UserPreferences.selectAll().where { UserPreferences.userId eq userId }
			.singleOrNull()
			?.toPreferences(json)

	fun upsert(userId: UUID, preferences: Preferences) {
		UserPreferences.upsert(UserPreferences.userId) {
			it[UserPreferences.userId] = userId
			it[theme] = preferences.theme.wire
			it[accent] = preferences.accent.wire
			it[density] = preferences.density.wire
			it[sidebarMode] = preferences.sidebarMode.wire
			it[showSyncBadges] = preferences.showSyncBadges
			it[showStatusBar] = preferences.showStatusBar
			it[showViewControls] = preferences.showViewControls
			it[openTicket] = preferences.openTicket.wire
			// The whole override document, not a merge into the stored one. "Reset
			// everything" is a `{}` the client sends, and a merge here would make it
			// unexpressible: no request would mean "forget what I chose".
			it[shortcuts] = json.writeValueAsString(preferences.shortcuts)
			it[defaultTeamId] = preferences.defaultTeamId
			it[onboardedAt] = preferences.onboardedAt
			// The only place a BigDecimal exists. `NUMERIC(5, 2)` is what the column is
			// and `Double` is what every rate above here is, so the conversion happens
			// once, at the edge, rather than leaking either type into the other's half.
			it[declaredVelocity] = preferences.declaredVelocity?.let { rate -> BigDecimal.valueOf(rate) }
			it[updatedAt] = OffsetDateTime.now()
		}
	}
}

private fun ResultRow.toPreferences(json: ObjectMapper) = Preferences(
	theme = Theme.from(this[UserPreferences.theme]),
	accent = Accent.from(this[UserPreferences.accent]),
	density = Density.from(this[UserPreferences.density]),
	sidebarMode = SidebarMode.from(this[UserPreferences.sidebarMode]),
	showSyncBadges = this[UserPreferences.showSyncBadges],
	showStatusBar = this[UserPreferences.showStatusBar],
	showViewControls = this[UserPreferences.showViewControls],
	openTicket = OpenTicket.from(this[UserPreferences.openTicket]),
	shortcuts = decodeShortcuts(json, this[UserPreferences.shortcuts]),
	defaultTeamId = this[UserPreferences.defaultTeamId],
	onboardedAt = this[UserPreferences.onboardedAt],
	declaredVelocity = this[UserPreferences.declaredVelocity]?.toDouble(),
)

/**
 * The jsonb document as the map it was written from.
 *
 * The cast is unchecked because Jackson erases to `Map<*, *>`, and the document is
 * deliberately *not* re-validated on the way out: [PreferencesPatch] is the only writer
 * and checks the shape on the way in, the column defaults to `{}`, and anything that is
 * not a json object throws inside Jackson before reaching the cast. The same bargain
 * `DocBlockRepository` strikes with `doc_blocks.content`.
 *
 * What the erasure leaves open — a hand-edited row whose chords are numbers rather than
 * strings — costs nothing here: the value serialises straight back out, and the client's
 * `mergeBindings` already ignores anything it cannot read as a chord. Rewriting every
 * entry on every preferences read would buy a guarantee only the front end can make.
 */
@Suppress("UNCHECKED_CAST")
private fun decodeShortcuts(json: ObjectMapper, raw: String): Map<String, List<String>> =
	json.readValue(raw, Map::class.java) as Map<String, List<String>>
