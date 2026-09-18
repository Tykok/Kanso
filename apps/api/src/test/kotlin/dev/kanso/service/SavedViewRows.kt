package dev.kanso.service

import dev.kanso.domain.User
import java.util.UUID

/**
 * A saved view's rows, flat — what four test classes assert filter semantics against.
 *
 * There used to be a `SavedViewService.tickets(id)` answering `GET /views/{id}/tickets`,
 * and it is gone: the web app moved to the stacked answer, nothing called the flat route
 * afterwards, and a second door onto one question is the door that drifts. Nothing in
 * production wanted a flat list of a view's rows — only these tests did, and a method kept
 * alive for its tests is a method nobody can safely change.
 *
 * Concatenating the buckets gives back exactly the page the flat call did. The page is
 * ordered by bucket before it is ordered by the view's own `sortBy`, so laying the buckets
 * end to end is a partition of that one page and not a re-sort of it — which is the same
 * property `TicketGrouping` relies on to lay its counts over its rows.
 *
 * An extension in the test source set rather than four private helpers: the identity above
 * is one claim, and four copies of it are four places to stop agreeing.
 */
fun SavedViewService.rows(actor: User, id: UUID, limit: Int = 200): List<TicketDetail> =
	grouped(actor, id, limit).flatMap { it.tickets }
