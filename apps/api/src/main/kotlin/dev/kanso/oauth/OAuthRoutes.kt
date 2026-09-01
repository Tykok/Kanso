package dev.kanso.oauth

/**
 * The paths the OAuth flow answers without a session — every one of them, in one place.
 *
 * The same shape, and the same reason, as `PublicRoutes`: widening the filter chain is
 * the most dangerous edit in the application, and a reviewer should be able to see the
 * whole added surface at once. `OAuthRoutesTest` asserts the shape, so "just add a
 * wildcard while you are in there" fails the suite instead of shipping.
 *
 * Paths are the library's defaults rather than names of our choosing: a client finds
 * every one of them in the metadata document, so they are not a product decision — and
 * renaming them would be configuration that can drift from what the library serves.
 */
object OAuthRoutes {

	/** Discovery. Both are public by specification: a client reads them before it has anything. */
	val OPEN_GET = arrayOf(
		"/.well-known/oauth-authorization-server",
		"/.well-known/oauth-protected-resource",
	)

	/**
	 * The three endpoints a client calls with no browser and no session.
	 *
	 * `/connect/register` is the one to watch: unauthenticated *and* row-creating, which
	 * is the most abusable surface this branch adds. It is rate-limited, and it is here
	 * rather than hidden because a list of open routes that omits the dangerous one is
	 * worse than no list.
	 *
	 * It is also the only one of the three this list actually opens, and the entry above
	 * overstates the rest: in oidc mode `/oauth2/token` and `/oauth2/revoke` belong to the
	 * authorisation server's chain, which is ordered ahead of Kanso's and claims them
	 * through `configurer.endpointsMatcher` — a rule in `SecurityConfig` is never reached
	 * for them. In dev mode that chain does not exist, so the two are permitted and answer
	 * 404. They stay listed because a reader looking for what the OAuth flow opens should
	 * find them named, and because deleting them would make this file quietly wrong the
	 * day the chains are reordered.
	 */
	val OPEN_POST = arrayOf(
		"/oauth2/token",
		"/oauth2/revoke",
		"/connect/register",
	)

	/** For the guard test, which checks the whole set rather than each array. */
	val ALL: List<String> get() = OPEN_GET.toList() + OPEN_POST.toList()
}
