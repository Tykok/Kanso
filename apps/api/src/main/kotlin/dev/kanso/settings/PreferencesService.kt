package dev.kanso.settings

import dev.kanso.domain.Accent
import dev.kanso.domain.Density
import dev.kanso.domain.OpenTicket
import dev.kanso.domain.Preferences
import dev.kanso.domain.Theme
import dev.kanso.service.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Absent field means "leave unchanged". To clear [defaultTeamId], name it in
 * [unset]: JSON cannot tell an omitted key from an explicit null, and guessing
 * either way makes one of the two operations impossible.
 */
data class PreferencesPatch(
	val theme: String? = null,
	val accent: String? = null,
	val density: String? = null,
	val sidebarVisible: Boolean? = null,
	val showSyncBadges: Boolean? = null,
	val showStatusBar: Boolean? = null,
	val openTicket: String? = null,
	val defaultTeamId: UUID? = null,
	/** True stamps the moment the wizard was finished; false sends the user back through it. */
	val onboarded: Boolean? = null,
	/**
	 * Points per working day, declared. Clearable via [unset], because withdrawing a
	 * guess about yourself has to be possible and an omitted key cannot say it.
	 */
	val declaredVelocity: Double? = null,
	val unset: Set<String> = emptySet(),
) {

	fun applyTo(current: Preferences): Preferences {
		val unknown = unset - CLEARABLE
		if (unknown.isNotEmpty()) {
			throw BadRequestException(
				"Cannot unset ${unknown.joinToString()}; clearable fields are ${CLEARABLE.joinToString()}"
			)
		}
		return current.copy(
			theme = theme?.let { parse("theme", it, Theme::from) } ?: current.theme,
			accent = accent?.let { parse("accent", it, Accent::from) } ?: current.accent,
			density = density?.let { parse("density", it, Density::from) } ?: current.density,
			sidebarVisible = sidebarVisible ?: current.sidebarVisible,
			showSyncBadges = showSyncBadges ?: current.showSyncBadges,
			showStatusBar = showStatusBar ?: current.showStatusBar,
			openTicket = openTicket?.let { parse("openTicket", it, OpenTicket::from) } ?: current.openTicket,
			defaultTeamId = if ("defaultTeamId" in unset) null else defaultTeamId ?: current.defaultTeamId,
			onboardedAt = when (onboarded) {
				null -> current.onboardedAt
				// Re-finishing the wizard should not rewrite when it was first done.
				true -> current.onboardedAt ?: OffsetDateTime.now()
				false -> null
			},
			declaredVelocity = if ("declaredVelocity" in unset) {
				null
			} else {
				declaredVelocity?.also(::checkVelocity) ?: current.declaredVelocity
			},
		)
	}

	/**
	 * The same bargain [parse] strikes with the enums, for the same reason:
	 * `user_preferences_declared_velocity_chk` refuses these values anyway, and a
	 * constraint violation surfacing as a 500 hides a 400 the caller could have fixed.
	 *
	 * Zero is refused rather than treated as "withdraw it" — a rate of zero divides into
	 * an infinite duration, and the way to withdraw a declaration is to unset it.
	 */
	private fun checkVelocity(rate: Double) {
		if (rate <= 0 || rate > MAX_DECLARED) {
			throw BadRequestException(
				"declaredVelocity must be above 0 and at most $MAX_DECLARED points per working day," +
					" or unset; got $rate"
			)
		}
	}

	/**
	 * The enums refuse anything outside the vocabulary, and so does a CHECK
	 * constraint on the table. Catching here turns what would surface as a 500 from
	 * the database into the 400 it always was.
	 */
	private fun <T> parse(field: String, raw: String, parser: (String) -> T): T = try {
		parser(raw)
	} catch (e: IllegalArgumentException) {
		throw BadRequestException(e.message ?: "Invalid $field '$raw'")
	}

	private companion object {
		val CLEARABLE = setOf("defaultTeamId", "declaredVelocity")

		/** Mirrors the CHECK. See `V24` for why a ceiling exists at all. */
		const val MAX_DECLARED = 100.0
	}
}

@Service
@Transactional
class PreferencesService(private val repo: PreferencesRepository) {

	/**
	 * A missing row is the defaults, not an error: signing up should not have to
	 * write preferences nobody has expressed yet.
	 */
	@Transactional(readOnly = true)
	fun get(userId: UUID): Preferences = repo.find(userId) ?: Preferences()

	fun save(userId: UUID, patch: PreferencesPatch): Preferences {
		val merged = patch.applyTo(get(userId))
		repo.upsert(userId, merged)
		return merged
	}
}
