import { describe, expect, it, vi } from "vitest";
import type { Team, Ticket } from "./api";
import {
  applyEvents,
  findTicket,
  PATCH_LIMIT,
  RealtimeCache,
  topicsFor,
  writeTickets,
  type CacheEntry,
  type EventCache,
  type KansoEvent,
} from "./realtime-events";

/**
 * A query cache, in memory.
 *
 * The point of the cache being an interface is that the behaviour worth testing — what
 * gets patched, what gets refetched, what is never asked for at all — is testable in
 * `environment: "node"`, where there is no React and no fetch. `writes` and
 * `invalidated` are the whole assertion surface: this module's job is to choose between
 * those two, and choosing the second when the first would do is the bug it exists to fix.
 */
function fakeCache(seed: CacheEntry[] = []) {
  const rows = new Map(seed.map((entry) => [JSON.stringify(entry.key), entry]));
  const invalidated: string[] = [];
  const writes: string[] = [];

  const cache: EventCache = {
    entries: (segment) => [...rows.values()].filter((entry) => entry.key[0] === segment),
    set: (key, data) => {
      writes.push(JSON.stringify(key));
      rows.set(JSON.stringify(key), { key, data });
    },
    invalidate: (key) => void invalidated.push(JSON.stringify(key)),
  };

  return {
    cache,
    invalidated,
    writes,
    read: <T>(key: readonly unknown[]) => rows.get(JSON.stringify(key))?.data as T | undefined,
    was: (key: readonly unknown[]) => invalidated.includes(JSON.stringify(key)),
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
  archived: false,
  mirror: { state: "disabled" },
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  ...overrides,
});

const event = (overrides: Partial<KansoEvent> = {}): KansoEvent => ({
  entity: "tickets",
  kind: "UPDATED",
  id: "t1",
  teamId: "team-a",
  origin: "kanso",
  at: "2026-01-01T00:00:00Z",
  ...overrides,
});

const team = (id: string, parentTeamId?: string): Team => ({
  id,
  name: id,
  key: id.toUpperCase(),
  parentTeamId,
  archived: false,
  ticketCount: 0,
  mirror: { state: "disabled" },
  editable: true,
});

/** Answers with whatever it was seeded with, and records what it was asked for. */
function server(rows: Ticket[] = []) {
  const asked: string[] = [];
  return {
    asked,
    fetchTicket: async (id: string) => {
      asked.push(id);
      return rows.find((row) => row.id === id);
    },
  };
}

describe("applying a realtime event to the cache", () => {
  it("patches the changed row rather than invalidating the list it is in", async () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1"), ticket("t2")] }]);
    const api = server([ticket("t1", { title: "renamed" })]);

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [event()]);

    expect(store.read<Ticket[]>(listKey("all", ""))?.map((row) => row.title)).toEqual([
      "renamed",
      "t2",
    ]);
    expect(store.was(["tickets"])).toBe(false);
    expect(api.asked).toEqual(["t1"]);
  });

  it("removes a deleted ticket without asking the server for a row that is gone", async () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1"), ticket("t2")] }]);
    const api = server();

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [
      event({ kind: "DELETED" }),
    ]);

    expect(store.read<Ticket[]>(listKey("all", ""))?.map((row) => row.id)).toEqual(["t2"]);
    expect(api.asked).toEqual([]);
  });

  it("still widens to the timeline, where a cascade moves rows nobody named", async () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const api = server([ticket("t1"), ticket("t2")]);

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [
      event({ id: "t1" }),
      event({ id: "t2" }),
    ]);

    // Once for the batch, not once per event.
    expect(store.invalidated.filter((key) => key === JSON.stringify(["timeline"]))).toHaveLength(1);
  });

  it("drops a row the edit moved out of the list", async () => {
    const store = fakeCache([
      { key: listKey("project", "p1"), data: [ticket("t1", { projectId: "p1" })] },
    ]);
    const api = server([ticket("t1", { projectId: "p2" })]);

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [event()]);

    expect(store.read<Ticket[]>(listKey("project", "p1"))).toEqual([]);
  });

  it("drops a row the edit archived, from the list that hides archived work", async () => {
    const store = fakeCache([
      { key: listKey("all", "", false), data: [ticket("t1")] },
      { key: listKey("all", "", true), data: [ticket("t1")] },
    ]);
    const api = server([ticket("t1", { archived: true })]);

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [event()]);

    expect(store.read<Ticket[]>(listKey("all", "", false))).toEqual([]);
    expect(store.read<Ticket[]>(listKey("all", "", true))).toHaveLength(1);
  });

  it("refetches only the lists a new ticket could belong to", async () => {
    const store = fakeCache([
      { key: listKey("project", "p1"), data: [] },
      { key: listKey("project", "p2"), data: [] },
    ]);
    const api = server([ticket("t9", { projectId: "p1" })]);

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [
      event({ id: "t9", kind: "CREATED", projectId: "p1" }),
    ]);

    expect(store.was(listKey("project", "p1"))).toBe(true);
    expect(store.was(listKey("project", "p2"))).toBe(false);
  });

  it("keeps a sub-team's row in a parent-scoped list, reading the team tree it has", async () => {
    const store = fakeCache([
      { key: ["teams", false], data: [team("team-a"), team("team-b", "team-a")] },
      { key: listKey("team", "team-a"), data: [ticket("t1", { teamId: "team-b" })] },
    ]);
    const api = server([ticket("t1", { teamId: "team-b", title: "renamed" })]);

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [
      event({ teamId: "team-b" }),
    ]);

    expect(store.read<Ticket[]>(listKey("team", "team-a"))?.[0]?.title).toBe("renamed");
    expect(store.was(listKey("team", "team-a"))).toBe(false);
  });

  it("refreshes the ticket page's own entry, which is keyed on an identifier", async () => {
    const pageKey = ["tickets", "by-key", "KAN", 142] as const;
    const store = fakeCache([{ key: pageKey, data: ticket("t1") }]);
    const api = server([ticket("t1", { title: "renamed" })]);

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [event()]);

    expect(store.read<Ticket>(pageKey)?.title).toBe("renamed");
  });

  it("falls back to one refetch when a batch is too big to be worth patching", async () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [] }]);
    const api = server();
    const many = Array.from({ length: PATCH_LIMIT + 1 }, (_, index) => event({ id: `t${index}` }));

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, many);

    expect(store.was(["tickets"])).toBe(true);
    expect(api.asked).toEqual([]);
  });

  it("falls back to one refetch when the server will not hand over a changed row", async () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const api = server([]);

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [event()]);

    expect(store.was(["tickets"])).toBe(true);
  });

  it("leaves the entities an event says nothing about alone", async () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    const api = server([ticket("t1")]);

    await applyEvents({ cache: store.cache, fetchTicket: api.fetchTicket }, [
      event({ entity: "teams", id: "team-a" }),
    ]);

    expect(store.was(["teams"])).toBe(true);
    expect(store.was(["timeline"])).toBe(false);
    expect(api.asked).toEqual([]);
  });
});

describe("writing a changed row, whoever changed it", () => {
  it("leaves the cache alone when the row it was handed is the row already there", () => {
    const store = fakeCache([
      { key: listKey("all", ""), data: [ticket("t1"), ticket("t2")] },
      { key: ["tickets", "by-key", "KAN", 142] as const, data: ticket("t1") },
    ]);

    writeTickets(store.cache, { changed: new Map([["t1", ticket("t1")]]) });

    expect(store.writes).toEqual([]);
    expect(store.invalidated).toEqual([]);
  });

  it("writes once the row actually differs", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);

    writeTickets(store.cache, { changed: new Map([["t1", ticket("t1", { title: "renamed" })]]) });

    expect(store.writes).toEqual([JSON.stringify(listKey("all", ""))]);
  });

  it("folds an overlay over the row before deciding anything about it", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);

    writeTickets(store.cache, {
      changed: new Map([["t1", ticket("t1")]]),
      overlay: (row) => ({ ...row, priority: "urgent" }),
    });

    expect(store.read<Ticket[]>(listKey("all", ""))?.[0]?.priority).toBe("urgent");
  });

  it("takes the row out when the overlay says it is gone", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1"), ticket("t2")] }]);

    writeTickets(store.cache, {
      changed: new Map([["t1", ticket("t1")]]),
      overlay: () => null,
    });

    expect(store.read<Ticket[]>(listKey("all", ""))?.map((row) => row.id)).toEqual(["t2"]);
  });

  it("does nothing at all when it is handed nothing", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);

    writeTickets(store.cache, {});

    expect(store.writes).toEqual([]);
    expect(store.invalidated).toEqual([]);
  });
});

describe("finding a row the cache already holds", () => {
  it("reads it out of a list", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1"), ticket("t2")] }]);
    expect(findTicket(store.cache, "t2")?.id).toBe("t2");
  });

  it("reads it out of the ticket page's own entry", () => {
    const store = fakeCache([{ key: ["tickets", "by-key", "KAN", 142], data: ticket("t1") }]);
    expect(findTicket(store.cache, "t1")?.id).toBe("t1");
  });

  it("answers nothing for a row nobody has loaded", () => {
    const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
    expect(findTicket(store.cache, "t9")).toBeUndefined();
  });
});

describe("the batch window", () => {
  it("collapses a burst about one row into a single fetch and a single write", async () => {
    vi.useFakeTimers();
    try {
      const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
      const api = server([ticket("t1", { title: "renamed" })]);
      const applier = new RealtimeCache({
        cache: store.cache,
        fetchTicket: api.fetchTicket,
        windowMs: 50,
      });

      for (let burst = 0; burst < 50; burst++) applier.receive(event());
      await vi.advanceTimersByTimeAsync(49);
      expect(api.asked).toEqual([]);

      await vi.advanceTimersByTimeAsync(1);
      expect(api.asked).toEqual(["t1"]);
      expect(store.writes).toEqual([JSON.stringify(listKey("all", ""))]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("opens a new window for what arrives after the last one closed", async () => {
    vi.useFakeTimers();
    try {
      const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
      const api = server([ticket("t1")]);
      const applier = new RealtimeCache({
        cache: store.cache,
        fetchTicket: api.fetchTicket,
        windowMs: 50,
      });

      applier.receive(event());
      await vi.advanceTimersByTimeAsync(50);
      applier.receive(event());
      await vi.advanceTimersByTimeAsync(50);

      expect(api.asked).toEqual(["t1", "t1"]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("throws away a batch nobody is left to receive", async () => {
    vi.useFakeTimers();
    try {
      const store = fakeCache([{ key: listKey("all", ""), data: [ticket("t1")] }]);
      const api = server([ticket("t1")]);
      const applier = new RealtimeCache({
        cache: store.cache,
        fetchTicket: api.fetchTicket,
        windowMs: 50,
      });

      applier.receive(event());
      applier.cancel();
      await vi.advanceTimersByTimeAsync(100);

      expect(api.asked).toEqual([]);
      expect(store.writes).toEqual([]);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("what a screen subscribes to", () => {
  const tree = [team("team-a"), team("team-b", "team-a"), team("team-c", "team-b"), team("team-z")];

  it("listens to every team and project change whatever the scope", () => {
    expect(topicsFor({ kind: "all" }, "list", tree)).toContain("/topic/projects");
    expect(topicsFor({ kind: "all" }, "list", tree)).toContain("/topic/teams");
  });

  it("takes the whole subtree, because a parent's list shows its descendants' work", () => {
    expect(topicsFor({ kind: "team", id: "team-a" }, "list", tree)).toEqual([
      "/topic/projects",
      "/topic/teams",
      "/topic/teams/team-a/tickets",
      "/topic/teams/team-b/tickets",
      "/topic/teams/team-c/tickets",
    ]);
  });

  it("narrows the board the same way it narrows the list", () => {
    expect(topicsFor({ kind: "team", id: "team-z" }, "board", tree)).toContain(
      "/topic/teams/team-z/tickets",
    );
  });

  it("stays global for a scope no team topic covers", () => {
    for (const scope of [{ kind: "all" } as const, { kind: "project", id: "p1" } as const]) {
      expect(topicsFor(scope, "list", tree)).toContain("/topic/tickets");
    }
  });

  it("stays global on the timeline, which draws bars from outside its own scope", () => {
    expect(topicsFor({ kind: "team", id: "team-a" }, "timeline", tree)).toContain(
      "/topic/tickets",
    );
  });

  it("stays global until the team tree has loaded, rather than going deaf", () => {
    expect(topicsFor({ kind: "team", id: "team-a" }, "list", [])).toContain("/topic/tickets");
  });
});
