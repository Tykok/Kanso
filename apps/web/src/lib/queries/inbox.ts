"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  inboxApi,
  notionImportApi,
  type Inbox,
  type InboxTab,
  type NotionImportPlanRow,
} from "@/lib/api";
import { withOfflineFallback } from "@/store/offline";

/**
 * Slice D's hooks. Keyed under `inbox` so one `invalidateQueries({ queryKey: ["inbox"] })`
 * refreshes every tab at once — the counts in the strip belong to all four, and a
 * `⇧e` that left three of them stale would be showing four disagreeing numbers.
 */
export const inboxKeys = {
  all: ["inbox"] as const,
  tab: (tab: InboxTab) => ["inbox", tab] as const,
};

export const useInbox = (tab: InboxTab) =>
  useQuery({
    queryKey: inboxKeys.tab(tab),
    queryFn: () => inboxApi.inbox(tab),
    // The sidebar's badge and the failures tab both go stale on their own — a push
    // fails without anybody's keystroke — so this one asks again rather than waiting
    // for a navigation.
    refetchInterval: 60_000,
    /**
     * All four counts ride on whichever tab was asked for, so a switch with no
     * placeholder leaves the strip with no data at all and the page falls back to four
     * zeros — the first click on `Failures` blanks the very number it was clicking
     * towards, then fills it back in a moment later. Keeping the previous tab's counts
     * is the honest thing to show for the length of the request: they are the last true
     * answer this client had, and a zero is not a stale answer but a false one. The
     * alternative — a spinner or a dash in place of each count — costs the reader the
     * numbers as well, and buys nothing, because the counts are the same four numbers
     * whichever tab returns them.
     */
    placeholderData: keepPreviousData,
  });

/**
 * The unread count the sidebar row prints.
 *
 * Reads the `all` tab's cache entry, so it costs no second request on the inbox
 * screen and one cheap one everywhere else.
 */
export const useUnreadCount = (): number => {
  const inbox = useInbox("all");
  return inbox.data?.counts.unread ?? 0;
};

export function useMarkAllRead() {
  const client = useQueryClient();

  return useMutation({
    mutationFn: () =>
      withOfflineFallback(inboxApi.markAllRead, {
        reference: "Inbox",
        summary: "mark everything read",
        request: { path: "/api/notifications/read-all", method: "POST" },
      }),

    // Optimistic: `⇧e` marks a screenful read, and a strip of counts that waits for a
    // round trip to change reads as a key that did nothing.
    onMutate: async () => {
      await client.cancelQueries({ queryKey: inboxKeys.all });
      const previous = client.getQueriesData<Inbox>({ queryKey: inboxKeys.all });
      const now = new Date().toISOString();
      for (const [key, inbox] of previous) {
        if (!inbox) continue;
        client.setQueryData<Inbox>(key, {
          // A failed push has no id and is never marked read: the mirror is still
          // behind, and ticking it off would be the interface lying about the queue.
          rows: inbox.rows.map((row) => (row.id && !row.readAt ? { ...row, readAt: now } : row)),
          counts: { ...inbox.counts, unread: inbox.counts.failures },
        });
      }
      return { previous };
    },

    onError: (_error, _input, context) => {
      for (const [key, inbox] of context?.previous ?? []) client.setQueryData(key, inbox);
    },

    onSettled: () => client.invalidateQueries({ queryKey: inboxKeys.all }),
  });
}

export function useMarkRead() {
  const client = useQueryClient();

  return useMutation({
    mutationFn: (id: string) =>
      withOfflineFallback(() => inboxApi.markRead(id), {
        reference: "Inbox",
        summary: "mark one notification read",
        request: { path: `/api/notifications/${id}/read`, method: "POST" },
      }),
    onSettled: () => client.invalidateQueries({ queryKey: inboxKeys.all }),
  });
}

/**
 * `Retry` on a failure row.
 *
 * Requeues every failed push rather than the one named on the row: the admin endpoint
 * this branch is not free to widen takes no id, and one job's failure is almost never
 * alone — a locked page or a revoked token fails all of them at once.
 */
export function useRetryFailedPushes() {
  const client = useQueryClient();

  return useMutation({
    mutationFn: inboxApi.retryFailedPushes,
    onSuccess: () => {
      client.invalidateQueries({ queryKey: inboxKeys.all });
      client.invalidateQueries({ queryKey: ["sync"] });
      // The queue's own rows live under a second key since `KAN-53` split the answer, and
      // the button that empties the queue is the one place both have to be dropped: the
      // count would drop to zero beside a list still naming the rows it counted.
      client.invalidateQueries({ queryKey: ["syncDetail"] });
    },
  });
}

/**
 * One base's columns, and Kanso's guess at how to map them.
 *
 * Keyed by the base *and* its target, because the fields a base is asked about are the
 * target's — the same data source read as teams and read as tickets is two different
 * questions. Two steps use it: the third to make the mapping, the second to notice that a
 * mapped relation points at a base nobody is importing. One hook so they cannot ask for it
 * on different terms.
 *
 * `staleTime: Infinity` because a data source's schema does not change while a dialog is
 * open, and every miss is a call to Notion. `retry: false` for the same reason step 1 has
 * it: a workspace that refuses is a sentence to print, not something to ask again about.
 */
export const importSchemaQuery = (sourceId: string, target: NotionImportPlanRow["target"]) => ({
  queryKey: ["notion-import-schema", sourceId, target] as const,
  queryFn: () => notionImportApi.schema(sourceId, target),
  retry: false,
  staleTime: Infinity,
});

/**
 * The options as well as the hook, because step 3 asks for every kept base at once —
 * `useQueries`, since a hook cannot be called in a loop, and because that step has to know
 * whether *any* of them is still in flight before it lets the reader leave it. One
 * definition either way: the two steps cannot ask for the same schema on different terms.
 */
export const useImportSchema = (sourceId: string, target: NotionImportPlanRow["target"]) =>
  useQuery(importSchemaQuery(sourceId, target));
