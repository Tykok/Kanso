package dev.kanso.publik

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The three routes a stranger may call. Nothing here asks `CurrentUser` for anything,
 * and nothing here can: there is no session behind these requests.
 *
 * The paths are `PublicRoutes`' patterns, and the two have to agree — a mapping the
 * filter chain does not open answers 401, which is a loud failure rather than a quiet
 * one, and the e2e spec calls all three anonymously to prove they do agree.
 */
@RestController
@RequestMapping("/api/public/roadmap")
class PublicController(
	private val roadmap: PublicRoadmapService,
	private val votes: VoteService,
	private val voterKeys: VoterKeys,
) {

	@GetMapping
	fun roadmap(): RoadmapResponse = RoadmapResponse.of(roadmap.roadmap())

	@GetMapping("/{teamKey}/{number}")
	fun contributorPage(@PathVariable teamKey: String, @PathVariable number: Int): ContributorResponse =
		ContributorResponse.of(roadmap.contributorPage(teamKey, number))

	@PostMapping("/{teamKey}/{number}/vote")
	fun vote(
		@PathVariable teamKey: String,
		@PathVariable number: Int,
		request: HttpServletRequest,
	): VoteResponse = VoteResponse.of(votes.vote(teamKey, number, voterKeys.keyFor(addressOf(request))))

	/**
	 * The voter's address as best it can be known, and knowingly forgeable.
	 *
	 * `X-Forwarded-For` is a client-supplied header, so trusting its first hop is
	 * usually a mistake — but the question here is only "have I seen this visitor
	 * today", and the worst a forged value achieves is a second vote, which the design
	 * already concedes to anyone with a second address. Ignoring the header instead
	 * would be worse for the honest case: behind the reverse proxy a self-hosted
	 * instance actually runs behind, every visitor would share the proxy's address and
	 * the first vote of the day would be the only one anybody could cast.
	 *
	 * Since `application.yml` set `server.forward-headers-strategy: framework`,
	 * `getRemoteAddr` *is* that leftmost forwarded hop, so the read below and the fallback
	 * under it have converged on one answer. It stays explicit because it is the half that
	 * does not depend on a property an operator can set back to `none`.
	 */
	private fun addressOf(request: HttpServletRequest): String =
		request.getHeader("X-Forwarded-For")
			?.substringBefore(',')
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?: request.remoteAddr
			?: "unknown"
}
