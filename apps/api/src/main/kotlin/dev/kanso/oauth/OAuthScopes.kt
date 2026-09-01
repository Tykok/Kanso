package dev.kanso.oauth

/**
 * What an agent may be granted, and how that is said to the person granting it.
 *
 * Two, not twelve, and not per-team. Team membership already bounds what a member can
 * reach, and a second scoping system that can disagree with the first is the failure the
 * spec opens with. A read-only grant exists because "let an agent look at my backlog" is
 * a genuinely smaller ask than "let it fill it".
 *
 * Namespaced because these strings appear on a consent screen and in other people's
 * client configuration, so they have to read as Kanso's own.
 */
object OAuthScopes {

	const val READ = "kanso:read"
	const val WRITE = "kanso:write"

	val ALL: List<String> = listOf(READ, WRITE)

	private val PROSE = mapOf(
		READ to "Read the tickets, projects, documents and dates of the teams you belong to",
		// No promise of provenance. `activity.via_client_id` exists in the schema and
		// nothing writes it yet, and a sentence next to an Authorise button that describes
		// a record this version does not keep is worse than a plainer one — the settings
		// screen repeats it word for word through `GrantService.scopeProse`. It goes back
		// when the column is filled, and `AgentRightsTest` is the tripwire that says so.
		WRITE to "Create and change them, and comment — as you, so the change carries your name",
	)

	/**
	 * Refuses an unknown scope rather than rendering it.
	 *
	 * A client sends whatever it likes. Echoing an unrecognised scope onto the consent
	 * screen would put a stranger's words in Kanso's voice, next to an Authorise button.
	 */
	fun prose(scope: String): String = requireNotNull(PROSE[scope]) {
		"Unknown scope '$scope' — this instance grants ${ALL.joinToString(" and ")}"
	}
}
