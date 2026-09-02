"use client";

import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import {
  api,
  organiseApi,
  filterParams,
  type Ticket,
  type TimelineView,
  type ViewFilters,
} from "@/lib/api";
import { keys, useMe } from "@/lib/queries";
import { isOpen, statusesWhere } from "@/lib/status-order";

/**
 * The one list three of `/me`'s five tabs are drawn from, and the graph the fourth needs.
 *
 * Assigned, Due and Blocked are three readings of the same rows — everything open with my
 * name on it, across every team — so they share one query and one cache entry rather than
 * asking three times. Which reading is on screen is a `?tab=` navigation and not a new
 * question, and three requests for one answer would also let the three tabs disagree
 * about the count in the strip above them.
 *
 * Not a hook in `lib/queries/`: those are the app's shared doors, and this is one screen's
 * composition of two of them. It lives beside the tabs that read it.
 */

/**
 * Still open, read off the category rather than named.
 *
 * The client half of `WorkloadService.OPEN_STATUSES`, which is what `MyStatsService`
 * counts `strip.open` over — so the list under the strip is the same set of rows the
 * strip counted. Naming the four statuses here instead would make the day a seventh open
 * status arrives the day this screen quietly stops listing part of somebody's plate,
 * which is the whole argument `lib/status-order.ts` exists to settle.
 */
const OPEN_STATUSES = statusesWhere(isOpen);

/**
 * How many rows one ask carries. `api.tickets`' own number, deliberately: the key below
 * is the key the main list uses for this same question, so the two must not be one entry
 * holding two different depths of answer.
 *
 * `strip.open` is counted over `WorkloadService.SCAN_LIMIT`, which is larger, so a plate
 * past this cap makes the list shorter than the number above it. The tab says so rather
 * than letting the two silently disagree — see `assigned-tab.tsx`.
 */
export const MY_OPEN_LIMIT = 200;

/**
 * Everything open with my name on it, in every team.
 *
 * Keyed exactly as the main list keys the same composed question — `keys.tickets`, scope
 * `all`, the same filter string — so this shares the ticket family the app already
 * invalidates and `lib/optimistic.ts` already paints guesses into. A family of its own
 * would have been a fourth list nothing refreshes.
 *
 * `enabled` on the session, because the filter *is* the session: a query fired before
 * `/api/me` answers would ask for everybody's open work and cache it under a key that
 * claims to be mine.
 */
export function useMyOpenTickets(): UseQueryResult<Ticket[]> {
  const me = useMe();
  const myId = me.data?.user.id;

  // Written in this order and not the store's, because `filterParams` walks the object's
  // own keys: the string is the cache key, so the field order here is what makes two asks
  // of the same question one entry.
  const filters: ViewFilters = { status: OPEN_STATUSES, assignee: myId ? [myId] : [] };
  const asked = filterParams(filters).toString();

  return useQuery({
    queryKey: keys.tickets({ kind: "all" }, false, asked),
    queryFn: () =>
      organiseApi.ticketsMatching(filters, {
        // Every team, and no `teamId`: counts and lists add across teams, which is the
        // same reason `/api/me/stats` takes no team either.
        includeArchived: false,
        limit: MY_OPEN_LIMIT,
      }),
    enabled: myId !== undefined,
  });
}

/**
 * The dependency graph, unscoped — the Blocked tab's only source for an arrow.
 *
 * There is no per-ticket dependency endpoint and no `blocked` facet on `/api/tickets`, so
 * the edges come from where the edges are: `components/views/ticket-page.tsx` already
 * reads "what this ticket waits on" off exactly this response and for exactly this
 * reason. Unscoped rather than scoped, because a personal home has no team selected and a
 * scoped read would answer "nothing is blocking you" about arrows it never looked at.
 *
 * `keys.timeline({ kind: "all" })` is the entry the ticket list already fills at that
 * scope, and every dependency write in `queries/core.ts` invalidates the family — so the
 * tab does not need a refresh rule of its own.
 *
 * No `enabled` flag, because mounting is the gate: `me-view.tsx` renders one tab at a
 * time, so the other four never call this — and this is the heaviest read on the screen
 * by some distance. A flag on top of that would be a second way to say the same thing.
 */
export function useDependencyGraph(): UseQueryResult<TimelineView> {
  return useQuery({
    queryKey: keys.timeline({ kind: "all" }),
    queryFn: () => api.timeline({ kind: "all" }),
  });
}
