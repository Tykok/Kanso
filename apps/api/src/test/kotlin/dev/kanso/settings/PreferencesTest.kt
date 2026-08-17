package dev.kanso.settings

import dev.kanso.PostgresTest
import dev.kanso.domain.Accent
import dev.kanso.domain.Density
import dev.kanso.domain.OpenTicket
import dev.kanso.domain.Preferences
import dev.kanso.domain.Theme
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class PreferencesTest : PostgresTest() {

	@Autowired lateinit var preferences: PreferencesService
	@Autowired lateinit var users: UserRepository

	private fun someone(): UUID =
		users.insert("prefs-${UUID.randomUUID()}@kanso.dev", "Preference Haver").id

	@Test
	fun `someone who never set a preference gets the defaults`() {
		val id = someone()

		assertEquals(
			Preferences(),
			preferences.get(id),
			"signing up should not have to write a row nobody asked for",
		)
	}

	@Test
	fun `a patch changes one field and leaves the rest alone`() {
		val id = someone()
		preferences.save(id, PreferencesPatch(theme = "dark", density = "compact"))

		val saved = preferences.save(id, PreferencesPatch(accent = "rose"))

		assertEquals(Theme.DARK, saved.theme)
		assertEquals(Density.COMPACT, saved.density)
		assertEquals(Accent.ROSE, saved.accent)
		assertTrue(saved.sidebarVisible, "an untouched field keeps its value")
		assertEquals(saved, preferences.get(id), "and it round-trips through the row")
	}

	@Test
	fun `an accent outside the vocabulary is a bad request, not a 500 from the check constraint`() {
		val id = someone()

		assertFailsWith<BadRequestException> {
			preferences.save(id, PreferencesPatch(accent = "chartreuse"))
		}
	}

	@Test
	fun `how enter opens a ticket is a preference, and it round-trips`() {
		val id = someone()

		assertEquals(OpenTicket.PANEL, preferences.get(id).openTicket, "the panel is the default")

		val saved = preferences.save(id, PreferencesPatch(openTicket = "page"))

		assertEquals(OpenTicket.PAGE, saved.openTicket)
		assertEquals(OpenTicket.PAGE, preferences.get(id).openTicket, "and it survives the row")
		assertFailsWith<BadRequestException> { preferences.save(id, PreferencesPatch(openTicket = "modal")) }
	}

	@Test
	fun `clearing the default team needs the field named, because null alone means nothing`() {
		val id = someone()
		val onboarded = preferences.save(id, PreferencesPatch(onboarded = true))
		assertTrue(onboarded.onboardedAt != null)

		// An absent defaultTeamId is "unchanged"; only `unset` clears it.
		assertNull(preferences.save(id, PreferencesPatch(unset = setOf("defaultTeamId"))).defaultTeamId)

		assertFailsWith<BadRequestException> {
			preferences.save(id, PreferencesPatch(unset = setOf("theme")))
		}
	}
}
