"use client";

import { useQuery } from "@tanstack/react-query";
import type { Scope } from "@/store/ui";
import { api } from "../api";
import { isTicketId, parseTicketKey, viewsApi, type OpenTicketMode } from "../api/views";
import { keys, useSavePreferences } from "./core";

/**
 * The hooks slice A's four screens read. Nothing here writes: the ticket page, the
 * board, the project page and the search draw rows other surfaces already create, and
 * the two mutations the board needs — a status patch and nothing else — are
 * `usePatchTicket`, which already exists.
 *
 * Every ticket key below begins with `"tickets"` on purpose. `applyEvents` finds them on
 * that first segment, so a realtime edit refreshes the page-width ticket and the board's
 * cards without this file having to be known to the socket. What it does with each one
 * depends on its shape: the two scoped lists are patched row by row, and the ticket
 * page's own entry — a single row keyed on the identifier — is replaced outright.
 */

/** The scope the search reads: everything, because a global search that is not is a filter. */
const EVERYTHING: Scope = { kind: "all" };

/**
 * One ticket, resolved from the identifier in the URL.
 *
 * `retry: false` because the interesting failure is a 404 — a link to a ticket that was
 * deleted, or a key nobody ever allocated — and retrying it only delays the sentence
 * that says so. Disabled outright for a key that is not one: `/t/nonsense` needs no
 * round trip to be answered.
 */
export function useTicketByKey(key: string) {
  const parsed = parseTicketKey(key);
  // The other address a ticket answers to. A ticket no team has claimed has no
  // identifier to put in a URL, so its id is the link — and the id keeps working
  // afterwards, which the identifier that did not exist yet could not have done.
  const byId = parsed === null && isTicketId(key);
  return useQuery({
    queryKey: ["tickets", "by-key", parsed?.teamKey ?? key, parsed?.number ?? 0] as const,
    queryFn: () => (parsed ? viewsApi.ticketByKey(parsed) : viewsApi.ticketById(key)),
    enabled: parsed !== null || byId,
    retry: false,
  });
}

/**
 * A project's tickets, whatever the sidebar happens to be scoped to.
 *
 * `keys.tickets` and `api.tickets` verbatim, so this is the *same* cache entry the list
 * fills when someone scopes to this project — the project page and the list share one
 * answer rather than each holding their own copy of it. Archived work is excluded: the
 * status counts are a picture of what is live, and `showArchived` is a list preference
 * that has no meaning on a summary.
 */
export function useProjectTickets(projectId: string) {
  const scope: Scope = { kind: "project", id: projectId };
  return useQuery({
    queryKey: keys.tickets(scope, false),
    queryFn: () => api.tickets(scope, false),
  });
}

/** Every ticket the actor may read — the palette searches across teams, not within one. */
export function useSearchableTickets(enabled: boolean) {
  return useQuery({
    queryKey: keys.tickets(EVERYTHING, false),
    queryFn: () => api.tickets(EVERYTHING, false),
    enabled,
  });
}

/** The Notion pages Kanso indexes. Searched by the palette, listed by a project. */
export function useDocs(enabled = true) {
  return useQuery({ queryKey: ["docs"] as const, queryFn: viewsApi.docs, enabled });
}

/**
 * The feed hook lives in `queries/social.ts`, beside the endpoint it reads. Slice A wrote
 * its own because `GET /api/activity` was not on its branch, and both spelled the same
 * query key — so keeping two would have been two hooks sharing one cache entry with
 * different retry rules. Re-exported here so slice A's own imports keep resolving.
 */
export { useActivity } from "./social";

/**
 * Saves how `↵` opens a ticket.
 *
 * `useSavePreferences` unchanged — the optimistic write, the rollback and the "no Save
 * button" rule are all already right for this field. The cast this carried is gone with
 * the seam that needed it: `Preferences` names `openTicket` now, so the mutation is typed
 * like every other preference rather than smuggled past it.
 */
export function useSaveOpenTicket() {
  const save = useSavePreferences();
  return {
    ...save,
    setOpenTicket: (openTicket: OpenTicketMode) => save.mutate({ openTicket }),
  };
}
