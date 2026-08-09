package dev.kanso.domain

import java.util.UUID

/** What happens to something a team or project holds when the container goes away. */
enum class DispositionChoice(override val wire: String) : Wire {
	/** Goes with the container: archived alongside it, or deleted with it. */
	TAKE("take"),

	/** Stays active, which always implies re-homing. */
	KEEP("keep");

	companion object {
		fun from(raw: String): DispositionChoice = parse(entries.toTypedArray(), raw)
	}
}

data class DispositionCounts(
	val subTeams: Int,
	val projects: Int,
	val tickets: Int,
)

/**
 * Both readings of what a container holds, because the plan decides which one is true.
 *
 * With `subTeams: "keep"` the sub-teams leave first, with their own contents untouched,
 * and the operation reaches this container alone — that is [direct]. With
 * `subTeams: "take"` the whole subtree goes, and every project and ticket in it is
 * destroyed, archived or renumbered — that is [subtree].
 *
 * Sending both rather than taking the plan as a parameter keeps `/contents` a plain
 * GET and lets the modal repaint the instant a radio moves, with no round trip and no
 * window in which the numbers on screen belong to a choice nobody has made yet. The
 * two coincide for a project, which holds no teams, and for a childless team.
 */
data class DispositionContents(
	val direct: DispositionCounts,
	val subtree: DispositionCounts,
)

/**
 * [counts] is what the modal displayed. When present it is compared against a fresh
 * count and a mismatch aborts the whole operation; deletion requires it, archiving
 * ignores it.
 */
data class DispositionPlan(
	val subTeams: DispositionChoice = DispositionChoice.KEEP,
	val projects: DispositionChoice = DispositionChoice.KEEP,
	val tickets: DispositionChoice = DispositionChoice.KEEP,
	val ticketsTargetTeamId: UUID? = null,
	val counts: DispositionCounts? = null,
)
