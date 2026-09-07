package dev.kanso.domain

import dev.kanso.service.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The guard against a team holding both `In Progress` and `in progress` — `KAN-28`.
 *
 * A key derived from the label rather than typed beside it is what makes two spellings of
 * one word the same row: `UNIQUE (team_id, key)` then refuses the second, and no screen
 * has a rule to enforce. Tested here because it is pure and because it is the whole of
 * that guarantee.
 */
class TeamStatusKeyTest {

	@Test
	fun `two spellings of one word are one key`() {
		assertEquals("in_progress", statusKeyOf("In Progress"))
		assertEquals("in_progress", statusKeyOf("in progress"))
		assertEquals("in_progress", statusKeyOf("IN  PROGRESS"))
		assertEquals("in_progress", statusKeyOf("  in-progress  "))
	}

	@Test
	fun `accents fold, because two keyboards are not two statuses`() {
		assertEquals("livre", statusKeyOf("Livré"))
		assertEquals("en_cours", statusKeyOf("En cours"))
		assertEquals("a_faire", statusKeyOf("À faire"))
	}

	@Test
	fun `a label with no letter or digit has no key`() {
		assertEquals(
			"A status needs a letter or a digit in its name",
			assertFailsWith<BadRequestException> { statusKeyOf("…") }.message,
		)
	}

	@Test
	fun `a digit is a letter's equal, so a numbered stage keeps its number`() {
		assertEquals("etape_2", statusKeyOf("Étape 2"))
	}
}
