import { beforeEach, describe, expect, it } from "vitest";
import { OfflineQueue, type QueueStore, type QueuedWrite } from "./queue";

/**
 * A disk, in memory.
 *
 * The point of the store being an interface is that the queue's behaviour — order,
 * survival, what happens to a refusal — is testable without a browser: Vitest runs in
 * `environment: "node"`, and IndexedDB is not the part that can be quietly wrong.
 * `rows` outliving the queue instance is what stands in for a reload.
 */
function fakeStore(): QueueStore & { rows: Map<string, QueuedWrite> } {
  const rows = new Map<string, QueuedWrite>();
  return {
    rows,
    all: async () => Array.from(rows.values()).map((row) => ({ ...row })),
    put: async (write) => void rows.set(write.id, { ...write }),
    remove: async (id) => void rows.delete(id),
  };
}

type Attempt = { path: string; method: string };

/** Records what was sent, and refuses whatever `refuse` names. */
function recorder(refuse: (attempt: Attempt) => string | undefined = () => undefined) {
  const sent: string[] = [];
  return {
    sent,
    send: async (write: QueuedWrite) => {
      const attempt = { path: write.request.path, method: write.request.method };
      const rejection = refuse(attempt);
      if (rejection) throw new Error(rejection);
      sent.push(write.summary);
    },
  };
}

const patch = (id: string) => ({ path: `/api/tickets/${id}`, method: "PATCH" as const });

describe("the offline queue", () => {
  let store: ReturnType<typeof fakeStore>;

  beforeEach(() => {
    store = fakeStore();
  });

  it("survives being thrown away and rebuilt over the same disk", async () => {
    const first = new OfflineQueue(store, { send: recorder().send, actor: "lea" });
    await first.enqueue({ reference: "KAN-142", summary: "status → In review", request: patch("a") });
    await first.enqueue({ reference: "KAN-139", summary: "assignee → you", request: patch("b") });

    // Nothing is carried over but the store: a reload gets a new instance and no
    // in-memory state at all, which is the whole reason the disk is the queue.
    const reloaded = new OfflineQueue(store, { send: recorder().send, actor: "lea" });

    expect((await reloaded.list()).map((write) => write.summary)).toEqual([
      "status → In review",
      "assignee → you",
    ]);
  });

  it("keeps numbering where the previous session left off", async () => {
    const first = new OfflineQueue(store, { send: recorder().send, actor: "lea" });
    await first.enqueue({ reference: "KAN-142", summary: "one", request: patch("a") });
    await first.enqueue({ reference: "KAN-142", summary: "two", request: patch("a") });

    const reloaded = new OfflineQueue(store, { send: recorder().send, actor: "lea" });
    const third = await reloaded.enqueue({ reference: "KAN-142", summary: "three", request: patch("a") });

    // Restarting at 1 would put the third write ahead of the first two on the next
    // flush, which is the one thing "order is preserved" forbids.
    expect(third.seq).toBe(3);
    expect((await reloaded.list()).map((write) => write.summary)).toEqual(["one", "two", "three"]);
  });

  it("sends one actor's writes in the order they were made", async () => {
    const sink = recorder();
    const lea = new OfflineQueue(store, { send: sink.send, actor: "lea" });
    const rey = new OfflineQueue(store, { send: sink.send, actor: "rey" });

    // Interleaved on purpose: the guarantee is per actor, so a global send order that
    // happened to be right would not prove anything.
    await lea.enqueue({ reference: "KAN-142", summary: "lea 1", request: patch("a") });
    await rey.enqueue({ reference: "KAN-9", summary: "rey 1", request: patch("z") });
    await lea.enqueue({ reference: "KAN-142", summary: "lea 2", request: patch("a") });
    await lea.enqueue({ reference: "KAN-142", summary: "lea 3", request: patch("a") });

    await lea.flush();

    expect(sink.sent.filter((summary) => summary.startsWith("lea"))).toEqual([
      "lea 1",
      "lea 2",
      "lea 3",
    ]);
    expect(await lea.list()).toEqual([]);
  });

  it("keeps a rejected write, with the reason, instead of dropping it", async () => {
    const sink = recorder((attempt) => (attempt.path.endsWith("/b") ? "The ticket is gone" : undefined));
    const queue = new OfflineQueue(store, { send: sink.send, actor: "lea" });
    await queue.enqueue({ reference: "KAN-142", summary: "kept", request: patch("b") });

    const result = await queue.flush();

    expect(result).toEqual({ sent: 0, rejected: 1, unreachable: 0, remaining: 1 });
    const [write] = await queue.list();
    expect(write.state).toBe("rejected");
    expect(write.error).toBe("The ticket is gone");
    // And still on disk, so a reload does not make the refusal disappear either.
    expect(store.rows.size).toBe(1);
  });

  it("stops the rejected actor's chain and leaves another actor's alone", async () => {
    const sink = recorder((attempt) => (attempt.path.endsWith("/a") ? "Refused" : undefined));
    const lea = new OfflineQueue(store, { send: sink.send, actor: "lea" });
    const rey = new OfflineQueue(store, { send: sink.send, actor: "rey" });

    await lea.enqueue({ reference: "KAN-142", summary: "lea 1", request: patch("a") });
    await lea.enqueue({ reference: "KAN-142", summary: "lea 2", request: patch("z") });
    await rey.enqueue({ reference: "KAN-9", summary: "rey 1", request: patch("z") });

    const result = await lea.flush();

    // `lea 2` is never attempted: it was written against a row the refused write was
    // supposed to have produced, so sending it would apply a change to a state that
    // never happened. Rey's writes are a different chain and go.
    expect(sink.sent).toEqual(["rey 1"]);
    expect(result).toEqual({ sent: 1, rejected: 1, unreachable: 0, remaining: 2 });
    expect((await lea.list()).map((write) => [write.summary, write.state])).toEqual([
      ["lea 1", "rejected"],
      ["lea 2", "queued"],
    ]);
  });

  it("leaves a write the network never carried queued, not refused", async () => {
    // The distinction the banner leans on. A refusal needs a decision — retry it, or
    // discard it — and an outage needs nothing but a network, so an outage that marked
    // four writes "refused" would ask for four decisions nobody has to make.
    const sink = recorder(() => "Failed to fetch");
    const queue = new OfflineQueue(store, {
      send: sink.send,
      actor: "lea",
      answered: () => false,
    });
    await queue.enqueue({ reference: "KAN-142", summary: "waiting", request: patch("a") });

    const result = await queue.flush();

    expect(result).toEqual({ sent: 0, rejected: 0, unreachable: 1, remaining: 1 });
    const [write] = await queue.list();
    expect(write.state).toBe("queued");
    // And no error written onto it either: there is nothing to tell the reader that
    // "queued" does not already say.
    expect(write.error).toBeUndefined();
  });

  it("puts a rejected write back in line when it is retried", async () => {
    let refuse = true;
    const sink = recorder(() => (refuse ? "Offline" : undefined));
    const queue = new OfflineQueue(store, { send: sink.send, actor: "lea" });
    const write = await queue.enqueue({
      reference: "KAN-142",
      summary: "second time lucky",
      request: patch("a"),
    });

    await queue.flush();
    refuse = false;
    await queue.retry(write.id);

    expect((await queue.list())[0].state).toBe("queued");
    await queue.flush();
    expect(sink.sent).toEqual(["second time lucky"]);
    expect(await queue.list()).toEqual([]);
  });

  it("discards a write only when it is asked to, and says nothing was sent", async () => {
    const sink = recorder();
    const queue = new OfflineQueue(store, { send: sink.send, actor: "lea" });
    const write = await queue.enqueue({ reference: "KAN-142", summary: "no", request: patch("a") });

    await queue.discard(write.id);

    expect(await queue.list()).toEqual([]);
    expect(sink.sent).toEqual([]);
  });

  it("does not send the same write twice when two flushes overlap", async () => {
    const sink = recorder();
    const queue = new OfflineQueue(store, { send: sink.send, actor: "lea" });
    await queue.enqueue({ reference: "KAN-142", summary: "once", request: patch("a") });

    // The banner flushes on `online`, and a mutation flushes when it lands: both can
    // fire in the same tick, and a queue that sends twice is worse than one that waits.
    await Promise.all([queue.flush(), queue.flush()]);

    expect(sink.sent).toEqual(["once"]);
  });
});
