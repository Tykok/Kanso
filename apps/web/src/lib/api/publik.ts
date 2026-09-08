import { API_URL, ApiError, type StatusCategory, type TicketStatus } from "./core";

/**
 * The client for the three routes that answer without a session — and the one client
 * in the app that deliberately sends no credentials.
 *
 * `core.ts`'s `request` attaches the session cookie and, in dev mode, an
 * `X-Kanso-User` header. Reusing it here would work, and would also mean the public
 * pages are only ever exercised as a signed-in reader: the first time a stranger
 * actually loaded one, whatever the session was quietly supplying would be missing.
 * `credentials: "omit"` makes the anonymous path the only path, so the browser cannot
 * hide a broken assumption behind a cookie somebody happened to have.
 */
async function open<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    credentials: "omit",
    headers: { "Content-Type": "application/json", ...init.headers },
  });

  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(response.status, problem?.detail ?? response.statusText, problem);
  }
  return response.json() as Promise<T>;
}

/** No id, only `KAN-142`: a public surface never prints a UUID. See `PublicDtos.kt`. */
export type RoadmapEntry = {
  key: string;
  title: string;
  status: TicketStatus;
  /**
   * What that key means — carried, not derivable — `KAN-90`.
   *
   * `categoryOf` is a map over Kanso's six and answers `undefined` for a word a team
   * invented, silently. A public page has no team to fetch a catalogue from, so the
   * meaning travels with the row.
   */
  category: StatusCategory;
  votes: number;
  /** Set only on a delivered ticket. */
  deliveredAt?: string;
};

export type RoadmapGroup = {
  /**
   * The column's **category** — `KAN-90`. `backlog`, `unstarted`, `started`, `completed`.
   *
   * Named `category` and not `status`, because the value is one: this field held a status
   * key and a table of statuses was looked up in it, which answered `undefined` and drew a
   * column with no heading at all. Measured by `22-public.spec.ts`.
   */
  category: StatusCategory;
  count: number;
  tickets: RoadmapEntry[];
};

export type Roadmap = { groups: RoadmapGroup[] };

export type FilePointer = { path: string; note?: string };

/** A name and a membership. The server sends nothing else about a person. */
export type Helper = { displayName: string; role: "member" | "admin" };

export type ContributorPage = {
  key: string;
  title: string;
  explanation?: string;
  status: TicketStatus;
  /**
   * What that key means — carried, not derivable — `KAN-90`.
   *
   * `categoryOf` is a map over Kanso's six and answers `undefined` for a word a team
   * invented, silently. A public page has no team to fetch a catalogue from, so the
   * meaning travels with the row.
   */
  category: StatusCategory;
  votes: number;
  unclaimed: boolean;
  /** The ticket's own labels, by name. No id and no colour — see `PublicDtos.kt`. */
  labels: string[];
  whereToLook: FilePointer[];
  helpers: Helper[];
  otherFirstSteps: RoadmapEntry[];
  /**
   * The label the list of first steps was narrowed to, or absent when no team has defined
   * one and it is every unclaimed ticket instead. The eyebrow says which it was.
   */
  firstStepLabel?: string;
  /** How many there are to pick up, this one included when it qualifies. */
  availableCount: number;
};

export type VoteResult = { votes: number; voted: boolean };

/**
 * `KAN-142` split for a two-segment path, the shape `/api/tickets/by-key` already uses.
 * On the *last* dash: a team key cannot contain one, but assuming the first would break
 * the day one does, and this is the function that would be blamed for it.
 */
export function splitKey(key: string): { teamKey: string; number: string } {
  const cut = key.lastIndexOf("-");
  return cut === -1
    ? { teamKey: key, number: "" }
    : { teamKey: key.slice(0, cut), number: key.slice(cut + 1) };
}

const path = (key: string) => {
  const { teamKey, number } = splitKey(key);
  return `/api/public/roadmap/${encodeURIComponent(teamKey)}/${encodeURIComponent(number)}`;
};

export const publicApi = {
  roadmap: () => open<Roadmap>("/api/public/roadmap"),
  contributorPage: (key: string) => open<ContributorPage>(path(key)),
  vote: (key: string) => open<VoteResult>(`${path(key)}/vote`, { method: "POST" }),
};
