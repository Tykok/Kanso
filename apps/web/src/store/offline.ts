"use client";

import { create } from "zustand";
import { offlineStore } from "@/components/offline/idb";
import { OfflineQueue, type QueuedWrite } from "@/components/offline/queue";
import { getDevUser, sendRaw } from "@/lib/api";

/**
 * The queue, and whether the network is there.
 *
 * Its own store rather than a widening of `store/ui.ts`: `ui.ts` is closed for the
 * fan-out, and this is not interface state anyway — the writes in it are the reader's
 * work, held on disk, and they outlive every overlay and every reload.
 */

/**
 * One queue per device, built on first use.
 *
 * Lazily, because `offlineStore()` touches `indexedDB` and this module is imported
 * during a Next server render where there is none. The actor is the dev-mode identity
 * when there is one: one browser can be several people, and their chains must not
 * interleave — that is the whole reason `QueuedWrite` carries an actor.
 */
let queue: OfflineQueue | undefined;
let durableDisk = true;

function theQueue(): OfflineQueue {
  if (!queue) {
    const { store, durable } = offlineStore();
    durableDisk = durable;
    queue = new OfflineQueue(store, {
      actor: getDevUser() ?? "me",
      send: (write) => sendRaw(write.request.path, write.request.method, write.request.body).then(() => undefined),
    });
  }
  return queue;
}

type OfflineState = {
  /**
   * Optimistic on the server, where there is no browser to ask, and optimistic in a
   * browser too: `navigator.onLine` is false only when the OS is certain, so the
   * honest signal that the network is gone is a request that failed, not this flag.
   */
  online: boolean;
  writes: QueuedWrite[];
  /** False when the browser would not give us IndexedDB — the banner says so. */
  durable: boolean;

  setOnline: (online: boolean) => void;
  refresh: () => Promise<void>;
  /** Records a write the network refused to carry. Returns it, for the banner. */
  hold: (input: {
    reference: string;
    summary: string;
    request: QueuedWrite["request"];
  }) => Promise<void>;
  flush: () => Promise<void>;
  retry: (id: string) => Promise<void>;
  discard: (id: string) => Promise<void>;
};

export const useOffline = create<OfflineState>((set) => {
  const reload = async () => {
    const writes = await theQueue().list();
    set({ writes, durable: durableDisk });
  };

  return {
    online: true,
    writes: [],
    durable: true,

    setOnline: (online) => set({ online }),
    refresh: reload,

    hold: async (input) => {
      await theQueue().enqueue(input);
      set({ online: false });
      await reload();
    },

    flush: async () => {
      const result = await theQueue().flush();
      // Back online only if something actually went through. A flush that refused
      // everything is not evidence the network returned.
      if (result.sent > 0) set({ online: true });
      await reload();
    },

    retry: async (id) => {
      await theQueue().retry(id);
      await reload();
    },

    discard: async (id) => {
      await theQueue().discard(id);
      await reload();
    },
  };
});

/**
 * Runs [attempt], and holds the write instead of losing it when the network is what
 * failed.
 *
 * Only a *transport* failure is queued. A 4xx is the server having read the request and
 * refused it, and replaying that later would refuse it again forever; `fetch` rejecting
 * with a `TypeError` is the request never having arrived, which is the only case a
 * queue can help with. `ApiError` is what `lib/api` throws for every answered request,
 * so "not an ApiError" is exactly "never answered".
 */
export async function withOfflineFallback<T>(
  attempt: () => Promise<T>,
  held: { reference: string; summary: string; request: QueuedWrite["request"] },
): Promise<T | undefined> {
  try {
    const answer = await attempt();
    useOffline.setState({ online: true });
    return answer;
  } catch (error) {
    if (isAnswered(error)) throw error;
    await useOffline.getState().hold(held);
    return undefined;
  }
}

/** True when the server answered — even to refuse. See [withOfflineFallback]. */
function isAnswered(error: unknown): boolean {
  return typeof (error as { status?: unknown } | null)?.status === "number";
}
