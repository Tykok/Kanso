package dev.kanso.service

class NotFoundException(message: String) : RuntimeException(message)

/** The request is well-formed but conflicts with the current state (duplicate key, cycle). */
class ConflictException(message: String) : RuntimeException(message)

/** The request is understandable but wrong (unknown status, missing team). */
class BadRequestException(message: String) : RuntimeException(message)

/**
 * The contents changed between the preview the person saw and the request. Carries the
 * fresh counts so the modal can reopen on the truth.
 */
class CountsChangedException(val counts: dev.kanso.domain.DispositionCounts) :
	RuntimeException("The contents changed since they were counted")

/**
 * Somebody else is holding the block — `KAN-25`'s one refusal.
 *
 * A [CountsChangedException]-shaped 409 rather than a bare [ConflictException], and for the
 * same reason that one gives: it is a refusal the person can *act* on, so the answer carries
 * what they need instead of asking them to guess. Two facts, and both are load-bearing —
 * **who** holds it, so the reader knows whom to ask, and **when it frees itself**, so they
 * know that waiting is a plan. A refusal that says only "no" is the bug `useReportError`
 * was written for.
 *
 * The message is written for a person because it is what the topbar strip prints verbatim
 * (`ApiExceptionHandler` puts `detail` on the wire as-is), and it is the last line of
 * defence: a client that never reads the two properties still says something true.
 */
class BlockLockedException(val holder: String, val freesAt: java.time.OffsetDateTime) :
	RuntimeException("$holder is editing this block. It frees itself in a moment.")
