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
 * The facets the server actually matches on — `SavedViewService.SERVED_FILTERS`, key for
 * key. A key this type carries that the set does not is a 400 on the write, not a chip.
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

  views: (teamId: string) => request<SavedView[]>(`/api/teams/${teamId}/views`),

  view: (id: string) => request<SavedView>(`/api/views/${id}`),

  viewTickets: (id: string) => request<Ticket[]>(`/api/views/${id}/tickets`),

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
