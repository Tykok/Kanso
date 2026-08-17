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
};

// --- reads -------------------------------------------------------------------

export const useDocFolders = (teamId?: string) =>
  useQuery({ queryKey: docKeys.folders(teamId), queryFn: () => docsApi.folders(teamId) });

export const useDocPages = (teamId?: string) =>
  useQuery({ queryKey: docKeys.pages(teamId), queryFn: () => docsApi.pages({ teamId }) });

export const useDocPage = (id: string) =>
  useQuery({ queryKey: docKeys.page(id), queryFn: () => docsApi.page(id), enabled: id !== "" });

/** Three seeded rows that never change. Cached for the session rather than refetched. */
export const useDocTemplates = () =>
  useQuery({ queryKey: docKeys.templates, queryFn: docsApi.templates, staleTime: Infinity });

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
