package dev.kanso.github

/**
 * One ticket key found on a pull request, and whether finding it there was a declaration
 * of intent.
 *
 * [closes] is the whole of the difference between a link that displays and a link that can
 * move a ticket. A bare mention is inert on purpose — it is what keeps
 * `unlike KAN-99, this one...` from closing KAN-99 — and part three of the design only
 * transitions on the ones that say true.
 */
data class PrLink(val key: String, val closes: Boolean)

/**
 * Which tickets a pull request is about, read from the three fields that can say so.
 *
 * **Pure.** No repository, no HTTP, no clock, no team list. That is not tidiness: it is
 * what lets the rule be tested as a table of inputs rather than through a webhook, and it
 * is why this file is the only place the rule exists.
 *
 * The keys it returns are *candidates*. They are matched against the real team keys by the
 * caller, afterwards, so an unknown key is **not a link** rather than an error —
 * `ARCH-12` in a body is somebody else's tracker, not a refusal, and a parser that threw
 * on it would make Kanso reject pull requests for mentioning other people's work.
 */
object PrLinkParser {

	/**
	 * `KAN-142`, and deliberately liberal about what a key may look like.
	 *
	 * The prefix is letters-then-word-characters rather than `[A-Z]+`, because this pattern
	 * runs against branch names, and the caller is what narrows the result to teams that
	 * exist. Being liberal here costs a candidate that matches nothing; being strict would
	 * cost a real link the day somebody's team key has a digit in it.
	 *
	 * Two boundaries earn their keep:
	 *
	 *  * `(\d+)` is greedy, so `KAN-1425` is KAN-1425 and never KAN-142 with a stray `5`.
	 *    A pull request closing the wrong ticket is the worst bug this feature can have and
	 *    a non-greedy quantifier is how it would happen.
	 *  * The leading `\b` means `XKAN-12` yields the candidate `XKAN`, not `KAN` — so a key
	 *    inside a longer word resolves to a team that does not exist and drops out at the
	 *    caller. That is the spec's "a key inside a longer word" case, answered by making
	 *    the wrong answer unmatchable rather than by a negative lookbehind.
	 *
	 * A trailing `\b` rather than an end anchor, because the common branch name is
	 * `feat/kan-142-overlap-warning` and the character after the number is a hyphen.
	 */
	private val KEY = Regex("""\b([A-Za-z][A-Za-z0-9_]*)-(\d+)\b""")

	/**
	 * The keywords, immediately before a key.
	 *
	 * The design names `Fixes`, `Closes` and `Resolves`. The inflections are accepted too —
	 * `fix`, `fixed`, `close`, `closed`, `resolve`, `resolved` — and the reason is not
	 * generosity: **GitHub's own keyword list contains all nine**, so a body reading
	 * `Fixed KAN-142` already closes the linked GitHub issue on merge. Recognising only
	 * three would make Kanso's reading of a pull request differ from GitHub's reading of
	 * the same sentence, in a feature whose entire purpose is that the two agree.
	 *
	 * An optional colon and required whitespace between keyword and key, so `Fixes:
	 * KAN-142` works and the accidental `it closes KAN-9 problems` — which has no key
	 * adjacent — does not get a keyword it did not earn.
	 */
	private val CLOSING = Regex(
		"""\b(?:fix|fixes|fixed|close|closes|closed|resolve|resolves|resolved)\b\s*:?\s+""" +
			"""([A-Za-z][A-Za-z0-9_]*-\d+)\b""",
		RegexOption.IGNORE_CASE,
	)

	/**
	 * Every candidate on the pull request, each with the strongest claim made for it.
	 *
	 * The three fields are read for two different things. `headRef` contributes closing
	 * links and nothing else — **naming a branch after a ticket is a declaration of intent
	 * at least as strong as a keyword**, and it is the common case. A rule that required
	 * `Fixes` in the body would leave most real pull requests unable to finish anything,
	 * which is the automation nobody trusts. `title` and `body` contribute a closing link
	 * where a keyword precedes the key and an inert one everywhere else.
	 *
	 * Claims are OR-ed, not last-one-wins: a key that appears bare in the body *and* after
	 * `Fixes` in the title closes. The order the three fields are read in therefore does not
	 * matter, which is one fewer thing for a caller to get wrong.
	 *
	 * Case-insensitive throughout, and keys come back **uppercase**, because branches are
	 * lowercase in practice (`feat/kan-142-…`) and keys are uppercase in Kanso. Normalising
	 * here rather than at the caller means `feat/kan-142` and `Fixes KAN-142` on the same
	 * pull request are one link with `closes = true`, not two links that disagree.
	 */
	fun parse(headRef: String?, title: String?, body: String?): Set<PrLink> {
		val claims = mutableMapOf<String, Boolean>()

		fun claim(raw: String, closes: Boolean) {
			val key = raw.uppercase()
			claims[key] = (claims[key] ?: false) || closes
		}

		// The branch. Every key on it closes.
		headRef?.let { ref -> KEY.findAll(ref).forEach { claim(it.value, closes = true) } }

		// The prose. A keyword sighting is recorded first so that the bare-mention pass
		// below — which sees the same key again, since a keyword does not consume it —
		// cannot weaken it. The OR in `claim` is what makes that true rather than the order.
		for (text in listOfNotNull(title, body)) {
			CLOSING.findAll(text).forEach { claim(it.groupValues[1], closes = true) }
			KEY.findAll(text).forEach { claim(it.value, closes = false) }
		}

		return claims.map { (key, closes) -> PrLink(key, closes) }.toSet()
	}
}
