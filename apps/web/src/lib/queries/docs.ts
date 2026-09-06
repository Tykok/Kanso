"use client";

import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { docsApi, type DocBlockContent, type DocBlockKind } from "../api";

/**
 * Hooks for screens 07 and 22.
 *
 * Every key starts with `"docs"`, the way `core.ts`'s do with their own entity: one
 * `invalidateQueries({ queryKey: ["docs"] })` reaches every variant, and the day the
 * realtime bus carries a document event the receiver needs no new knowledge to apply it.
 */
export const docKeys = {
  templates: ["docs", "templates"] as const,
  folders: (teamId?: string) => ["docs", "folders", teamId ?? ""] as const,
  pages: (teamId?: string) => ["docs", "pages", teamId ?? ""] as const,
  /** One page with its blocks. Its own entry, so a block edit repaints one document. */
  page: (id: string) => ["docs", "page", id] as const,
  /**
   * Who has one page open. Its own entry beside [page] and not part of it, because the
   * two change at completely different rates and for unrelated reasons: somebody walking
   * into a document must not cost everybody else a refetch of its blocks.
   */
  viewers: (id: string) => ["docs", "viewers", id] as const,
};

// --- reads -------------------------------------------------------------------

export const useDocFolders = (teamId?: string) =>
  useQuery({ queryKey: docKeys.folders(teamId), queryFn: () => docsApi.folders(teamId) });

/**
 * `enabled` defaults true, so the two screens that list the tree read as they always did.
 * It exists for the command palette, which mounts this hook on every `⌘K` and searches
 * only once somebody types: without the flag, opening the palette and closing it again
 * paid for a request nobody read. `useDocs` and `useSearchableTickets` next door already
 * take the same argument for the same reason — the palette gates all three or none, and
 * one ungated hook is enough to make the gate a decoration.
 */
export const useDocPages = (teamId?: string, enabled = true) =>
  useQuery({
    queryKey: docKeys.pages(teamId),
    queryFn: () => docsApi.pages({ teamId }),
    enabled,
  });

export const useDocPage = (id: string) =>
  useQuery({ queryKey: docKeys.page(id), queryFn: () => docsApi.page(id), enabled: id !== "" });

/** Three seeded rows that never change. Cached for the session rather than refetched. */
export const useDocTemplates = () =>
  useQuery({ queryKey: docKeys.templates, queryFn: docsApi.templates, staleTime: Infinity });

/**
 * Who else has this page open — `KAN-25`.
 *
 * Read **once on mount**, and then only when a `doc_viewers` event says the roster moved.
 * Not polled: the whole point of presence living on the socket rather than in a table is
 * that nothing has to ask. `staleTime: Infinity` is what says so — a refocus or a remount
 * must not go and re-read something the bus is already keeping current, and a poll here
 * would be a heartbeat wearing a different hat.
 *
 * The one read exists because a newly subscribed client cannot rely on the broadcast it
 * caused: `SessionSubscribeEvent` and the broker's handling of the SUBSCRIBE frame both
 * travel the inbound channel and their order is not guaranteed. `DocumentController.viewers`
 * carries that argument.
 */
export const useDocViewers = (pageId: string) =>
  useQuery({
    queryKey: docKeys.viewers(pageId),
    queryFn: () => docsApi.viewers(pageId),
    enabled: pageId !== "",
    staleTime: Infinity,
  });

// --- writes ------------------------------------------------------------------

/**
 * What every write here invalidates.
 *
 * The tree and the "recently changed" list both move on any write — a new page appears
 * in both, and *any* edit reorders the second, because it is ordered on the page's own
 * `updatedAt`. So the whole `docs` prefix goes rather than a hand-picked subset that
 * would be wrong the first time a screen reads something new.
 */
const invalidateDocs = (client: QueryClient) => client.invalidateQueries({ queryKey: ["docs"] });

export function useCreateDocFolder() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: docsApi.createFolder,
    onSettled: () => invalidateDocs(client),
  });
}

export function useRenameDocFolder() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) => docsApi.renameFolder(id, name),
    onSettled: () => invalidateDocs(client),
  });
}

export function useDeleteDocFolder() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: docsApi.deleteFolder,
    onSettled: () => invalidateDocs(client),
  });
}

export function useCreateDocPage() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: docsApi.createPage,
    onSettled: () => invalidateDocs(client),
  });
}

export function usePatchDocPage() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...input }: { id: string; title?: string; folderId?: string; unset?: string[] }) =>
      docsApi.patchPage(id, input),
    onSettled: () => invalidateDocs(client),
  });
}

export function useDeleteDocPage() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: docsApi.deletePage,
    onSettled: () => invalidateDocs(client),
  });
}

/**
 * The five writes a page's own screen makes, sharing one invalidation.
 *
 * One hook rather than five because they are one gesture set — typing in a document —
 * and because each of the five is a single call: five exported hooks would be five
 * copies of the same two lines around it. The page reads `isPending` off whichever it
 * called, which is what a block editor actually asks.
 */
export function useDocBlockWrites(pageId: string) {
  const client = useQueryClient();
  const settle = () => invalidateDocs(client);

  const add = useMutation({
    mutationFn: (input: { kind: DocBlockKind; content: DocBlockContent; afterBlockId?: string }) =>
      docsApi.addBlock(pageId, input),
    onSettled: settle,
  });

  const patch = useMutation({
    mutationFn: ({ id, content }: { id: string; content: DocBlockContent }) =>
      docsApi.patchBlock(id, content),
    onSettled: settle,
  });

  const move = useMutation({
    mutationFn: ({ id, toIndex }: { id: string; toIndex: number }) => docsApi.moveBlock(id, toIndex),
    onSettled: settle,
  });

  const remove = useMutation({ mutationFn: docsApi.deleteBlock, onSettled: settle });

  const link = useMutation({
    mutationFn: ({ ticketId, afterBlockId }: { ticketId: string; afterBlockId?: string }) =>
      docsApi.linkTicket(pageId, ticketId, afterBlockId),
    onSettled: settle,
  });

  /**
   * `c`. Invalidates the tickets and the timeline as well as the document: the ticket
   * this creates is a real ticket and belongs in every list that was already showing
   * its team's work.
   */
  const createTicket = useMutation({
    mutationFn: (title: string) => docsApi.createLinkedTicket(pageId, title),
    onSettled: () => {
      settle();
      client.invalidateQueries({ queryKey: ["tickets"] });
      client.invalidateQueries({ queryKey: ["timeline"] });
    },
  });

  return { add, patch, move, remove, link, createTicket };
}
