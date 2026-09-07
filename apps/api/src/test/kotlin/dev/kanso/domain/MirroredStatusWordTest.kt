package dev.kanso.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The word the Notion mirror writes, and how one read back is resolved — `KAN-91`.
 *
 * The mirror has one tickets database for the whole instance, so its `Status` select
 * carries every word every team uses. That is only safe because a page belongs to a
 * ticket and a ticket belongs to a team: the label is resolved against *that team's*
 * catalogue, never against a union. Two teams renaming two different keys to the same
 * word — `todo` to "En cours" here, `in_progress` to "En cours" there — is the case that
 * makes a union unusable, and the case these tests are about.
 */
class MirroredStatusWordTest {

	private fun catalogue(vararg pairs: Pair<String, String>): List<TeamStatus> =
		pairs.mapIndexed { index, (key, label) ->
			TeamStatus(java.util.UUID.randomUUID(), key, label, DefaultStatus.from(key).category, index)
		}

	private val support = catalogue(
		"backlog" to "Boîte",
		"todo" to "En cours",
		"in_progress" to "Sur le feu",
		"in_review" to "Attente client",
		"done" to "Résolu",
		"canceled" to "Sans suite",
	)

	private val engineering = catalogue(
		"backlog" to "Backlog",
		"todo" to "Todo",
		"in_progress" to "En cours",
		"in_review" to "In review",
		"done" to "Done",
		"canceled" to "Canceled",
	)

	@Test
	fun `the word written to a page is the team's`() {
		assertEquals("Résolu", mirroredWord(DefaultStatus.DONE, support))
		assertEquals("Done", mirroredWord(DefaultStatus.DONE, engineering))
	}

	@Test
	fun `a ticket with no catalogue to ask writes Kanso's word`() {
		// A draft is never pushed, but a job queued by some other path must write something
		// a reader recognises rather than a key.
		assertEquals("Done", mirroredWord(DefaultStatus.DONE, emptyList()))
	}

	@Test
	fun `one word means two statuses, and the team decides which`() {
		// The whole reason resolution is per team. A union would have to pick one.
		assertEquals(DefaultStatus.TODO, statusFromWord("En cours", support))
		assertEquals(DefaultStatus.IN_PROGRESS, statusFromWord("En cours", engineering))
	}

	@Test
	fun `case and surrounding space are not a different word`() {
		assertEquals(DefaultStatus.DONE, statusFromWord("  résolu ", support))
	}

	@Test
	fun `Kanso's own word still reads, for a team that renamed nothing`() {
		// The mirror's select was seeded with the six, and a page edited before a rename
		// still holds one of them.
		assertEquals(DefaultStatus.DONE, statusFromWord("Done", support))
	}

	@Test
	fun `a word nobody uses resolves to nothing, and the caller logs it`() {
		// `NotionPoller.unknown` is what happens next: logged and dropped, with the
		// corrective push putting Kanso's own value back on the page.
		assertNull(statusFromWord("Shipped", support))
	}

	@Test
	fun `the team's word wins over Kanso's when they collide`() {
		// `support` calls `todo` "En cours"; Kanso calls `in_progress` that. A page holding
		// "En cours" under a Support ticket means what Support means.
		assertEquals(DefaultStatus.TODO, statusFromWord("En cours", support))
	}
}
