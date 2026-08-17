"use client";

import { useQuery } from "@tanstack/react-query";
import type { Scope } from "@/store/ui";
import { api, type Preferences } from "../api";
import {
  parseTicketKey,
  viewsApi,
  type ActivityRow,
  type OpenTicketMode,
} from "../api/views";
import { keys, useSavePreferences } from "./core";

/**
 * The hooks slice A's four screens read. Nothing here writes: the ticket page, the
 * board, the project page and the search draw rows other surfaces already create, and
 * the two mutations the board needs — a status patch and nothing else — are
 * `usePatchTicket`, which already exists.
 *
 * Every ticket key below begins with `"tickets"` on purpose. `applyEvent` invalidates on
 * that first segment alone, so a realtime edit refreshes the page-width ticket and the
 * board's cards without this file having to be known to the socket.
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
  return useQuery({
    queryKey: ["tickets", "by-key", parsed?.teamKey ?? key, parsed?.number ?? 0] as const,
    queryFn: () => viewsApi.ticketByKey(parsed!),
    enabled: parsed !== null,
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
 * An entity's feed.
 *
 * `GET /api/activity` is slice 0's endpoint and is not on slice A's branch, so this
 * query has to fail gracefully rather than loudly: `retry: false` so a 404 costs one
 * request, and the caller draws nothing rather than an error — a project page whose
 * feed is missing is still a project page, and a red strip about an endpoint the
 * reader did not ask for teaches them nothing.
 */
export function useActivity(entityType: ActivityRow["entityType"], entityId: string) {
  return useQuery({
    queryKey: ["activity", entityType, entityId] as const,
    queryFn: () => viewsApi.activity(entityType, entityId),
    retry: false,
  });
}

/**
 * Saves how `↵` opens a ticket.
 *
 * `useSavePreferences` unchanged — the optimistic write, the rollback and the
 * "no Save button" rule are all already right for this field. The cast is the whole
 * of the seam: `open_ticket` is slice 0's column, so `Preferences` does not carry the
 * key yet and `savePreferences`'s parameter type cannot name it. Until the column
 * exists the server answers with a preferences row that has no `openTicket`, and the
 * control visibly reverts — which is the honest signal that nothing was stored, the
 * same signal a failed theme change already gives.
 */
export function useSaveOpenTicket() {
  const save = useSavePreferences();
  return {
    ...save,
    setOpenTicket: (openTicket: OpenTicketMode) =>
      save.mutate({ openTicket } as unknown as Partial<Preferences>),
  };
}
