package dev.kanso.settings

import dev.kanso.PostgresTest
import dev.kanso.domain.Accent
import dev.kanso.domain.Density
import dev.kanso.domain.OpenTicket
import dev.kanso.domain.Preferences
import dev.kanso.domain.SidebarMode
import dev.kanso.domain.Theme
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
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
	@Autowired lateinit var jdbc: JdbcClient

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
		assertEquals(SidebarMode.PINNED, saved.sidebarMode, "an untouched field keeps its value")
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

	// --- the sidebar's three modes ------------------------------------------

	/**
	 * Pinned is the default because it is what every account already had: `V28` carried
	 * `sidebar_visible = true` over to it, so the deploy moves nobody's column.
	 */
	@Test
	fun `the sidebar has three modes, and the one that exists today is the default`() {
		val id = someone()

		assertEquals(SidebarMode.PINNED, preferences.get(id).sidebarMode)

		assertEquals(SidebarMode.HOVER, preferences.save(id, PreferencesPatch(sidebarMode = "hover")).sidebarMode)
		assertEquals(SidebarMode.HOVER, preferences.get(id).sidebarMode, "and it survives the row")
		assertEquals(SidebarMode.HIDDEN, preferences.save(id, PreferencesPatch(sidebarMode = "hidden")).sidebarMode)
	}

	@Test
	fun `a sidebar mode outside the vocabulary is a bad request, like every other enum here`() {
		val id = someone()

		assertFailsWith<BadRequestException> {
			preferences.save(id, PreferencesPatch(sidebarMode = "collapsed"))
		}
	}

	/**
	 * The half of the two-sided guard the enum cannot give us.
	 *
	 * A service can only write one of three words because [SidebarMode] has only three
	 * members, so this asserts the other writer — psql, a fixture, a future migration —
	 * is refused too. Last statement in the method on purpose: a failed statement leaves
	 * the transaction aborted, and the rollback this class runs anyway is all that is
	 * still owed.
	 */
	@Test
	fun `the column refuses a mode no enum member could have produced`() {
		val id = someone()

		assertFailsWith<DataIntegrityViolationException> {
			jdbc.sql("INSERT INTO user_preferences (user_id, sidebar_mode) VALUES (:id, 'collapsed')")
				.param("id", id)
				.update()
		}
	}

	/**
	 * `V28`'s `UPDATE … WHERE sidebar_visible = FALSE` is not observable from here — it
	 * ran before this container answered its first query, and the column it read is gone
	 * by the end of the same file, so no test that starts after Flyway can insert a row
	 * for it to convert. What is observable is that the drop happened: if the column came
	 * back, two settings would describe one sidebar and only one of them would be written.
	 */
	@Test
	fun `sidebar_visible is gone, not kept beside the mode it became`() {
		val remaining = jdbc
			.sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'user_preferences'")
			.query(String::class.java)
			.list()
			.filterNotNull()
			.toSet()

		assertTrue("sidebar_mode" in remaining)
		assertTrue("shortcuts" in remaining)
		assertTrue("show_view_controls" in remaining)
		assertTrue("sidebar_visible" !in remaining, "the boolean is replaced, not joined")
	}

	// --- remapped keys ------------------------------------------------------

	/**
	 * Empty, not a copy of the defaults: `V28` argues why at length. A stored copy would
	 * freeze today's key set into the account and a default improved later would never
	 * reach it.
	 */
	@Test
	fun `shortcuts start empty and a saved override replaces the document rather than merging`() {
		val id = someone()

		assertEquals(emptyMap(), preferences.get(id).shortcuts)

		val first = preferences.save(id, PreferencesPatch(shortcuts = mapOf("ticket.new" to listOf("Shift+n"))))
		assertEquals(mapOf("ticket.new" to listOf("Shift+n")), first.shortcuts)
		assertEquals(first.shortcuts, preferences.get(id).shortcuts, "and it round-trips through the jsonb")

		// A PUT of `shortcuts` is the whole document, which is what makes "reset
		// everything" expressible as `{}` rather than as a list of keys to forget.
		val second = preferences.save(id, PreferencesPatch(shortcuts = mapOf("app.back" to listOf("Escape"))))
		assertEquals(mapOf("app.back" to listOf("Escape")), second.shortcuts)
		assertEquals(emptyMap(), preferences.save(id, PreferencesPatch(shortcuts = emptyMap())).shortcuts)
	}

	/**
	 * Shape, and only shape. `ticket.pretend` is not an action and the server accepts it:
	 * the registry is a front-end module, so a server that refused unknown ids would need
	 * redeploying every time the web bundle renamed one, and `mergeBindings` already
	 * ignores what it does not recognise.
	 */
	@Test
	fun `an action id the server has never heard of is stored, because meaning is not its question`() {
		val id = someone()

		val saved = preferences.save(id, PreferencesPatch(shortcuts = mapOf("ticket.pretend" to listOf("q"))))

		assertEquals(mapOf("ticket.pretend" to listOf("q")), saved.shortcuts)
	}

	@Test
	fun `a shortcuts document outside the shape the column is for is a bad request`() {
		val id = someone()

		assertFailsWith<BadRequestException>("a blank action id addresses nothing") {
			preferences.save(id, PreferencesPatch(shortcuts = mapOf("  " to listOf("q"))))
		}
		assertFailsWith<BadRequestException>("a blank chord is a key nobody can press") {
			preferences.save(id, PreferencesPatch(shortcuts = mapOf("ticket.new" to listOf(""))))
		}
		assertFailsWith<BadRequestException>("a chord is a handful of characters, not a paragraph") {
			preferences.save(id, PreferencesPatch(shortcuts = mapOf("ticket.new" to listOf("x".repeat(41)))))
		}
		assertFailsWith<BadRequestException>("one action, a few spellings — not a thousand") {
			preferences.save(id, PreferencesPatch(shortcuts = mapOf("ticket.new" to List(9) { "F$it" })))
		}
		assertFailsWith<BadRequestException>("the registry is nowhere near this large") {
			preferences.save(
				id,
				PreferencesPatch(shortcuts = (1..201).associate { "action.$it" to listOf("q") }),
			)
		}

		assertEquals(emptyMap(), preferences.get(id).shortcuts, "and none of that was stored")
	}

	// --- the view controls --------------------------------------------------

	/**
	 * On by default: a control you have to find a setting to be shown is not a control,
	 * and the reader who does not want the three buttons is the one with the opinion.
	 */
	@Test
	fun `the three view controls are shown until somebody says otherwise`() {
		val id = someone()

		assertTrue(preferences.get(id).showViewControls)

		assertTrue(!preferences.save(id, PreferencesPatch(showViewControls = false)).showViewControls)
		assertTrue(!preferences.get(id).showViewControls, "and it survives the row")
	}
}
