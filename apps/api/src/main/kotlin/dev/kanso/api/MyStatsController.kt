package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.service.MyStats
import dev.kanso.service.MyStatsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `/api/me/stats`, and deliberately nothing wider.
 *
 * `VelocityController` settled the rule this route reuses rather than inventing a second
 * one: you may read your own. Every number underneath is about the caller's own tickets,
 * the caller is the only subject the service accepts, and there is no `userId` parameter
 * to widen it with — a route exposing one person's output to anyone who can guess a user
 * id is not a thing to add before somebody has decided who may read it, and nothing on
 * this screen needs it.
 *
 * No `teamId` either, unlike `/api/me/velocity`, and the asymmetry is the point. A
 * velocity is a *rate*, so it has to be measured against one team's calendar or it divides
 * work by days counted twice. These are counts and point sums, which add across teams —
 * and a personal home has no team selected to pass. Where the distinction still matters
 * the response carries it: [MyStats.commitments] is one row per cycle in progress, never a
 * total of two.
 *
 * The service's shapes go on the wire unchanged, with no response DTO between them.
 * `EffectiveVelocityResponse` exists to flatten a sealed hierarchy Jackson has no shape
 * for and to publish a verdict a client must not re-derive; this answer has neither, so a
 * second copy of five data classes would only be five more places to forget a field.
 */
@RestController
class MyStatsController(
	private val currentUser: CurrentUser,
	private val stats: MyStatsService,
) {

	@GetMapping("/api/me/stats")
	fun mine(): MyStats = stats.forPerson(currentUser.require())
}
