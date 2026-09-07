package dev.kanso.github

import dev.kanso.domain.DefaultStatus
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The three guards, as a table. These are the assertions the design says carry part three,
 * and they are here rather than behind a webhook because what they protect is somebody's
 * ticket moving without them asking.
 */
class PrTransitionTest {

	private val noon = OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.UTC)

	private fun decide(
		closes: Boolean = true,
		current: DefaultStatus,
		target: DefaultStatus = DefaultStatus.DONE,
		human: OffsetDateTime? = null,
		eventAt: OffsetDateTime = noon,
	) = PrTransition.decide(closes, current, target, human, eventAt)

	// ------------------------------------------------------------------ the two moves

	@Test
	fun `a merge moves a closing ticket to done`() {
		assertEquals(
			TransitionDecision.Move(DefaultStatus.DONE),
			decide(current = DefaultStatus.IN_PROGRESS),
		)
	}

	@Test
	fun `ready for review moves it to in review`() {
		assertEquals(
			TransitionDecision.Move(DefaultStatus.IN_REVIEW),
			decide(current = DefaultStatus.IN_PROGRESS, target = DefaultStatus.IN_REVIEW),
		)
	}

	// ------------------------------------------------------------------ guard three

	@Test
	fun `a bare mention displays and does not act`() {
		assertEquals(
			TransitionDecision.NotAClosingLink,
			decide(closes = false, current = DefaultStatus.TODO),
			"a link that only mentions the ticket must never move it, whatever the event",
		)
	}

	/** Guard three outranks everything: an inert link is inert even for a valid move. */
	@Test
	fun `closes false is refused before any other question is asked`() {
		assertEquals(
			TransitionDecision.NotAClosingLink,
			decide(closes = false, current = DefaultStatus.CANCELED),
		)
	}

	// ------------------------------------------------------------------ guard one

	@Test
	fun `a lower rank is never reached backwards`() {
		assertEquals(
			TransitionDecision.NotBackwards,
			decide(current = DefaultStatus.DONE, target = DefaultStatus.IN_REVIEW),
			"a pull request reopened must not pull a finished ticket back into review",
		)
	}

	/**
	 * Equal rank is refused too, and for a reason of its own: moving a ticket to the status
	 * it already holds would write an activity row that says nothing, and a feed full of
	 * those is a feed nobody reads.
	 */
	@Test
	fun `a ticket already there does not move again`() {
		assertEquals(TransitionDecision.NotBackwards, decide(current = DefaultStatus.DONE))
	}

	@Test
	fun `the whole ranking, forwards and backwards`() {
		val order = listOf(
			DefaultStatus.BACKLOG,
			DefaultStatus.TODO,
			DefaultStatus.IN_PROGRESS,
			DefaultStatus.IN_REVIEW,
			DefaultStatus.DONE,
		)
		for ((i, from) in order.withIndex()) {
			for ((j, to) in order.withIndex()) {
				val expected = if (j > i) TransitionDecision.Move(to) else TransitionDecision.NotBackwards
				assertEquals(expected, decide(current = from, target = to), "$from -> $to")
			}
		}
	}

	// ------------------------------------------------------------------ canceled

	@Test
	fun `canceled is outside the ranking and untouched in either direction`() {
		assertEquals(
			TransitionDecision.Canceled,
			decide(current = DefaultStatus.CANCELED, target = DefaultStatus.DONE),
			"cancelling is a decision and a merge is not evidence against it",
		)
		assertEquals(
			TransitionDecision.Canceled,
			decide(current = DefaultStatus.CANCELED, target = DefaultStatus.IN_REVIEW),
		)
	}

	// ------------------------------------------------------------------ guard two

	@Test
	fun `a person who moved it after the event keeps their answer`() {
		assertEquals(
			TransitionDecision.NotOverAPerson,
			decide(current = DefaultStatus.TODO, human = noon.plusMinutes(5)),
			"you moved it by hand while the pull request sat open; the merge does not overrule you",
		)
	}

	@Test
	fun `a person who moved it before the event does not block it`() {
		assertEquals(
			TransitionDecision.Move(DefaultStatus.DONE),
			decide(current = DefaultStatus.TODO, human = noon.minusMinutes(5)),
		)
	}

	/**
	 * A tie goes to the person. Not a nicety: `isAfter` here instead of `!isBefore` would
	 * make the outcome depend on clock resolution, which is the kind of bug that reproduces
	 * once a month.
	 */
	@Test
	fun `a hand move in the same instant as the event resolves for the person`() {
		assertEquals(
			TransitionDecision.NotOverAPerson,
			decide(current = DefaultStatus.TODO, human = noon),
		)
	}

	/**
	 * The reason guard two compares against the event and never against `now()`. GitHub
	 * gives up retrying after about three days, so this delivery is realistic: an event from
	 * an hour ago, redelivered, against a person who acted half an hour ago. Comparing to
	 * `now()` would make the stale event look newer and drag the ticket backwards.
	 */
	@Test
	fun `a redelivery does not win a race it lost when it was first sent`() {
		assertEquals(
			TransitionDecision.NotOverAPerson,
			decide(
				current = DefaultStatus.IN_PROGRESS,
				target = DefaultStatus.IN_REVIEW,
				human = noon.minusMinutes(30),
				eventAt = noon.minusHours(1),
			),
		)
	}

	@Test
	fun `only automation has ever touched it, so nothing is in the way`() {
		assertEquals(
			TransitionDecision.Move(DefaultStatus.DONE),
			decide(current = DefaultStatus.IN_REVIEW, human = null),
		)
	}
}
