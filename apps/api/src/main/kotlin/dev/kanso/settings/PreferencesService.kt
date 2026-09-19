package dev.kanso.settings

import dev.kanso.domain.Accent
import dev.kanso.domain.Density
import dev.kanso.domain.OpenTicket
import dev.kanso.domain.Preferences
import dev.kanso.domain.SidebarMode
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
	val sidebarMode: String? = null,
	val showSyncBadges: Boolean? = null,
	val showStatusBar: Boolean? = null,
	val showViewControls: Boolean? = null,
	val openTicket: String? = null,
	/**
	 * The complete override document, replacing whatever was stored — not a merge.
	 *
	 * Absent still means "leave unchanged", like every other field here; an empty map is
	 * the settings page's `Reset everything`, which a merge would have no way to express.
	 */
	val shortcuts: Map<String, List<String>>? = null,
	val defaultTeamId: UUID? = null,
	/**
	 * True stamps the moment `/setup`'s account screen was passed. False clears the
	 * stamp — nothing today sends one, but `PreferencesPatch` is a generic PUT body and
	 * nothing stops a caller from trying — though `/setup` now has nothing left to send a
	 * claimed, signed-in account back through even if it arrived.
	 */
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
			sidebarMode = sidebarMode?.let { parse("sidebarMode", it, SidebarMode::from) } ?: current.sidebarMode,
			showSyncBadges = showSyncBadges ?: current.showSyncBadges,
			showStatusBar = showStatusBar ?: current.showStatusBar,
			showViewControls = showViewControls ?: current.showViewControls,
			openTicket = openTicket?.let { parse("openTicket", it, OpenTicket::from) } ?: current.openTicket,
			shortcuts = shortcuts?.also(::checkShortcuts) ?: current.shortcuts,
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
	 * Shape, and only shape — which is the whole of what this side can honestly check.
	 *
	 * The keys are action ids from the front end's `lib/actions/`, so the server has no
	 * way to know whether `ticket.rename` exists: refusing an unknown id would mean
	 * redeploying the API every time the web bundle renamed one, and would turn a
	 * front-end refactor into a wall of 400s. `mergeBindings` on the client is what
	 * decides meaning, and it ignores what it does not recognise, so an id this method
	 * waves through is a row that stays readable rather than a keyboard that misbehaves.
	 *
	 * What is left is worth checking anyway, because a jsonb column with no CHECK is the
	 * one place in this schema where a client could store an unbounded document. Every
	 * bound below is far above anything the registry could produce and far below anything
	 * that costs a read: they exist to keep the column a set of bindings rather than a
	 * blob store, not to second-guess a capture UI.
	 */
	private fun checkShortcuts(bindings: Map<String, List<String>>) {
		if (bindings.size > MAX_ACTIONS) {
			throw BadRequestException("shortcuts may name at most $MAX_ACTIONS actions; got ${bindings.size}")
		}
		bindings.forEach { (action, chords) ->
			if (action.isBlank()) throw BadRequestException("A shortcuts key must name an action")
			if (action.length > MAX_ACTION_LENGTH) {
				throw BadRequestException(
					"Action id '${action.take(MAX_ACTION_LENGTH)}…' is longer than $MAX_ACTION_LENGTH characters"
				)
			}
			if (chords.size > MAX_CHORDS) {
				throw BadRequestException("'$action' may have at most $MAX_CHORDS chords; got ${chords.size}")
			}
			chords.forEach { chord ->
				// Blank is refused rather than dropped: a chord nobody can press is a
				// binding somebody meant to make, and silently discarding it would leave
				// the settings table showing a key the dispatcher never received.
				if (chord.isBlank()) throw BadRequestException("'$action' has a blank chord")
				if (chord.length > MAX_CHORD_LENGTH) {
					throw BadRequestException(
						"'$action' has a chord longer than $MAX_CHORD_LENGTH characters"
					)
				}
			}
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

		/** The registry is around fifty actions; two hundred leaves it room to double twice. */
		const val MAX_ACTIONS = 200

		/** `organise.selectRange` is the longest id today, at nineteen characters. */
		const val MAX_ACTION_LENGTH = 100

		/** One intention, one or two spellings — `n` and `↓`. Eight is generous. */
		const val MAX_CHORDS = 8

		/** `"Shift+ArrowDown"` is fifteen. Forty is a modifier stack nobody can hold down. */
		const val MAX_CHORD_LENGTH = 40
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
