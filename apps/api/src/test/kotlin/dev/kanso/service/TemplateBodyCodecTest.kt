package dev.kanso.service

import dev.kanso.domain.TemplateBody
import dev.kanso.domain.TicketPriority
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shape rules, which are in Kotlin because a CHECK cannot reach inside a document.
 *
 * The distinction the first two tests draw is the one worth keeping: an *absent* key means
 * "do not pre-fill this" and leaves the composer's own seeding alone, while a present empty
 * string means "start this field blank". A codec that collapsed them would make it impossible
 * to write a template that carries only a description.
 */
class TemplateBodyCodecTest {

	private val codec = TemplateBodyCodec(ObjectMapper())

	@Test
	fun `an absent key is not an empty one`() {
		val body = codec.decode("""{"description": "## What happens\n"}""")
		assertNull(body.title)
		assertEquals("## What happens\n", body.description)
		assertNull(body.priority)
		assertEquals(emptyList(), body.labels)
	}

	@Test
	fun `an empty string is a value`() {
		assertEquals("", codec.decode("""{"title": ""}""").title)
	}

	@Test
	fun `a priority outside the vocabulary is refused by name`() {
		val failure = assertFailsWith<BadRequestException> {
			codec.decode("""{"priority": "burning"}""")
		}
		assertTrue(failure.message!!.contains("urgent"), "the refusal names the vocabulary")
	}

	@Test
	fun `an estimate off the scale is refused`() {
		assertFailsWith<BadRequestException> { codec.decode("""{"estimate": 7}""") }
	}

	@Test
	fun `labels are trimmed, lower-cased and de-duplicated`() {
		val body = codec.decode("""{"labels": ["  bug ", "BUG", "chore"]}""")
		assertEquals(listOf("bug", "chore"), body.labels)
	}

	@Test
	fun `a round trip through encode and decode is the identity`() {
		val body = TemplateBody(
			title = "[Bug] ",
			description = "## What happens\n",
			priority = TicketPriority.HIGH,
			estimate = 3,
			labels = listOf("bug"),
			fields = mapOf("Severity" to "major"),
		)
		assertEquals(body, codec.decode(codec.encode(body)))
	}

	@Test
	fun `a body is not a place to put a novel`() {
		val long = "x".repeat(20_001)
		assertFailsWith<BadRequestException> { codec.decode(codec.encodeRaw(mapOf("description" to long))) }
	}
}
