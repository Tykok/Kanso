package dev.kanso.auth

import dev.kanso.service.TicketAccess
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * The writes a read-only seat may still make — every one of them, in one place.
 *
 * The same shape and the same reason as `PublicRoutes`: the list of exceptions to a
 * security rule is the part of it worth reading, so it is one screen a reviewer can take
 * in at once rather than a `@PreAuthorize` scattered over forty controllers. Deny is the
 * default and this is the whole of the other side; a route that is not here is refused,
 * including one added tomorrow by somebody who never read this file. That last property
 * is the entire argument for a choke point over per-endpoint annotations.
 *
 * Each entry is the HTTP method and the *mapping pattern the controller declares*, not the
 * URL a client types — `ReadOnlySeatInterceptor` matches against the pattern Spring MVC
 * resolved, so `{id}` here is the literal text in the `@PutMapping`. Renaming a route
 * therefore drops it out of this list rather than silently widening it, which is the
 * direction a mistake should fall in. `ReadOnlySeatTest` asserts every entry still names a
 * real mapping, so the list cannot rot into permissions granted to nothing.
 *
 * The three groups below are three different arguments, and they are kept apart because
 * conflating them is how an exception list grows: *their own screen*, *their own account*,
 * and *not a decision about seats at all*.
 */
object ReadOnlySeat {

	/**
	 * A viewer's own screen, their own attention and their own pace — what only they decide.
	 *
	 * These write rows, and none of the rows is the team's data. A blanket refusal that
	 * stopped somebody setting dark mode would be a worse product than a considered list,
	 * and would also be dishonest about what the seat is: the promise is "you cannot change
	 * *the work*", not "you cannot change *anything*". Every path here is under `/api/me`
	 * or `/api/notifications`, and every row it writes is keyed on the actor's own user id
	 * inside the service.
	 *
	 * **The test is who decides, not who can see.** These were once argued in as "invisible
	 * to everybody else on the instance", which stopped being true when declared velocity
	 * moved into preferences: it feeds `/api/tickets/{id}/duration`, so anyone who can read
	 * a ticket you are assigned to can read your pace back out of it. The exemption stands
	 * regardless — your own estimate of yourself is yours to state — but the retired sentence
	 * would have waved the next entry through on a reason that was never the reason. What
	 * qualifies is that nobody but the actor can set it and that it asserts nothing about the
	 * work; that somebody else can *read* it is not disqualifying, and being unreadable was
	 * never the promise.
	 *
	 * Favourites are in for the same reason preferences are — a pin is a sidebar, and the
	 * `favourites` table is keyed per user with a cascade on the account. Marking the inbox
	 * read is in because an inbox nobody can clear stops being an inbox on day two, and
	 * `notifications.read_at` says one person has looked, not that anything happened.
	 */
	val OWN_SCREEN = arrayOf(
		"PUT /api/me/preferences",
		"PUT /api/me/favourites/{kind}/{id}",
		"DELETE /api/me/favourites/{kind}/{id}",
		"POST /api/notifications/read-all",
		"POST /api/notifications/{id}/read",
	)

	/**
	 * A viewer's own account.
	 *
	 * Narrower-sounding and more important: a seat that could not rotate its own password
	 * would be a security regression dressed as a permission, and one that could not unlink
	 * a provider could not leave. Renaming yourself is here because the alternative is an
	 * instance where a typo in a display name needs an admin.
	 *
	 * `notion-identity` used to be here, argued in as the one debatable entry. It is gone
	 * because the route is: who you are in a Notion workspace turned out not to be a field
	 * about you at all, and it is read-only now for everybody, seat or no seat.
	 *
	 * Setting *somebody else's* role is not here, and neither is any other `/api/people`
	 * write: those are admin business, refused to a viewer twice over — once by this
	 * interceptor and once by `AccountService`.
	 */
	val OWN_ACCOUNT = arrayOf(
		"PUT /api/me",
		"PUT /api/me/password",
		"DELETE /api/me/identities/{provider}",
		"DELETE /api/oauth/grants/{clientId}",
	)

	/**
	 * Not a decision about seats.
	 *
	 * Every path here answers a caller with no session at all — `PublicRoutes.OPEN_POST`,
	 * `OAuthRoutes.OPEN_POST`, and the two doors of the sign-in screen. Refusing a viewer
	 * something a total stranger may do would be an absurdity rather than a policy: the
	 * roadmap vote is keyed on an anonymous voter key and not on a Kanso account at all, and
	 * a viewer who could not sign in would have no way to reach the reads the seat exists
	 * for.
	 *
	 * They are listed rather than left implicit because these endpoints *can* be reached
	 * with a session standing — a signed-in person opening the public roadmap, a viewer
	 * re-authenticating in a second tab — and an unlisted route is refused.
	 *
	 * `POST /api/setup/owner` is deliberately absent. It is open for the same "no session
	 * yet" reason, but the thing it does is claim the instance, and there is no reading of
	 * that a viewer should be handed. It is unreachable anyway once an owner exists, which
	 * is precisely whenever a viewer does.
	 */
	val OPEN_TO_STRANGERS = arrayOf(
		"POST /api/auth/login",
		"POST /api/auth/accept-invitation",
		"POST /api/public/roadmap/{teamKey}/{number}/vote",
		"POST /connect/register",
	)

	/**
	 * The MCP endpoint, which is one path for both reads and writes.
	 *
	 * It cannot be judged by its verb: every JSON-RPC call is a `POST`, so refusing the
	 * method here would take a viewer's agent's *reads* away too, and reads are the entire
	 * point of the seat. So this door is opened, and the refusal happens one layer in,
	 * where the question can actually be asked — a writing tool reaches a service that
	 * reaches `TicketAccess`, and `TicketAccess` refuses a viewer. Same rule, same sentence,
	 * no MCP-specific clause. `McpTool.writes` stays what it is: the *scope* gate, which is
	 * a question about the grant and not about the seat.
	 */
	val AGENT_DOOR = arrayOf(
		"POST /api/mcp",
	)

	/** For the guard test, which checks the whole set rather than each array. */
	val ALL: List<String> get() =
		OWN_SCREEN.toList() + OWN_ACCOUNT.toList() + OPEN_TO_STRANGERS.toList() + AGENT_DOOR.toList()

	private val allowed: Set<String> = ALL.toSet()

	/**
	 * Safe methods are never refused. That is not a shortcut around the rule, it *is* the
	 * rule — the seat reads everything a member of the same teams reads, and Kanso has no
	 * state-changing `GET` (`SecurityConfig` turns CSRF off on exactly that premise, so the
	 * day one appears this and that both have to change).
	 */
	private val safe = setOf(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name())

	fun refuses(method: String, pattern: String?): Boolean {
		if (method.uppercase() in safe) return false
		// A request Spring MVC could not name is one this list cannot have exempted. Refusing
		// is the only answer that stays correct if the attribute ever goes missing.
		return "$method ${pattern ?: return true}" !in allowed
	}
}

/**
 * The read-only seat, enforced at the one place every HTTP write passes through.
 *
 * **Not in the UI.** A hidden button whose endpoint still answers is not a permission, so
 * the client hiding a composer is a courtesy and this is the mechanism. The web app is
 * told the role through `/api/me` and draws accordingly; nothing downstream trusts it.
 *
 * An interceptor rather than a `@PreAuthorize` per method, and rather than a rule in
 * `SecurityConfig`. Against the annotation: forty of them is thirty-nine chances to forget
 * the fortieth, and the one forgotten is the whole feature. Against the filter chain: it
 * would have to re-derive the role from a principal that does not carry one, and the
 * pattern a route is *mapped* on — which is what [ReadOnlySeat] lists — is only known once
 * a handler has been chosen, which is here. Throwing from `preHandle` reaches
 * `ApiExceptionHandler` the same way a throw from a controller does, so a viewer gets the
 * same RFC 7807 403 as anybody else refused, with a sentence saying why.
 *
 * It costs one primary-key read per unsafe request from a signed-in caller, and unsafe
 * requests are the rare ones. An anonymous request is left alone: whether it may be here
 * at all was decided by the filter chain, and there is no seat to consult.
 */
@Component
class ReadOnlySeatInterceptor(private val currentUser: CurrentUser) : HandlerInterceptor {

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		if (currentUser.principalOrNull() == null) return true
		val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
		if (!ReadOnlySeat.refuses(request.method, pattern)) return true
		if (currentUser.require().instanceRole.mayWrite) return true
		// `TicketAccess`'s sentence, not one of this file's own. The two doors refuse the same
		// person for the same reason, and a viewer who meets both should not be able to tell
		// which one they hit.
		throw AccessDeniedException(TicketAccess.READS_NOT_WRITES)
	}
}

/**
 * Registered here rather than in `config/WebConfig`, so that the list, the rule and the
 * wiring are one file. A reviewer asking "is this actually switched on" should not have to
 * find the answer somewhere else.
 *
 * `addPathPatterns` is deliberately absent: the interceptor runs on everything and decides
 * for itself, because a path filter here would be a second, quieter copy of [ReadOnlySeat]
 * — and the one that silently wins.
 */
@Configuration
class ReadOnlySeatConfig(private val interceptor: ReadOnlySeatInterceptor) : WebMvcConfigurer {
	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(interceptor)
	}
}
