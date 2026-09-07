import { onlineManager, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, type Ticket } from "@/lib/api";
import { useOffline } from "@/store/offline";
import { keys, usePatchTicket } from "./core";

/**
 * A status change made with no network — `KAN-88`.
 *
 * The phrasing and the settle rule are covered as pure functions in
 * `lib/offline-write.test.ts`, and the ledger's side in `lib/optimistic.test.ts`. What
 * neither can prove is that the hook wires them together: that a `fetch` which never
 * arrived reaches the queue instead of being lost, and that the row stays where the
 * reader put it. This repository has shipped a rule computed correctly and rendered
 * nowhere before; the same gap sits between a queue that works and a mutation that
 * uses it.
 */

const LIST = keys.tickets({ kind: "all" }, false);

const ticket = (overrides: Partial<Ticket> = {}): Ticket => ({
  id: "t1",
  identifier: "KAN-142",
  number: 142,
  teamId: "team-a",
  title: "The board jumps on every status key",
  status: "todo",
  priority: "none",
  assigneeIds: [],
  docIds: [],
  pullRequests: [],
  archived: false,
  customFields: {},
  mirror: { state: "pending" },
  createdAt: "2026-09-01T10:00:00Z",
  updatedAt: "2026-09-01T10:00:00Z",
  ...overrides,
});

function harness() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  client.setQueryData(LIST, [ticket()]);

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );

  return { client, ...renderHook(() => usePatchTicket(), { wrapper }) };
}

const row = (client: QueryClient) => client.getQueryData<Ticket[]>(LIST)?.[0];

/** `fetch` rejecting with no `status`: the request never reached a server. */
const noNetwork = () => Promise.reject(new TypeError("Failed to fetch"));

describe("a ticket patched while the network is gone", () => {
  beforeEach(async () => {
    // The queue is one per device and this store is a module global, so a test that
    // left writes in it would be read by the next one.
    for (const write of useOffline.getState().writes) {
      await useOffline.getState().discard(write.id);
    }
  });

  afterEach(() => {
    vi.restoreAllMocks();
    onlineManager.setOnline(true);
  });

  it("keeps the row where the reader put it", async () => {
    vi.spyOn(api, "patchTicket").mockImplementation(noNetwork);
    const { client, result } = harness();

    await act(async () => {
      await result.current.mutateAsync({ id: "t1", status: "done" });
    });

    // Not `todo`. A queued write is not a refused one — the row is going to be saved.
    expect(row(client)?.status).toBe("done");
  });

  it("holds the write on disk, in the words the banner prints", async () => {
    vi.spyOn(api, "patchTicket").mockImplementation(noNetwork);
    const { result } = harness();

    await act(async () => {
      await result.current.mutateAsync({ id: "t1", status: "in_review" });
    });

    await waitFor(() => expect(useOffline.getState().writes).toHaveLength(1));
    const [held] = useOffline.getState().writes;
    expect(held.reference).toBe("KAN-142");
    expect(held.summary).toBe("status → In review");
    expect(held.request).toEqual({
      path: "/api/tickets/t1",
      method: "PATCH",
      body: { status: "in_review" },
    });
  });

  it("asks for nothing fresh, since nothing was written and the ask cannot land", async () => {
    vi.spyOn(api, "patchTicket").mockImplementation(noNetwork);
    const { client, result } = harness();
    const invalidate = vi.spyOn(client, "invalidateQueries");

    await act(async () => {
      await result.current.mutateAsync({ id: "t1", status: "done" });
    });

    // The server has not moved, so there is nothing fresher to fetch — and the fetch
    // would fail anyway. What the reconnect owes the stale numbers is a sweep of the
    // whole cache, which `resumeAfterOutage` already does.
    expect(invalidate).not.toHaveBeenCalled();
  });

  it("queues nothing when the server answered, even to refuse", async () => {
    vi.spyOn(api, "patchTicket").mockRejectedValue({ status: 403, message: "read-only seat" });
    const { client, result } = harness();

    await act(async () => {
      await result.current.mutateAsync({ id: "t1", status: "done" }).catch(() => undefined);
    });

    // A refusal replayed later would be refused again, forever. It rolls back instead.
    expect(useOffline.getState().writes).toEqual([]);
    expect(row(client)?.status).toBe("todo");
  });

  it("keeps the saved row when the request did get through", async () => {
    vi.spyOn(api, "patchTicket").mockResolvedValue(ticket({ status: "done", title: "renamed" }));
    const { client, result } = harness();

    await act(async () => {
      await result.current.mutateAsync({ id: "t1", status: "done" });
    });

    expect(useOffline.getState().writes).toEqual([]);
    expect(row(client)?.title).toBe("renamed");
  });

  /**
   * React Query's own answer to being offline, which is the wrong one here.
   *
   * With the default `networkMode`, a mutation started while `navigator.onLine` is false
   * is *paused*: `onMutate` paints the guess, `mutationFn` is never called, and the write
   * waits in memory until the network returns. Kanso has a queue for that, on disk, with
   * an order and a banner — and the paused write has none of it: nothing on screen says
   * it exists, and a reload loses it, which is the one thing `queue.ts` was written to
   * prevent. So this mutation asks to be run anyway and lets the request fail.
   */
  it("is queued even when the browser says the network is gone", async () => {
    vi.spyOn(api, "patchTicket").mockImplementation(noNetwork);
    onlineManager.setOnline(false);
    const { result } = harness();

    await act(async () => {
      await result.current.mutateAsync({ id: "t1", status: "done" });
    });

    await waitFor(() => expect(useOffline.getState().writes).toHaveLength(1));
    expect(useOffline.getState().writes[0].summary).toBe("status → Done");
  });
});
