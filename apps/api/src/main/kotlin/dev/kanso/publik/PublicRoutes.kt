package dev.kanso.publik

/**
 * The paths that answer without a session — every one of them, in one place.
 *
 * `SecurityConfig` opens the filter chain by spreading these arrays rather than by
 * spelling the patterns out inline, and that indirection is the whole point: widening
 * the chain is the most dangerous edit in the application, and this is a file small
 * enough that a reviewer can see the entire attack surface at once. `PublicRoutesTest`
 * asserts the shape of what is here, so "just add a wildcard while you are in there"
 * fails the suite instead of shipping.
 *
 * Each pattern names its path variables. None of them is a wildcard over the whole
 * `/api/public` prefix: that would silently open whatever a later slice puts under it,
 * and the cost of naming three routes is three lines.
 */
object PublicRoutes {

	/** Screen 27's list, and screen 28's single ticket. */
	val OPEN_GET = arrayOf(
		"/api/public/roadmap",
		"/api/public/roadmap/{teamKey}/{number}",
	)

	/**
	 * Voting, and nothing else. Anonymous *and* state-changing, which is the one
	 * combination `architecture.md` singles out — and it is safe for the reason that
	 * document gives rather than in spite of it: CSRF protects a request that carries a
	 * credential the browser attaches on its own, and this request carries none. There
	 * is no session to ride.
	 */
	val OPEN_POST = arrayOf(
		"/api/public/roadmap/{teamKey}/{number}/vote",
	)

	/** For the guard test, which checks the whole set rather than each array. */
	val ALL: List<String> get() = OPEN_GET.toList() + OPEN_POST.toList()
}
