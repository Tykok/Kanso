package dev.kanso.outbox

import java.time.Duration

/**
 * What to do with a job, for one destination.
 *
 * This interface is the generalisation. Claiming, ordering, backing off, giving up
 * and reclaiming a dead worker's locks are the same problem whatever the job is for,
 * and they live once in [OutboundWorker]. What a job *is* lives here, once per
 * destination. Adding a consumer is a handler and a value in [Destination], not a
 * second queue with its own idea of what "failed" means.
 */
interface OutboundJobHandler {

	/** Which queue this handler drains. Exactly one handler per destination. */
	val destination: Destination

	/**
	 * Does the job's remote half, and returns what to write down locally.
	 *
	 * Split in two because the halves belong in different transactions, and where the
	 * seam goes is not the handler's to decide. The remote call runs with no database
	 * connection held — a push spends most of its life in an HTTP round trip, and
	 * holding a pooled connection across one exhausts the pool long before it exhausts
	 * the remote's rate limit. The returned block then runs in the same transaction
	 * that marks the job done, so a crash cannot leave a page recorded as pushed with
	 * its job still queued, or a job done with nothing recorded.
	 *
	 * Returning normally means success. Throwing means failure, and [classify] says
	 * what kind.
	 */
	suspend fun handle(job: OutboundJob): Completion

	/**
	 * How to treat a thrown exception.
	 *
	 * Only the handler can answer: that a 429 means wait without spending an attempt,
	 * and a 422 means never try again, are facts about one destination's API. The
	 * default treats anything unrecognised as worth another go — the failure mode of
	 * retrying something hopeless is a row in the failures tab a few minutes later,
	 * and the failure mode of giving up on something transient is a change that never
	 * leaves.
	 */
	fun classify(error: Exception): Failure =
		Failure.Retry(error.message ?: error.javaClass.simpleName)

	/**
	 * Last rites, run in the transaction that marks the job failed.
	 *
	 * The default is to do nothing. A destination that mirrors a job's fate onto the
	 * entity itself — the way Notion's `sync_state` makes a stuck mirror visible on
	 * the row — writes that here.
	 */
	fun onGivenUp(job: OutboundJob, error: Exception) {}
}

/**
 * The local half of a finished job, run inside the worker's transaction.
 *
 * A value rather than a second interface method so a handler with nothing to record
 * says [NOTHING] instead of implementing an empty override, and so the worker cannot
 * forget to call it.
 */
fun interface Completion {
	fun record()

	companion object {
		val NOTHING = Completion {}
	}
}

/**
 * The three things that can be wrong with a job, and the only three the worker knows
 * how to act on.
 */
sealed interface Failure {

	/**
	 * Nothing is wrong with the job — a dependency has not landed at the far end yet,
	 * or the remote asked us to wait. The attempt is refunded, because counting these
	 * against `max-attempts` fails perfectly good work for being queued behind
	 * something slow.
	 */
	data class Defer(val reason: String, val delay: Duration) : Failure

	/** A genuine failure worth another go. Backs off, and counts. */
	data class Retry(val message: String) : Failure

	/**
	 * The remote will refuse this one forever. Straight to `failed`, where the inbox's
	 * failures tab reads it, rather than spending eight attempts proving it.
	 */
	data class Fatal(val message: String) : Failure
}
