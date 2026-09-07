import { describe, expect, it } from "vitest";
import type { Label, Ticket } from "./api";
import {
  Guesses,
  patchedTicket,
  removedTicket,
  TicketGuesses,
  wornLabels,
} from "./optimistic";
import {
  applyEvents,
  resumeAfterOutage,
  type CacheEntry,
  type EventCache,
} from "./realtime-events";

/**
 * The same in-memory cache `realtime-events.test.ts` uses, and deliberately a second
 * copy of it: these tests assert the *other* half of the shared machinery, and a helper
 * imported across two test files would register one file's `describe` blocks inside the
 * other's.
 */
function fakeCache(seed: CacheEntry[] = []) {
  const rows = new Map(seed.map((entry) => [JSON.stringify(entry.key), entry]));
  const invalidated: string[] = [];
  const writes: string[] = [];
  let swept = 0;

  const cache: EventCache = {
    entries: (segment) => [...rows.values()].filter((entry) => entry.key[0] === segment),
    set: (key, data) => {
      writes.push(JSON.stringify(key));
      rows.set(JSON.stringify(key), { key, data });
    },
    invalidate: (key) => void invalidated.push(JSON.stringify(key)),
    invalidateAll: () => void (swept += 1),
  };

  return {
    cache,
    invalidated,
    writes,
    read: <T>(key: readonly unknown[]) => rows.get(JSON.stringify(key))?.data as T | undefined,
    was: (key: readonly unknown[]) => invalidated.includes(JSON.stringify(key)),
    /** How many times the whole cache was swept — what a reconnect does. */
    sweeps: () => swept,
  };
}

const listKey = (kind: "all" | "team" | "project", id: string, includeArchived = false) =>
  ["tickets", kind, id, includeArchived] as const;

const ticket = (id: string, overrides: Partial<Ticket> = {}): Ticket => ({
  id,
  identifier: `KAN-${id}`,
  number: 1,
  teamId: "team-a",
  title: id,
  status: "todo",
  priority: "none",
  assigneeIds: [],
  docIds: [],
  customFields: {},
  // Required on `Ticket` for the reason `customFields` beside it is: the server always
  // sends the key, so a factory that omits it is not a ticket the API can produce.
  pullRequests: [],
  archived: false,
  mirror: { state: "disabled" },
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  ...overrides,
});

const label = (id: string, name: string): Label => ({
  id,
  teamId: "team-a",
  name,
  colour: "indigo",
});

describe("a guess about one ticket", () => {
  it("shows before the request settles, in every cache entry that holds the row", () => {
    const pageKey = ["tickets", "by-key", "KAN", 142] as const;
    const store = fakeCache([
      { key: listKey("all", ""), data: [ticket("t1"), ticket("t2")] },
      { key: listKey("team", "team-a"), data: [ticket("t1")] },
      { key: pageKey, data: ticket("t1") },
    ]);
    const guesses = new TicketGuesses();

    guesses.open(store.cache, "t1", patchedTicket({ status: "done" }));

    expect(store.read<Ticket[]>(listKey("all", ""))?.[0]?.status).toBe("done");
    expect(store.read<Ticket[]>(listKey("team", "team-a"))?.[0]?.status).toBe("done");
    expect(store.read<Ticket>(pageKey)?.status).toBe("done");
    // The row it says nothing about is the same object it always was.
    expect(store.read<Ticket[]>(listKey("all", ""))?.[1]?.status).toBe("todo");
  });

  it("clears what `unset` names, the way the request will", () => {
    const store = fakeCache([
      { key: listKey("all", ""), data: [ticket("t1", { estimate: 5 })] },
    ]);
    const guesses = new TicketGuesses();

    guesses.open(store.cache, "t1", patchedTicket({}, ["estimate"]));

    expect(store.read<Ticket[]>(listKey("all", ""))?.[0]?.estimate).toBeUndefined();
  });

  it("says the mirror is behind, because the moment a row changes locally it is", () => {
    const store = fakeCache([
      { key: listKey("all", ""), data: [ticket("t1", { mirror: { state: "synced" } })] },
    ]);
    const guesses = new TicketGuesses();

    guesses.open(store.cache, "t1", patchedTicket({ title: "renamed" }));

    expect(store.read<Ticket[]>(listKey("all", ""))?.[0]?.mirror.state).toBe("pending");
  });

  it("leaves a disabled mirror disabled, which is not a thing a patch can start", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    guesses.open(store.cache, "t1", patchedTicket({ title: "renamed" }));

    expect(store.read<Ticket[]>(listKey("all", ""))?.[0]?.mirror.state).toBe("disabled");
  });

  it("takes an archived row out of the list that hides archived work, now", () => {
    const store = fakeCache([
      { key: listKey("all", "", false), data: [ticket("t1"), ticket("t2")] },
      { key: listKey("all", "", true), data: [ticket("t1"), ticket("t2")] },
    ]);
    const guesses = new TicketGuesses();

    guesses.open(store.cache, "t1", patchedTicket({ archived: true }));

    expect(store.read<Ticket[]>(listKey("all", "", false))?.map((row) => row.id)).toEqual(["t2"]);
    expect(store.read<Ticket[]>(listKey("all", "", true))).toHaveLength(2);
  });

  it("takes a deleted row out of every list and out of the ticket page's own entry", () => {
    const pageKey = ["tickets", "by-key", "KAN", 142] as const;
    const store = fakeCache([
      { key: listKey("all", ""), data: [ticket("t1"), ticket("t2")] },
      { key: pageKey, data: ticket("t1") },
    ]);
    const guesses = new TicketGuesses();

    guesses.open(store.cache, "t1", removedTicket);

    expect(store.read<Ticket[]>(listKey("all", ""))?.map((row) => row.id)).toEqual(["t2"]);
    // The page's answer to a ticket that no longer exists is the 404 it was written for.
    expect(store.was(pageKey)).toBe(true);
  });
});

describe("a guess that was wrong", () => {
  it("restores exactly what was there", () => {
    const before = [ticket("t1", { estimate: 3 }), ticket("t2")];
    const store = fakeCache([{ key: listKey("all", ""), data: before }]);
    const guesses = new TicketGuesses();

    const handle = guesses.open(store.cache, "t1", patchedTicket({ status: "done" }, ["estimate"]));
    guesses.close(store.cache, handle);

    expect(store.read<Ticket[]>(listKey("all", ""))).toEqual(before);
  });

  it("puts back a row it had taken out of a list", () => {
    const store = fakeCache([{ key: listKey("all", "", false), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const handle = guesses.open(store.cache, "t1", removedTicket);
    expect(store.read<Ticket[]>(listKey("all", "", false))).toEqual([]);

    guesses.close(store.cache, handle);

    // Where the row goes back in the server's order is the server's business, so the key
    // that lost it is refetched rather than spliced at a guessed position.
    expect(store.was(listKey("all", "", false))).toBe(true);
  });

  it("forgets the row once nothing is in flight about it", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    guesses.close(store.cache, guesses.open(store.cache, "t1", patchedTicket({ title: "a" })));

    expect(guesses.idle).toBe(true);
  });
});

describe("a guess whose write went to the offline queue", () => {
  it("keeps what it had drawn, because the write is on disk and not lost", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const handle = guesses.open(store.cache, "t1", patchedTicket({ status: "done" }));
    guesses.hold(store.cache, handle);

    // `close` with no row means "the server refused", and a refusal is the one thing a
    // queued write is not: it never reached a server. Reverting the row here would be
    // the interface disagreeing with the banner that says the write is still coming.
    expect(store.read<Ticket[]>(listKey("all", ""))?.[0]?.status).toBe("done");
  });

  it("is idle afterwards, so a reconnect may sweep the cache", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    guesses.hold(store.cache, guesses.open(store.cache, "t1", patchedTicket({ status: "done" })));

    // The whole reason a held write settles at all: `resumeAfterOutage` waits for idle,
    // and going offline is precisely what precedes a reconnect.
    expect(guesses.idle).toBe(true);
  });

  it("leaves a row gone when the write ahead of it was a delete the server took", () => {
    const store = fakeCache([{ key: listKey("all", "", false), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const remove = guesses.open(store.cache, "t1", removedTicket);
    const rename = guesses.open(store.cache, "t1", patchedTicket({ title: "renamed" }));
    guesses.close(store.cache, remove, null);

    // The delete landed; the rename behind it is queued against a row that no longer
    // exists. Painting it back would put a deleted ticket in the list until the flush
    // gets its 404.
    guesses.hold(store.cache, rename);

    expect(store.read<Ticket[]>(listKey("all", "", false))).toEqual([]);
  });

  it("folds a guess still in flight over the held one", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const first = guesses.open(store.cache, "t1", patchedTicket({ status: "done" }));
    guesses.open(store.cache, "t1", patchedTicket({ priority: "urgent" }));
    guesses.hold(store.cache, first);

    const row = store.read<Ticket[]>(listKey("all", ""))?.[0];
    expect(row?.status).toBe("done");
    expect(row?.priority).toBe("urgent");
  });
});

describe("two guesses in flight about the same row", () => {
  it("keeps the second when the first is refused", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const first = guesses.open(store.cache, "t1", patchedTicket({ status: "done" }));
    guesses.open(store.cache, "t1", patchedTicket({ priority: "urgent" }));
    guesses.close(store.cache, first);

    const row = store.read<Ticket[]>(listKey("all", ""))?.[0];
    // A snapshot restored wholesale would have taken the priority with it.
    expect(row?.status).toBe("todo");
    expect(row?.priority).toBe("urgent");
  });

  it("keeps the first when the second is refused", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    guesses.open(store.cache, "t1", patchedTicket({ status: "done" }));
    const second = guesses.open(store.cache, "t1", patchedTicket({ priority: "urgent" }));
    guesses.close(store.cache, second);

    const row = store.read<Ticket[]>(listKey("all", ""))?.[0];
    expect(row?.status).toBe("done");
    expect(row?.priority).toBe("none");
  });

  it("folds what is still in flight over the row the first one came back with", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const first = guesses.open(store.cache, "t1", patchedTicket({ status: "done" }));
    guesses.open(store.cache, "t1", patchedTicket({ priority: "urgent" }));
    guesses.close(store.cache, first, ticket("t1", { status: "done", title: "renamed by nick" }));

    const row = store.read<Ticket[]>(listKey("all", ""))?.[0];
    expect(row?.title).toBe("renamed by nick");
    expect(row?.priority).toBe("urgent");
  });

  it("is only idle once both have settled", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const first = guesses.open(store.cache, "t1", patchedTicket({ status: "done" }));
    const second = guesses.open(store.cache, "t1", patchedTicket({ priority: "urgent" }));

    guesses.close(store.cache, first);
    expect(guesses.idle).toBe(false);
    guesses.close(store.cache, second);
    expect(guesses.idle).toBe(true);
  });
});

describe("the realtime feed, while a guess is in flight", () => {
  it("costs nothing when the server confirms what was already drawn", async () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();
    const saved = ticket("t1", { status: "done" });

    const handle = guesses.open(store.cache, "t1", patchedTicket({ status: "done" }));
    guesses.close(store.cache, handle, saved);
    const before = store.writes.length;

    // The server's own event for this change, arriving a moment later.
    await applyEvents(
      {
        cache: store.cache,
        fetchTicket: async () => saved,
        overlay: guesses.fold,
      },
      [
        {
          entity: "tickets",
          kind: "UPDATED",
          id: "t1",
          origin: "kanso",
          at: "2026-01-01T00:00:00Z",
        },
      ],
    );

    expect(store.writes.length).toBe(before);
    expect(store.read<Ticket[]>(listKey("all", ""))?.[0]?.status).toBe("done");
  });

  it("does not undo a guess the server has not answered yet", async () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    guesses.open(store.cache, "t1", patchedTicket({ priority: "urgent" }));

    // Somebody else renamed the same ticket; the fetched row knows nothing of the
    // priority still in flight here.
    await applyEvents(
      {
        cache: store.cache,
        fetchTicket: async () => ticket("t1", { title: "renamed by nick" }),
        overlay: guesses.fold,
      },
      [
        {
          entity: "tickets",
          kind: "UPDATED",
          id: "t1",
          origin: "kanso",
          at: "2026-01-01T00:00:00Z",
        },
      ],
    );

    const row = store.read<Ticket[]>(listKey("all", ""))?.[0];
    expect(row?.title).toBe("renamed by nick");
    expect(row?.priority).toBe("urgent");
  });

  it("keeps the other reader's row once the guess settles on top of it", async () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const handle = guesses.open(store.cache, "t1", patchedTicket({ priority: "urgent" }));
    await applyEvents(
      {
        cache: store.cache,
        fetchTicket: async () => ticket("t1", { title: "renamed by nick" }),
        overlay: guesses.fold,
      },
      [
        {
          entity: "tickets",
          kind: "UPDATED",
          id: "t1",
          origin: "kanso",
          at: "2026-01-01T00:00:00Z",
        },
      ],
    );
    guesses.close(store.cache, handle);

    const row = store.read<Ticket[]>(listKey("all", ""))?.[0];
    // Rolling back to the row as it was when the guess opened would have thrown away a
    // rename this client never made.
    expect(row?.title).toBe("renamed by nick");
    expect(row?.priority).toBe("none");
  });
});

describe("what is deliberately not guessed", () => {
  it("says nothing about a row no cache holds, which is what a create is", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const handle = guesses.open(store.cache, "new", patchedTicket({ title: "a new one" }));

    expect(store.writes).toEqual([]);
    expect(store.invalidated).toEqual([]);
    expect(guesses.idle).toBe(true);
    // Settling a guess that was never opened is a no-op, so the caller needs no branch.
    expect(() => guesses.close(store.cache, handle)).not.toThrow();
    expect(store.writes).toEqual([]);
  });

  it("refetches the lists a created row belongs in rather than placing it", () => {
    const store = fakeCache([
      { key: listKey("project", "p1"), data: [] },
      { key: listKey("project", "p2"), data: [] },
    ]);
    const guesses = new TicketGuesses();

    // What `useCreateTicket` does with the row the server hands back: the identifier and
    // the position in the server's order are both the server's, so only the keys that
    // have to gain it are asked again.
    guesses.arrived(store.cache, ticket("t9", { projectId: "p1" }));

    expect(store.was(listKey("project", "p1"))).toBe(true);
    expect(store.was(listKey("project", "p2"))).toBe(false);
    expect(store.writes).toEqual([]);
  });
});

describe("the ledger on its own", () => {
  it("answers nothing about a subject it never had a base for", () => {
    const guesses = new Guesses<string>();
    expect(guesses.value("a")).toBeUndefined();
  });

  it("folds guesses oldest first", () => {
    const guesses = new Guesses<string>();
    guesses.open("a", () => "x", (value) => `${value}1`);
    guesses.open("a", () => "ignored", (value) => `${value}2`);
    expect(guesses.value("a")).toBe("x12");
  });

  it("stops folding once a guess has said the subject is gone", () => {
    const guesses = new Guesses<string>();
    guesses.open("a", () => "x", () => null);
    guesses.open("a", () => "ignored", (value) => `${value}!`);
    expect(guesses.value("a")).toBeNull();
  });

  it("keeps subjects apart", () => {
    const guesses = new Guesses<string>();
    guesses.open("a", () => "x", (value) => `${value}!`);
    guesses.open("b", () => "y", (value) => `${value}?`);
    expect(guesses.value("a")).toBe("x!");
    expect(guesses.value("b")).toBe("y?");
  });

  it("hands a row straight back when nothing is in flight about it", () => {
    const guesses = new Guesses<string>();
    expect(guesses.rebase("a", "fresh")).toBe("fresh");
  });
});

describe("the labels a ticket will wear", () => {
  const catalogue = [label("l1", "sync"), label("l2", "bug"), label("l3", "chore")];

  it("resolves the chosen ids against the team's labels", () => {
    expect(wornLabels(["l1", "l2"], catalogue).map((row) => row.id)).toEqual(["l2", "l1"]);
  });

  it("sorts by name, which is the order the endpoint answers in", () => {
    expect(wornLabels(["l1", "l3", "l2"], catalogue).map((row) => row.name)).toEqual([
      "bug",
      "chore",
      "sync",
    ]);
  });

  it("drops an id the catalogue cannot name rather than drawing a blank pill", () => {
    expect(wornLabels(["l1", "unknown"], catalogue).map((row) => row.id)).toEqual(["l1"]);
  });

  it("is empty when every pill has been pressed off", () => {
    expect(wornLabels([], catalogue)).toEqual([]);
  });
});

describe("a reconnect, while a guess is in flight", () => {
  it("holds the sweep until the mutation settles", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const handle = guesses.open(store.cache, "t1", patchedTicket({ title: "renamed" }));
    resumeAfterOutage(store.cache, guesses);

    // A refetch writes the server's rows into the cache directly — nowhere near the
    // overlay that folds a live guess back over them — so sweeping now would un-draw a
    // change the server has not been told about yet.
    expect(store.sweeps()).toBe(0);
    expect(store.read<Ticket[]>(listKey("all", ""))?.[0].title).toBe("renamed");

    guesses.close(store.cache, handle, ticket("t1", { title: "renamed" }));
    expect(store.sweeps()).toBe(1);
  });

  it("sweeps once the last write is held, which is the reconnect's own case", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const handle = guesses.open(store.cache, "t1", patchedTicket({ status: "done" }));
    resumeAfterOutage(store.cache, guesses);
    expect(store.sweeps()).toBe(0);

    // The order this actually happens in: the network goes, the write is queued and
    // held, the network comes back and the socket reconnects. A hold that did not drain
    // would leave that sweep waiting for a request that has already had its answer.
    guesses.hold(store.cache, handle);

    expect(store.sweeps()).toBe(1);
  });

  it("sweeps at once when nothing is in flight", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);

    resumeAfterOutage(store.cache, new TicketGuesses());

    expect(store.sweeps()).toBe(1);
  });

  it("waits for the last guess, not the first", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1"), ticket("t2")] }]);
    const guesses = new TicketGuesses();

    const first = guesses.open(store.cache, "t1", patchedTicket({ title: "a" }));
    const second = guesses.open(store.cache, "t2", patchedTicket({ title: "b" }));
    resumeAfterOutage(store.cache, guesses);

    guesses.close(store.cache, first);
    expect(store.sweeps()).toBe(0);

    guesses.close(store.cache, second);
    expect(store.sweeps()).toBe(1);
  });

  it("sweeps once per outage, not once per guess that settles", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const guesses = new TicketGuesses();

    const handle = guesses.open(store.cache, "t1", patchedTicket({ title: "a" }));
    resumeAfterOutage(store.cache, guesses);
    guesses.close(store.cache, handle);

    guesses.close(store.cache, guesses.open(store.cache, "t1", patchedTicket({ title: "b" })));

    expect(store.sweeps()).toBe(1);
  });

  it("keeps waiting for a guess opened after the sweep was queued", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1"), ticket("t2")] }]);
    const guesses = new TicketGuesses();

    const first = guesses.open(store.cache, "t1", patchedTicket({ title: "a" }));
    resumeAfterOutage(store.cache, guesses);
    const second = guesses.open(store.cache, "t2", patchedTicket({ title: "b" }));

    guesses.close(store.cache, first);
    // Drained on going idle, not on the next settle whatever else is still open.
    expect(store.sweeps()).toBe(0);

    guesses.close(store.cache, second);
    expect(store.sweeps()).toBe(1);
  });
});
