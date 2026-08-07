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
