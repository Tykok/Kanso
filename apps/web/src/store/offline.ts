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
      answered: isAnswered,
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
      // Online if anything was answered at all — sent or refused. A refusal is proof
      // the server is reachable; only `unreachable` says otherwise.
      if (result.sent > 0 || result.rejected > 0) set({ online: true });
      else if (result.unreachable > 0) set({ online: false });
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
 * What a mutation that holds its own writes has to say to React Query.
 *
 * With the default `networkMode`, a mutation started while `navigator.onLine` is false is
 * *paused*: `onMutate` paints its guess, `mutationFn` is never called, and the write
 * waits in memory until the network comes back. That is a queue — an invisible one, with
 * no order a reader can see and no disk under it, so a reload loses every write in it.
 * Kanso already has the other kind, which is what `queue.ts` and the banner are.
 *
 * `always` is therefore not "ignore the network": it is "let the request fail, so
 * [withOfflineFallback] can catch it and put the write somewhere that survives". Every
 * mutation spread with this must go through that function, or it goes back to losing
 * writes offline — which is why the two live in one file.
 *
 * It is also why `KAN-24`'s queue held nothing but a notification marked read: the writes
 * it was built for never reached it. `KAN-88`.
 */
export const RUNS_OFFLINE = { networkMode: "always" } as const;

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

/**
 * True when the server answered — even to refuse.
 *
 * A duck-typed check on `status` rather than `error instanceof ApiError`. Two reasons,
 * and the second is the real one: `lib/api/core.ts` and `lib/api/inbox.ts` each throw
 * their own construction of the class today, and a queued write is replayed by whichever
 * of them is loaded weeks later — a prototype identity is the wrong thing for that to
 * hinge on. The field is the contract.
 */
export function isAnswered(error: unknown): boolean {
  return typeof (error as { status?: unknown } | null)?.status === "number";
}
