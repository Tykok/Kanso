// Type-only, all three: `QueryClient` so the adapter at the bottom can be written
// without React in the runtime graph, `Scope` and the row shapes for the same reason
// `lib/api/core.ts` gives — restating them here would let the two drift. What is left
// is a module of plain functions, testable in `environment: "node"`.
import type { QueryClient } from "@tanstack/react-query";
import type { Scope, View } from "@/store/ui";
import type { Team, Ticket } from "./api";

export type ChangeKind = "CREATED" | "UPDATED" | "DELETED";

/**
 * What one client tells the others, verbatim from `realtime/Events.kt`.
 *
 * Deliberately thin: an id and enough scope to decide whether a view cares. `teamId` and
 * `projectId` are the whole point of it being here rather than in the socket — they are
 * what lets a receiver answer "does this belong in the list I am drawing" without asking
 * the server.
 */
export type KansoEvent = {
  entity: "tickets" | "projects" | "teams";
  kind: ChangeKind;
  id: string;
  teamId?: string;
  projectId?: string;
  /** "kanso" for someone's keystroke, "notion" when the inbound poller applied it. */
  origin: "kanso" | "notion";
  at: string;
};

// --- the subscription --------------------------------------------------------

/**
 * What a screen has to listen to.
 *
 * `projects` and `teams` stay global. The sidebar draws every team and every project
 * whatever the list is scoped to, so a narrower topic would make the tree wrong, and
 * neither entity changes often enough for the width to cost anything.
 *
 * Tickets are the loud one, and a team scope takes the per-team topics instead — but the
 * *whole subtree* of them, not just the scoped team's. `GET /api/tickets` is called with
 * `includeDescendants` for a team scope, so a parent that subscribed only to its own
 * topic would draw its sub-teams' rows and never hear them change: deaf on exactly the
 * rows a hierarchy exists to show.
 *
 * Two screens stay global whatever the scope. A project scope, because a project may
 * span several teams or belong to none, so no set of team topics covers it. And the
 * timeline, because it is the one drawing that shows work from *outside* its scope: the
 * far end of a dependency that crosses into another team arrives as a context bar, and a
 * subscription narrowed to the scope would never hear that bar move.
 */
export function topicsFor(scope: Scope, view: View, teams: readonly Team[]): string[] {
  return ["/topic/projects", "/topic/teams", ...ticketTopics(scope, view, teams)];
}

function ticketTopics(scope: Scope, view: View, teams: readonly Team[]): string[] {
  if (scope.kind !== "team" || view === "timeline") return ["/topic/tickets"];
  const subtree = teamSubtree(scope.id, teams);
  // A tree that has not loaded yet answers "one team", which is what going deaf looks
  // like — so until it has, listen to everything. The scope change that follows the
  // load swaps the subscriptions; nothing has to reconnect for it.
  return subtree ? [...subtree].map((id) => `/topic/teams/${id}/tickets`) : ["/topic/tickets"];
}

/** The team and everything under it, or `null` when the tree cannot answer. */
function teamSubtree(rootId: string, teams: readonly Team[]): Set<string> | null {
  if (!teams.some((team) => team.id === rootId)) return null;
  const ids = new Set([rootId]);
  // Repeated until nothing new appears: the list is flat and in no order anyone
  // promised, so one pass would miss a grandchild listed before its parent.
  for (let growing = true; growing; ) {
    growing = false;
    for (const team of teams) {
      if (team.parentTeamId && ids.has(team.parentTeamId) && !ids.has(team.id)) {
        ids.add(team.id);
        growing = true;
      }
    }
  }
  return ids;
}

// --- applying one batch ------------------------------------------------------

export type CacheEntry = { key: readonly unknown[]; data: unknown };

/**
 * What applying an event needs of a query cache, and nothing more.
 *
 * Behind an interface for the reason `components/offline/queue.ts` puts the disk behind
 * one: the behaviour worth testing is the *choice* between patching a row and refetching
 * a key, and that choice should be assertable without React, a fetch, or a DOM.
 * [queryCache] is the only implementation that ships.
 */
export type EventCache = {
  /** Every cached entry whose key starts with this segment, data included. */
  entries(segment: string): CacheEntry[];
  set(key: readonly unknown[], data: unknown): void;
  invalidate(key: readonly unknown[]): void;
};

export type CacheTarget = {
  cache: EventCache;
  /** One row by id. `undefined` when the server did not hand it over — gone, or refused. */
  fetchTicket: (id: string) => Promise<Ticket | undefined>;
  /**
   * Folded over every fetched row before it is written — [TicketWrite.overlay].
   *
   * `lib/optimistic.ts` supplies it, and it is optional so this module stays testable
   * without one. Without it, an event that lands while a mutation of this reader's own is
   * still in flight would write the server's row over a guess the server has not been
   * told about yet, and the change would visibly un-happen for as long as the request has
   * left to run.
   */
  overlay?: (ticket: Ticket) => Ticket | null;
};

/**
 * How long one batch collects for.
 *
 * A fixed window rather than a debounce that restarts on every event: a bulk edit is a
 * steady stream, and a restarting timer under a steady stream never fires at all. This
 * bounds the delay at one window whatever arrives, and 50 ms is under what a reader
 * notices while still catching the whole of a fifty-row edit.
 */
export const BATCH_WINDOW_MS = 50;

/**
 * How many changed rows are worth fetching one at a time.
 *
 * Past this the narrowing inverts: a list is one request and fifty rows are fifty, so a
 * batch this big is cheaper as the refetch it was trying to avoid.
 */
export const PATCH_LIMIT = 10;

/**
 * Applies a batch of realtime events to the cache. Same effect whoever caused it.
 *
 * The rule for tickets is: patch what the cache already holds, refetch only the keys
 * that would have to *gain* a row — where the row goes in the server's order is the
 * server's business, not something to guess at. Everything else is unchanged, and one
 * thing is unchanged on purpose: `["timeline"]` is still invalidated wholesale, because
 * slack and criticality are computed across the whole scope and a cascade moves bars
 * belonging to tickets no event ever named.
 */
export async function applyEvents(
  target: CacheTarget,
  batch: readonly KansoEvent[],
): Promise<void> {
  const { cache } = target;
  const tickets = batch.filter((event) => event.entity === "tickets");
  const projects = batch.some((event) => event.entity === "projects");

  if (batch.some((event) => event.entity === "teams")) cache.invalidate(["teams"]);
  if (projects) cache.invalidate(["projects"]);

  // A project's derived bounds change when its tickets do, its explicit ones when it is
  // edited, and its explicit end is a deadline the critical path reads. Once for the
  // batch: fifty notifications are still one stale chart.
  if (projects || tickets.length) cache.invalidate(["timeline"]);
  if (tickets.length) await applyTickets(target, tickets);
}

async function applyTickets(target: CacheTarget, events: readonly KansoEvent[]): Promise<void> {
  const { cache, fetchTicket } = target;

  // Last word per row: fifty notifications about one ticket are one question.
  const kinds = new Map<string, ChangeKind>();
  for (const event of events) kinds.set(event.id, event.kind);

  const gone = new Set<string>();
  const changed: string[] = [];
  for (const [id, kind] of kinds) {
    // A delete has no row to fetch, and asking for one is a round trip spent on a 404.
    if (kind === "DELETED") gone.add(id);
    else changed.push(id);
  }

  if (changed.length > PATCH_LIMIT) {
    cache.invalidate(["tickets"]);
    return;
  }

  const fetched = new Map<string, Ticket>();
  for (const row of await Promise.all(changed.map((id) => fetchTicket(id)))) {
    if (row) fetched.set(row.id, row);
  }
  // A row the server would not give up says nothing about which lists lost it, so the
  // only honest answer is the wide one this module exists to avoid. Rare: it takes a
  // ticket deleted between the notification and the fetch, or one this reader may not
  // read at all — which only the global topic can deliver.
  if (fetched.size !== changed.length) {
    cache.invalidate(["tickets"]);
    return;
  }

  writeTickets(cache, { changed: fetched, gone, overlay: target.overlay });
}

// --- writing rows into the cache ---------------------------------------------

export type TicketWrite = {
  /** Rows as they are now described, by id. */
  changed?: ReadonlyMap<string, Ticket>;
  /** Rows that are not anywhere any more. */
  gone?: ReadonlySet<string>;
  /**
   * Folded over each row in [changed] before anything is decided about it; `null` moves
   * that row to [gone].
   *
   * The seam `lib/optimistic.ts` writes through. A guess about a row is the same
   * operation as an event about it — a changed row, written into every cached list that
   * holds it — so it is the same function, and the two cannot drift into disagreeing
   * about which lists hold what.
   */
  overlay?: (ticket: Ticket) => Ticket | null;
};

/**
 * Writes changed and vanished rows into every cached entry that holds them.
 *
 * The one door: `applyEvents` above comes through it with rows it fetched, and
 * `lib/optimistic.ts` with rows it guessed. Both need the same three answers — which
 * lists hold the row, which of them it has just moved out of, and what the ticket page's
 * single-row entry should say — and there is no version of those answers that is right
 * for a socket and wrong for a keystroke.
 */
export function writeTickets(cache: EventCache, write: TicketWrite): void {
  const gone = new Set(write.gone ?? []);
  const changed = new Map<string, Ticket>();
  for (const [id, row] of write.changed ?? []) {
    const folded = write.overlay ? write.overlay(row) : row;
    if (folded) changed.set(id, folded);
    else gone.add(id);
  }
  if (!changed.size && !gone.size) return;

  const teams = knownTeams(cache);
  for (const entry of cache.entries("tickets")) {
    const list = listShape(entry);
    if (list) patchList(cache, entry.key, list, changed, gone, teams);
    else if (isTicket(entry.data)) patchRow(cache, entry.key, entry.data, changed, gone);
    // A filtered list, which nothing here can place a row in. The wide answer, for the
    // reason the two paragraphs above give: only the server knows what matches, so
    // asking it again is the only honest way to learn whether this row now does.
    else if (Array.isArray(entry.data) && askedOf(entry) !== "") cache.invalidate(entry.key);
  }
}

/**
 * The row as the cache currently holds it, from wherever it holds it.
 *
 * What a guess needs before it can be a guess: something to guess *from*. A row nobody
 * has loaded answers `undefined`, which is the honest answer and the one that tells
 * `lib/optimistic.ts` there is nothing on screen to paint.
 */
export function findTicket(cache: EventCache, id: string): Ticket | undefined {
  for (const entry of cache.entries("tickets")) {
    const list = listShape(entry);
    if (list) {
      const row = list.rows.find((candidate) => candidate.id === id);
      if (row) return row;
    } else if (isTicket(entry.data) && entry.data.id === id) {
      return entry.data;
    }
  }
  return undefined;
}

/**
 * The filter set a tickets entry was asked with, as `keys.tickets` spells it — `""` for
 * the unfiltered list, and for any key written before that segment existed.
 */
function askedOf(entry: CacheEntry): string {
  const asked = entry.key[4];
  return typeof asked === "string" ? asked : "";
}

type ListShape = { rows: Ticket[]; scope: Scope; includeArchived: boolean };

const SCOPE_KINDS = ["all", "team", "project"] as const;

/**
 * A `keys.tickets(scope, includeArchived)` entry, or `null`.
 *
 * Read off the key rather than declared anywhere: `queries/core.ts` builds these and
 * `queries/views.ts` deliberately puts other things under the same first segment, so
 * the shape is what tells them apart.
 *
 * A *filtered* entry is not one of these, and saying so is the whole of [askedOf]. The
 * scope is the only question [placement] below can answer — it knows a row's team and
 * its project and nothing else about it — so a list narrowed by status, label or points
 * would take every row the scope admits, filter or no filter. That is a row appearing in
 * a list that excludes it, which is the failure the filter gate exists to prevent.
 */
function listShape(entry: CacheEntry): ListShape | null {
  const [, kind, id, includeArchived] = entry.key;
  if (!Array.isArray(entry.data)) return null;
  if (typeof includeArchived !== "boolean") return null;
  if (askedOf(entry) !== "") return null;
  if (typeof kind !== "string" || !SCOPE_KINDS.includes(kind as (typeof SCOPE_KINDS)[number])) {
    return null;
  }
  const scope: Scope =
    kind === "all" ? { kind: "all" } : { kind: kind as "team" | "project", id: String(id) };
  return { rows: entry.data as Ticket[], scope, includeArchived };
}

/** Whether a row belongs in a list — and the third answer, when only the server knows. */
type Placement = "in" | "out" | "unknown";

function placement(
  ticket: Ticket,
  { scope, includeArchived }: ListShape,
  teams: readonly Team[],
): Placement {
  if (ticket.archived && !includeArchived) return "out";
  if (scope.kind === "all") return "in";
  if (scope.kind === "project") return ticket.projectId === scope.id ? "in" : "out";
  if (ticket.teamId === scope.id) return "in";
  // A team scope includes its descendants, so a stranger's row and a sub-team's row are
  // the same row until the tree says otherwise.
  const subtree = teamSubtree(scope.id, teams);
  if (!subtree) return "unknown";
  return subtree.has(ticket.teamId) ? "in" : "out";
}

function patchList(
  cache: EventCache,
  key: readonly unknown[],
  list: ListShape,
  changed: Map<string, Ticket>,
  gone: Set<string>,
  teams: readonly Team[],
): void {
  const next: Ticket[] = [];
  let touched = false;

  for (const row of list.rows) {
    if (gone.has(row.id)) {
      touched = true;
      continue;
    }
    const fresh = changed.get(row.id);
    if (!fresh) {
      next.push(row);
      continue;
    }
    // "unknown" keeps it: the row was in this list a moment ago, and dropping it on a
    // tree the client has not loaded would make a sub-team's work vanish mid-edit.
    if (placement(fresh, list, teams) === "out") {
      touched = true;
      continue;
    }
    // Compared rather than assumed changed, and this is what makes the server's echo of
    // this client's own edit free: the event arrives carrying the row already on screen,
    // and a write of an identical list is a repaint of something nobody changed.
    if (!same(fresh, row)) touched = true;
    next.push(fresh);
  }

  // A row this list does not hold yet. Where the server would sort it in is the server's
  // business, so the key is refetched rather than guessed at — and only this key, which
  // is what a `CREATED` event for work nobody is looking at now costs: nothing. The same
  // rule answers a rolled-back guess that had emptied a row out of this list.
  const arriving = [...changed.values()].some(
    (fresh) =>
      !list.rows.some((row) => row.id === fresh.id) && placement(fresh, list, teams) !== "out",
  );

  if (arriving) cache.invalidate(key);
  else if (touched) cache.set(key, next);
}

/** The ticket page's own entry, which keys on the identifier in the URL, not on the id. */
function patchRow(
  cache: EventCache,
  key: readonly unknown[],
  row: Ticket,
  changed: Map<string, Ticket>,
  gone: Set<string>,
): void {
  // Refetched rather than dropped: the page's answer to a ticket that no longer exists
  // is the 404 it was written for, and only the query can produce it.
  if (gone.has(row.id)) cache.invalidate(key);
  else {
    const fresh = changed.get(row.id);
    if (fresh && !same(fresh, row)) cache.set(key, fresh);
  }
}

function isTicket(data: unknown): data is Ticket {
  return typeof data === "object" && data !== null && typeof (data as Ticket).id === "string";
}

/**
 * Whether two rows say the same thing.
 *
 * Written out rather than left to react-query's structural sharing, which would also
 * spare the render: that is a library detail one `structuralSharing: false` away from
 * being untrue, and "the server's echo of your own edit changes nothing" is a promise
 * this module makes and its tests check. A ticket is JSON — scalars, two instants, two
 * string arrays and the mirror — so a walk over it is all this needs to be.
 */
function same(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  const left = Object.keys(a as object);
  const right = Object.keys(b as object);
  if (left.length !== right.length) return false;
  return left.every(
    (key) =>
      key in (b as object) &&
      same((a as Record<string, unknown>)[key], (b as Record<string, unknown>)[key]),
  );
}

/**
 * The team tree, from whatever the cache already holds.
 *
 * Read rather than fetched: `useTeams` has loaded it on every screen that can be scoped
 * to a team, and a socket event is not a reason to ask again. `keys.teamMembers` lives
 * under the same first segment, so the list is the one keyed on a flag; the longest wins
 * because the archived-included copy is the more complete tree.
 */
function knownTeams(cache: EventCache): readonly Team[] {
  let best: Team[] = [];
  for (const entry of cache.entries("teams")) {
    if (typeof entry.key[1] !== "boolean" || !Array.isArray(entry.data)) continue;
    if (entry.data.length >= best.length) best = entry.data as Team[];
  }
  return best;
}

// --- the batch window --------------------------------------------------------

/**
 * Collects events for one window, then applies them together.
 *
 * A bulk edit of fifty tickets is fifty `pg_notify` messages, and fifty separate passes
 * over the cache would be fifty renders for one action. One instance per connection; it
 * holds the batch and nothing else, so throwing it away is [cancel] and no more.
 */
export class RealtimeCache {
  private readonly target: CacheTarget;
  private readonly windowMs: number;
  private readonly pending = new Map<string, KansoEvent>();
  private timer?: ReturnType<typeof setTimeout>;

  constructor(options: CacheTarget & { windowMs?: number }) {
    this.target = options;
    this.windowMs = options.windowMs ?? BATCH_WINDOW_MS;
  }

  receive(event: KansoEvent): void {
    this.pending.set(`${event.entity}:${event.id}`, event);
    // Set once and left to run: the window opens with the first event of a burst, so a
    // stream that never pauses is still applied every `windowMs`.
    this.timer ??= setTimeout(() => {
      this.timer = undefined;
      void this.flush();
    }, this.windowMs);
  }

  /** Applies whatever has arrived, now. */
  async flush(): Promise<void> {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    const batch = [...this.pending.values()];
    this.pending.clear();
    if (batch.length) await applyEvents(this.target, batch);
  }

  /** Unmounting. A queued batch has no cache left to write into. */
  cancel(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    this.pending.clear();
  }
}

/**
 * [EventCache] over the real thing. The only place this module knows react-query exists,
 * and it holds no decisions — every one of them is above, where the tests can reach it.
 */
export function queryCache(client: QueryClient): EventCache {
  return {
    entries: (segment) =>
      client
        .getQueryCache()
        .findAll({ queryKey: [segment] })
        .map((query) => ({ key: query.queryKey, data: query.state.data })),
    set: (key, data) => void client.setQueryData(key, data),
    invalidate: (key) => void client.invalidateQueries({ queryKey: key }),
  };
}
