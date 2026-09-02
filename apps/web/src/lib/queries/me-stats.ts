"use client";

import { useQuery } from "@tanstack/react-query";
import { meStatsApi } from "@/lib/api";

/**
 * `/me`'s numbers — one key, because there is one request.
 *
 * A family of its own rather than a sixth segment under `tickets`. It would be tempting:
 * every mutation in the app already invalidates `["tickets"]`, so hiding under it would
 * buy refetch-on-write for free. But `core.ts`'s optimistic writer walks *every* cache
 * entry in that family and paints a guessed ticket page into it, so an entry holding a
 * `MyStats` would be overwritten with a shape it is not. The gap that leaves is stated
 * below rather than papered over.
 *
 * The first segment is what an invalidation reaches for, the way `applyEvents` in
 * `lib/realtime-events.ts` finds the other families. Six mutations now name it: `patch`,
 * `create` and `delete` in `queries/core.ts`, both ends of a dependency edge there — the
 * only edits that move `strip.blocked` without touching a status — and
 * `invalidateOrganise`, which covers triage rulings, bulk edits and cycle moves. The gap
 * the paragraph above describes is therefore stated *and* closed: the family stays out of
 * `tickets` so the optimistic writer cannot paint a ticket page into it, and the writes
 * that change these numbers say its name instead.
 *
 * `useUnarchive` deliberately does not. Archiving a team or a project is not known to move
 * any of these counts, and an invalidation added on a hunch is a request per click that
 * nobody can later argue for or against.
 */
export const meStatsKeys = { all: ["myStats"] as const };

/**
 * The strip, the bars, the commitment and the unsized count, in one query.
 *
 * `staleTime` rather than a `refetchInterval`: none of these numbers move without
 * somebody's keystroke, so polling would ask a question nothing has answered differently.
 * A minute is long enough that flipping between the five tabs — which are `?tab=`
 * navigations on one screen — costs one request instead of five, and short enough that
 * coming back to the page after closing a ticket elsewhere shows the closed ticket.
 */
export const useMyStats = () =>
  useQuery({
    queryKey: meStatsKeys.all,
    queryFn: meStatsApi.stats,
    staleTime: 60_000,
  });
