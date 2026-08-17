import type { QueuedWrite, QueueStore } from "./queue";

/**
 * The disk, for real.
 *
 * Nothing but IndexedDB plumbing lives here: the queue's ordering, its chains and what
 * becomes of a refusal are all in `queue.ts`, behind the [QueueStore] interface this
 * file implements. That split is what lets the part that can be quietly wrong be
 * tested in `environment: "node"` — a browser is needed to exercise this file, and
 * there is deliberately nothing in it worth exercising.
 *
 * `localStorage` was the other option and is the wrong one: it is synchronous, so every
 * read blocks the main thread, and it caps at a few megabytes of *string*, which a
 * document's queued block edits would reach.
 */

const DB_NAME = "kanso-offline";
const STORE_NAME = "writes";
const VERSION = 1;

function request<T>(operation: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    operation.onsuccess = () => resolve(operation.result);
    operation.onerror = () => reject(operation.error ?? new Error("IndexedDB refused the request"));
  });
}

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const opening = indexedDB.open(DB_NAME, VERSION);
    opening.onupgradeneeded = () => {
      // Keyed on `id`, not on `seq`: `seq` decides the order and is not the identity,
      // and a store keyed on it could not hold two writes made in the same millisecond
      // if the counter ever became a timestamp.
      if (!opening.result.objectStoreNames.contains(STORE_NAME)) {
        opening.result.createObjectStore(STORE_NAME, { keyPath: "id" });
      }
    };
    opening.onsuccess = () => resolve(opening.result);
    opening.onerror = () => reject(opening.error ?? new Error("IndexedDB is unavailable"));
  });
}

async function transact<T>(
  mode: IDBTransactionMode,
  run: (store: IDBObjectStore) => Promise<T>,
): Promise<T> {
  const db = await open();
  try {
    return await run(db.transaction(STORE_NAME, mode).objectStore(STORE_NAME));
  } finally {
    db.close();
  }
}

export function indexedDbStore(): QueueStore {
  return {
    all: () => transact("readonly", (store) => request<QueuedWrite[]>(store.getAll())),
    put: (write) => transact("readwrite", (store) => request(store.put(write)).then(() => undefined)),
    remove: (id) => transact("readwrite", (store) => request(store.delete(id)).then(() => undefined)),
  };
}

/**
 * A disk that forgets, for a browser that will not give us one — private mode in some
 * versions of Safari, a locked-down profile, and the server during a Next render.
 *
 * The queue then behaves exactly as it did before it was written to disk, which is bad
 * and is why it is not the default. It is not silently bad: [offlineStore] says which
 * one it got, and the banner tells the reader their queue will not survive a reload
 * rather than implying a guarantee this store cannot keep.
 */
export function memoryStore(): QueueStore {
  const rows = new Map<string, QueuedWrite>();
  return {
    all: async () => Array.from(rows.values()),
    put: async (write) => void rows.set(write.id, write),
    remove: async (id) => void rows.delete(id),
  };
}

/** The best disk this environment has, and whether it is the durable one. */
export function offlineStore(): { store: QueueStore; durable: boolean } {
  if (typeof indexedDB === "undefined") return { store: memoryStore(), durable: false };
  return { store: indexedDbStore(), durable: true };
}
