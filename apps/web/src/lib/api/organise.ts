import { API_URL, ApiError, getDevUser, type Ticket, type TicketPriority, type TicketStatus } from "./core";

/**
 * Slice C's client — cycles, triage, saved views, bulk edit, workload.
 *
 * `core.ts` keeps its `request` and `query` helpers module-private, and `core.ts` is
 * frozen for the fan-out, so the two are restated here rather than exported from a file
 * six branches were told not to touch. They are the same eleven lines; `API_URL`,
 * `ApiError` and `getDevUser` come from `core` so the identity and the error shape cannot
 * drift from the rest of the client. Exporting them from `core` is the right fix and
 * belongs to whoever integrates the six.
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const devUser = getDevUser();
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(devUser ? { "X-Kanso-User": devUser } : {}),
      ...init.headers,
    },
  });

  if (response.status === 204) return undefined as T;

  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(response.status, problem?.detail ?? response.statusText, problem);
  }
  return response.json() as Promise<T>;
}

/** Drops absent parameters rather than sending `undefined` as a literal string. */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) search.set(key, String(value));
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : "";
}

// --- cycles ---------------------------------------------------------------

export const CYCLE_STATES = ["upcoming", "active", "closed"] as const;
export type CycleState = (typeof CYCLE_STATES)[number];

export type Cycle = {
  id: string;
  teamId: string;
  number: number;
  /** `YYYY-MM-DD`. A cycle is whole days everyone in the team agrees on, so no timezone. */
  startsOn: string;
  endsOn: string;
  state: CycleState;
  ticketCount: number;
};

/** One bar of the burn-down. `projected` is what the chart hatches. */
/**
 * One bar of the burn-down, in both units: `open` is the rows left that day, `openPoints`
 * the points. Neither is derivable from the other — a cycle can be half-estimated — so
 * the server sends both and the chart picks the one the cycle can actually be read in.
 */
export type RemainingDay = {
  day: string;
  open: number;
  openPoints: number;
  projected: boolean;
};

/**
 * The cycle's effort in points, and how many of its tickets the sum cannot speak for.
 *
 * `unestimated` travels with the sum everywhere it is drawn: a total that quietly leaves
 * out a third of the cycle reads as the whole of it, and that is the one thing a
 * burn-down must not do.
 */
export type CyclePoints = {
  total: number;
  done: number;
  percent: number;
  unestimated: number;
};

export type CycleReport = {
  cycle: Cycle;
  total: number;
  done: number;
  percent: number;
  /** The same three questions in points. The counts stay: not every team estimates. */
  points: CyclePoints;
  /** Keyed by the status wire value, and every counted status is present, including zeros. */
  byStatus: Record<string, number>;
  daysLeft: number;
  remaining: RemainingDay[];
  /** What does not fit in the days left at the observed rate, worst-last. */
  slipping: Ticket[];
  tickets: Ticket[];
};

// --- triage ---------------------------------------------------------------

export const TRIAGE_DECISIONS = ["accepted", "backlogged", "duplicate", "closed"] as const;
export type TriageDecision = (typeof TRIAGE_DECISIONS)[number];

/** `total` is the whole queue; `items` is the page of it on screen. */
export type TriageQueue = { total: number; items: Ticket[] };

export type SimilarTicket = {
  ticket: Ticket;
  /** A whole percent, as the drawing prints it: `68 %`. */
  similarity: number;
};

export type Person = { id: string; displayName: string; avatarUrl?: string };

export type TriageRuling = {
  ticket: Ticket;
  decision: TriageDecision;
  duplicateOf?: Ticket;
  decidedBy?: Person;
  decidedAt: string;
};

// --- saved views ----------------------------------------------------------

export const VIEW_GROUP_BYS = ["status", "priority", "assignee", "project", "none"] as const;
export const VIEW_SORT_BYS = ["priority", "updated", "created", "title"] as const;
export type ViewGroupBy = (typeof VIEW_GROUP_BYS)[number];
export type ViewSortBy = (typeof VIEW_SORT_BYS)[number];

/**
 * The facets the server actually matches on — `TicketFilterVocabulary.SERVED`, key for
 * key. A key this type carries that the set does not is a 400 on the write, not a chip.
 *
 * It is no longer only a saved view's vocabulary: `GET /api/tickets` takes these same
 * names as query parameters and is refused by the same gate, so the main list and a
 * stored question are one query asked through two doors. The name stays `ViewFilters`
 * because a chip is a chip wherever it is drawn.
 */
export type ViewFilters = {
  status?: TicketStatus[];
  /** The drawing's `Statut ≠ Done`. Its own key, because "not done" is the useful shape. */
  statusNot?: TicketStatus[];
  priority?: TicketPriority[];
  project?: string[];
  assignee?: string[];
  unassigned?: boolean;
  cycle?: string[];
  /**
   * The drawing's `Étiquette synchro`, by label id and not by name: labels are
   * team-scoped, a view reaches into descendant teams, and two of them may both own the
   * name `sync`.
   */
  label?: string[];
  /** "Blocked for 3 days", measured from creation. */
  openedForDays?: number;
  /**
   * "Sans estimation" — its own flag rather than a bound, because null is not a small
   * number. Asked with a bound, the two contradict and the answer is empty, which is the
   * honest reading of "unsized and bigger than a 3".
   */
  unestimated?: boolean;
  /** Bounds on the points, inclusive. Neither of them ever matches an unsized ticket. */
  estimateMin?: number;
  estimateMax?: number;
};

/**
 * A [ViewFilters] as `GET /api/tickets` takes it — the same names, in a query string.
 *
 * The list endpoint and a saved view are one question asked through two doors, so this is
 * a spelling change and nothing more. A list-valued facet repeats its key rather than
 * joining on a comma, because that is what `MultiValueMap` on the other side reads and
 * because a comma is a legal character in nothing the values here hold.
 *
 * An empty list and `false` are written as absent. The server refuses a name it does not
 * serve but says nothing about a facet asked with no answer, and sending `unassigned=
 * false` would put a chip on the wire that the screen is not drawing.
 */
export function filterParams(filters: ViewFilters): URLSearchParams {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || value === null || value === false) continue;
    if (Array.isArray(value)) {
      for (const one of value) search.append(key, String(one));
    } else {
      search.append(key, String(value));
    }
  }
  return search;
}

/**
 * One bucket as the server stacks it: the value its rows share, how many rows share it,
 * and the rows of them this page reached.
 *
 * `count` and `tickets.length` are allowed to differ, and that is the whole reason the
 * wire carries groups rather than a flat list. A header reading `Todo · 29` above twenty
 * loaded rows is true; counting the page could only ever have said `Todo · 20`.
 *
 * `key` is the empty string for the rows that have none — nobody assigned, no project —
 * and for the single bucket `groupBy: "none"` answers with, which is the spelling
 * `organise/grouping.ts` has always used for it. There is no `label`: `Todo`,
 * `A. Okonkwo` and `Design system` are what a screen calls these keys, and the screen is
 * where the names already are.
 */
export type TicketGroup = { key: string; count: number; tickets: Ticket[] };

/** A grouped answer, and the stacking and sort it was answered under. */
export type TicketGroups = {
  groupBy: ViewGroupBy;
  sortBy: ViewSortBy;
  /** The sum of the buckets, so the two numbers on screen cannot disagree. */
  total: number;
  groups: TicketGroup[];
};

export type SavedView = {
  id: string;
  teamId: string;
  name: string;
  shared: boolean;
  filters: ViewFilters;
  groupBy: ViewGroupBy;
  sortBy: ViewSortBy;
  createdBy?: string;
  /** How many rows it answers with right now — the number in the sidebar. */
  count: number;
};

// --- bulk edit ------------------------------------------------------------

export type BulkEdit = {
  ticketIds: string[];
  status?: TicketStatus;
  priority?: TicketPriority;
  assigneeIds?: string[];
  cycleId?: string;
  /** Added to every selected row, not replacing what they wear — see `BulkEdit.kt`. */
  labelId?: string;
};

// --- workload -------------------------------------------------------------

export type WorkloadRow = {
  /** Absent for the unassigned bucket, which is a pile and not an account. */
  person?: Person;
  total: number;
  /** The points of their open tickets. Unsized ones are not in it, and not zeroes. */
  points: number;
  /** How many of `total` carry no estimate — what `points` cannot speak for. */
  unestimated: number;
  byStatus: Record<string, number>;
  urgentOverThreeDays: number;
  oldestOpenDays: number;
};

export type Workload = { cycleId?: string; rows: WorkloadRow[] };

export const organiseApi = {
  cycles: (teamId: string) => request<Cycle[]>(`/api/teams/${teamId}/cycles`),

  /** Resolves the word `current`, which is what the sidebar's Cycle row links to. */
  currentCycle: (teamId: string) => request<CycleReport>(`/api/teams/${teamId}/cycles/current`),

  cycleByNumber: (teamId: string, number: number) =>
    request<CycleReport>(`/api/teams/${teamId}/cycles/by-number/${number}`),

  cycleReport: (id: string) => request<CycleReport>(`/api/cycles/${id}`),

  createCycle: (
    teamId: string,
    body: { number: number; startsOn: string; endsOn: string; state?: CycleState },
  ) => request<Cycle>(`/api/teams/${teamId}/cycles`, { method: "POST", body: JSON.stringify(body) }),

  /** Idempotent, and it takes the tickets out of whichever cycle they were in. */
  placeInCycle: (id: string, ticketIds: string[]) =>
    request<CycleReport>(`/api/cycles/${id}/tickets`, {
      method: "PUT",
      body: JSON.stringify({ ticketIds }),
    }),

  triage: (teamId: string, limit = 50) =>
    request<TriageQueue>(`/api/teams/${teamId}/triage${query({ limit })}`),

  triageDecisions: (teamId: string) =>
    request<TriageRuling[]>(`/api/teams/${teamId}/triage/decisions`),

  similar: (ticketId: string, limit = 5) =>
    request<SimilarTicket[]>(`/api/tickets/${ticketId}/similar${query({ limit })}`),

  decide: (body: { ticketId: string; decision: TriageDecision; duplicateOfId?: string }) =>
    request<TriageRuling>("/api/triage/decisions", { method: "POST", body: JSON.stringify(body) }),

  /**
   * The main list, asked with the saved view's vocabulary — an unsaved saved view.
   *
   * `api.tickets` in `core.ts` stays exactly as it is and keeps working: it sends
   * `teamId`, `includeDescendants`, `projectId` and `includeArchived`, which the server
   * still answers because the old names are aliases over these same filters. This is the
   * door for the facets that door cannot spell.
   */
  ticketsMatching: (
    filters: ViewFilters,
    scope: {
      teamId?: string;
      includeDescendants?: boolean;
      includeArchived?: boolean;
      sort?: ViewSortBy;
      limit?: number;
      offset?: number;
    } = {},
  ) => {
    const search = filterParams(filters);
    for (const [key, value] of Object.entries(scope)) {
      if (value !== undefined) search.set(key, String(value));
    }
    const encoded = search.toString();
    return request<Ticket[]>(`/api/tickets${encoded ? `?${encoded}` : ""}`);
  },

  /**
   * The same list, stacked by the server — and the reason a group header's count is a
   * fact about the team rather than about the fetch.
   *
   * Its own call rather than a flag on `ticketsMatching`: the two answer different
   * shapes, and one function whose return type depended on an argument would make every
   * caller branch on it.
   */
  groupedTickets: (
    filters: ViewFilters,
    scope: {
      teamId?: string;
      includeDescendants?: boolean;
      includeArchived?: boolean;
      groupBy?: ViewGroupBy;
      sort?: ViewSortBy;
      limit?: number;
      offset?: number;
    } = {},
  ) => {
    const search = filterParams(filters);
    for (const [key, value] of Object.entries(scope)) {
      if (value !== undefined) search.set(key, String(value));
    }
    const encoded = search.toString();
    return request<TicketGroups>(`/api/tickets/grouped${encoded ? `?${encoded}` : ""}`);
  },

  /**
   * The facets the server will answer, from the server.
   *
   * There is no second list of them in this file and there must not be: `ViewFilters`
   * above is a *type*, checked at compile time against a server that may since have been
   * rolled back, and a control built from a list typed out here would offer a chip the
   * server 400s on. This is what the "add a filter" control reads instead — see
   * `components/organise/facets.ts`.
   */
  servedFilters: () => request<{ served: string[] }>("/api/tickets/filters"),

  views: (teamId: string) => request<SavedView[]>(`/api/teams/${teamId}/views`),

  view: (id: string) => request<SavedView>(`/api/views/${id}`),

  /**
   * The view's rows, stacked the way the view stores. No `groupBy` parameter: the
   * stacking is part of the stored question, and a caller that could override it would
   * be asking a different one under this view's name.
   */
  viewGroups: (id: string, limit = 200) =>
    request<TicketGroups>(`/api/views/${id}/grouped${query({ limit })}`),

  createView: (
    teamId: string,
    body: {
      name: string;
      shared?: boolean;
      filters?: ViewFilters;
      groupBy?: ViewGroupBy;
      sortBy?: ViewSortBy;
    },
  ) => request<SavedView>(`/api/teams/${teamId}/views`, { method: "POST", body: JSON.stringify(body) }),

  patchView: (
    id: string,
    body: Partial<{
      name: string;
      shared: boolean;
      filters: ViewFilters;
      groupBy: ViewGroupBy;
      sortBy: ViewSortBy;
    }>,
  ) => request<SavedView>(`/api/views/${id}`, { method: "PATCH", body: JSON.stringify(body) }),

  deleteView: (id: string) => request<void>(`/api/views/${id}`, { method: "DELETE" }),

  bulkEdit: (edit: BulkEdit) =>
    request<{ changed: number }>("/api/tickets/bulk", { method: "POST", body: JSON.stringify(edit) }),

  bulkDelete: (ticketIds: string[]) =>
    request<{ changed: number }>("/api/tickets/bulk/delete", {
      method: "POST",
      body: JSON.stringify({ ticketIds }),
    }),

  workload: (teamId: string, cycleId?: string) =>
    request<Workload>(`/api/teams/${teamId}/workload${query({ cycleId })}`),
};
