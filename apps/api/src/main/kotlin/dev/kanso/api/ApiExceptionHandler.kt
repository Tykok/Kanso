package dev.kanso.api

import dev.kanso.service.BadRequestException
import dev.kanso.service.ConflictException
import dev.kanso.service.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * RFC 7807 responses, so the front can branch on `status` and show `detail` as-is.
 *
 * Extends Spring's own handler rather than replacing it: the framework's exceptions
 * already carry the right status (an unknown path is a 404, a wrong verb a 405), and
 * a bare catch-all would flatten every one of them into 500.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(NotFoundException::class)
	fun notFound(e: NotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, e.message)

	@ExceptionHandler(ConflictException::class)
	fun conflict(e: ConflictException): ProblemDetail = problem(HttpStatus.CONFLICT, e.message)

	@ExceptionHandler(BadRequestException::class)
	fun badRequest(e: BadRequestException): ProblemDetail = problem(HttpStatus.BAD_REQUEST, e.message)

	/** Thrown by the enum parsers when a status or priority is outside the vocabulary. */
	@ExceptionHandler(IllegalArgumentException::class)
	fun illegalArgument(e: IllegalArgumentException): ProblemDetail =
		problem(HttpStatus.BAD_REQUEST, e.message)

	@ExceptionHandler(DataIntegrityViolationException::class)
	fun integrity(e: DataIntegrityViolationException): ProblemDetail {
		log.warn("Constraint violation: {}", e.mostSpecificCause.message)
		return problem(HttpStatus.CONFLICT, "The change conflicts with a database constraint")
	}

	@ExceptionHandler(AccessDeniedException::class)
	fun accessDenied(e: AccessDeniedException): ProblemDetail =
		problem(HttpStatus.FORBIDDEN, e.message ?: "Forbidden")

	@ExceptionHandler(Exception::class)
	fun unexpected(e: Exception): ProblemDetail {
		log.error("Unhandled error", e)
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error")
	}

	/** Spring's default says only "invalid request content"; name the fields instead. */
	override fun handleMethodArgumentNotValid(
		ex: MethodArgumentNotValidException,
		headers: HttpHeaders,
		status: HttpStatusCode,
		request: WebRequest,
	): ResponseEntity<Any>? {
		val details = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
		return ResponseEntity.badRequest()
			.body(problem(HttpStatus.BAD_REQUEST, details.ifBlank { "Invalid request body" }))
	}

	private fun problem(status: HttpStatus, detail: String?): ProblemDetail =
		ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
}
