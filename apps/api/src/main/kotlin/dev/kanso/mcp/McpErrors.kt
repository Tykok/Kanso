package dev.kanso.mcp

import dev.kanso.service.BadRequestException
import dev.kanso.service.ConflictException
import dev.kanso.service.NotFoundException
import org.springframework.security.access.AccessDeniedException

/**
 * A tool failure is a **result**, not a transport error.
 *
 * `ApiExceptionHandler` turns the same exceptions into RFC 7807 for a browser, and this
 * is the other rendering of the one set: an MCP client reading an HTTP 400 reports a
 * broken server and stops, where a `tools/call` result carrying `isError` and a sentence
 * is something the model reads and retries against. So the exception is caught at the
 * boundary and its own message is passed through — the sentences are already written to
 * name the fix (`No team $id`, `These filters are not served: …`), and rewording them
 * here would be a second copy to keep in step.
 *
 * **`AccessDeniedException` is in this list, and that is the one entry worth arguing.**
 * The spec says authorisation failures stay HTTP so a client can re-authorise — and that
 * is right for *insufficient scope*, which [McpController] answers with a 403 and a
 * challenge. It is wrong for this one: "Hers is not one of your teams" is not fixed by
 * asking for a bigger token, because no token outranks its owner. It is a fact about the
 * backlog, and an agent that reads it can act on it by choosing another team. A 403 here
 * would instead send a client into a step-up flow that cannot succeed, forever.
 */
object McpErrors {

	/**
	 * @return the sentence to hand back, or `null` for anything this does not recognise —
	 *   which the caller must let escape rather than describe. An unexpected exception
	 *   reported as tool content is a 500 disguised as an answer, and the agent would read
	 *   it as its own mistake and retry.
	 */
	fun sentence(failure: Throwable): String? = when (failure) {
		is NotFoundException,
		is ConflictException,
		is BadRequestException,
		is AccessDeniedException,
		// The enum parsers raise this for a status or a priority outside the vocabulary,
		// and the message already lists the vocabulary — the same reason
		// `ApiExceptionHandler` maps it to 400 rather than 500.
		is IllegalArgumentException,
		-> failure.message ?: "Refused"

		else -> null
	}
}
