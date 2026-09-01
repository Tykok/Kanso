"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { trashApi, type TrashKind } from "../api";

/**
 * Slice E — screen 26.
 *
 * One key for the whole screen: both tabs come from one response, because the drawing
 * shows both counts at once and two queries would let them describe two different
 * moments.
 */
export const trashKeys = { trash: ["trash"] as const };

export const useTrash = () =>
  useQuery({
    queryKey: trashKeys.trash,
    queryFn: trashApi.load,
    // The countdown is measured in whole days, so nothing here goes stale in a hurry —
    // but every exit changes the row set, which is what the mutations below invalidate.
    staleTime: 30_000,
  });

type Target = { kind: TrashKind; id: string };

/**
 * The three exits, as three mutations rather than one taking an action name: each
 * invalidates a different set, and a single hook would either over-invalidate or hide
 * which one did what.
 *
 * All three invalidate `["tickets"]` as well. `applyEvents` in `lib/realtime-events.ts`
 * cannot do it for them — it maps a realtime entity onto a key and knows nothing about
 * the trash — and a restored ticket has to reappear in the list of whoever restored it
 * without a reload.
 */
function useExit(run: (target: Target) => Promise<void>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSettled: () => {
      client.invalidateQueries({ queryKey: trashKeys.trash });
      client.invalidateQueries({ queryKey: ["tickets"] });
      client.invalidateQueries({ queryKey: ["timeline"] });
    },
  });
}

export const useRestoreFromTrash = () =>
  useExit(({ kind, id }) => trashApi.restore(kind, id));

export const useArchiveInstead = () =>
  useExit(({ kind, id }) => trashApi.archive(kind, id));

/** For good. The screen asks twice before calling this. */
export const usePurge = () => useExit(({ kind, id }) => trashApi.purge(kind, id));
