package dev.kanso.domain

import dev.kanso.service.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The table both derivations of a status key are pinned against — `KAN-90`.
 *
 * `apps/web/src/lib/status-key.test.ts` asserts the same pairs in TypeScript, and the
 * agreement is the point: the statuses screen refuses a duplicate label before the
 * request, so a client that derived the key differently would refuse a word this server
 * accepts, or accept one it refuses. The copy that drifts fails a test on the side that
 * drifted.
 *
 * The two shapes differ at the bottom and deliberately: a label with nothing nameable in
 * it throws here, because the server is refusing a request, and answers `null` there,
 * because the screen is deciding whether to mark a field invalid.
 */
class StatusKeyTest {

	private val table = listOf(
		"Backlog" to "backlog",
		"In Progress" to "in_progress",
		"in progress" to "in_progress",
		"IN  PROGRESS" to "in_progress",
		// The case the Notion mirror cares about: a French team's words are keys an ASCII
		// column holds.
		"Livré" to "livre",
		"Qualifié" to "qualifie",
		"En chantier" to "en_chantier",
		// Punctuation folds to one separator and the ends are trimmed, rather than left as
		// underscores — `_devis_` would collide with any other such label.
		"Devis / estimation" to "devis_estimation",
		"  Devis  " to "devis",
		"…Devis…" to "devis",
		"A/B" to "a_b",
		// Digits survive, so `Phase 2` is a status somebody can name.
		"Phase 2" to "phase_2",
	)

	@Test
	fun `every label in the table derives the key both sides agree on`() {
		for ((label, key) in table) {
			assertEquals(key, statusKeyOf(label), "the key of \"$label\"")
		}
	}

	@Test
	fun `two spellings of one word are one key, which is what the primary key relies on`() {
		assertEquals(statusKeyOf("En cours"), statusKeyOf("en-cours"))
		assertEquals(statusKeyOf("Livré"), statusKeyOf("livre"))
	}

	@Test
	fun `a label with nothing nameable in it is refused rather than stored`() {
		for (label in listOf("…", "///", "")) {
			assertEquals(
				"A status needs a letter or a digit in its name",
				assertFailsWith<BadRequestException> { statusKeyOf(label) }.message,
				"the refusal for \"$label\"",
			)
		}
	}
}
