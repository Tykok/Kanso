"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { publicApi, type Roadmap } from "@/lib/api/publik";

/**
 * The public surfaces' own hooks.
 *
 * Keyed under `public` rather than sharing `keys.tickets`: `applyEvent` invalidates on
 * the first segment when a realtime event arrives, and these pages have no session, so
 * no event will ever reach them. Sharing a prefix would let a signed-in tab's
 * invalidation refetch a stranger's page for no reason, and would make the roadmap look
 * like it is part of the application's cache when it is a different read model entirely.
 */
export const publicKeys = {
  roadmap: ["public", "roadmap"] as const,
  ticket: (key: string) => ["public", "roadmap", key] as const,
};

export const useRoadmap = () =>
  useQuery({ queryKey: publicKeys.roadmap, queryFn: publicApi.roadmap });

export const useContributorPage = (key: string) =>
  useQuery({
    queryKey: publicKeys.ticket(key),
    queryFn: () => publicApi.contributorPage(key),
    // A key typed into the address bar that resolves to nothing is not a transient
    // failure, and retrying a 404 only delays the message that says so.
    retry: false,
  });

/**
 * Voting, with the new count written straight into whichever caches hold that ticket.
 *
 * The server's answer is the total, so there is nothing to guess and no optimistic
 * update to roll back: the control is drawn pressed the moment the count comes back.
 * Which tickets *this* visitor has voted on is remembered in the browser (see
 * `components/publik/voted.ts`) rather than asked for — the server keys a vote to a
 * day-scoped hash it cannot hand back, and asking would mean hashing an address on a
 * GET to answer a question the browser already knows.
 */
export function useVote() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (key: string) => publicApi.vote(key),
    onSuccess: ({ votes }, key) => {
      queryClient.setQueryData<Roadmap>(publicKeys.roadmap, (roadmap) =>
        roadmap && {
          groups: roadmap.groups.map((group) => ({
            ...group,
            tickets: group.tickets.map((t) => (t.key === key ? { ...t, votes } : t)),
          })),
        },
      );
      void queryClient.invalidateQueries({ queryKey: publicKeys.ticket(key) });
    },
  });
}
