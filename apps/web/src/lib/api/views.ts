import { API_URL, ApiError, getDevUser, type Preferences, type Ticket } from "./core";

/**
 * Slice A's read client: the four screens that draw work the app already stores.
 *
 * Every path here is a `GET`. The slice adds no migration and no write endpoint — the
 * ticket page, the board, the project page and the search all read rows some other
 * surface already creates — which is what lets this file be a reader and nothing more.
 */

// --- the request -------------------------------------------------------------

/**
 * A `GET`, spelled out here because `core.ts`'s own `request` is module-private and
 * this branch may not widen `core.ts` to export it.
 *
 * The three things that make a request Kanso's are reused rather than restated:
 * [API_URL], [getDevUser] — without which every call in dev mode would answer as
 * whoever the cookie happens to name instead of the person the switcher chose — and
 * [ApiError], so a failure here is caught by the same `instanceof` every other screen
 * already uses. `credentials: "include"` for the same reason `core.ts` gives: the
 * session cookie is the only credential there is.
 */
async function get<T>(path: string): Promise<T> {
  const devUser = getDevUser();
  const response = await fetch(`${API_URL}${path}`, {
    credentials: "include",
    headers: devUser ? { "X-Kanso-User": devUser } : {},
  });

  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(response.status, problem?.detail ?? response.statusText, problem);
  }
  return response.json() as Promise<T>;
}

// --- the ticket page ---------------------------------------------------------

/** A parsed `KAN-142`: the two path segments `GET /api/tickets/by-key/{key}/{n}` wants. */
export type TicketKey = { teamKey: string; number: number };

/**
 * `TeamService.KEY_PATTERN` is `^[A-Z0-9]{2,8}$` and a number starts at one, so an
 * identifier is exactly this and a URL that is not is a bad link rather than an empty
 * result. Anchored and case-insensitive: the key is stored uppercase, and a link that
 * has been through a mail client or an address bar arrives in whatever case it likes.
 */
const KEY_SHAPE = /^([A-Za-z0-9]{2,8})-([1-9]\d*)$/;

/** [key] as the two segments the endpoint takes, or `null` when it is not one. */
export function parseTicketKey(key: string): TicketKey | null {
  const match = KEY_SHAPE.exec(key);
  return match ? { teamKey: match[1].toUpperCase(), number: Number(match[2]) } : null;
}

/**
 * Where a ticket lives at page width. One function rather than a template literal at
 * each of the four call sites — the board card, the palette row, the project row and
 * the breadcrumb — so the route and the identifier cannot drift apart.
 */
export const ticketHref = (identifier: string) => `/t/${identifier}`;

// --- how `↵` opens a ticket --------------------------------------------------

export const OPEN_TICKET_MODES = ["panel", "page"] as const;
export type OpenTicketMode = (typeof OPEN_TICKET_MODES)[number];

/** The panel, because it is the answer that cannot navigate away from unsaved work. */
export const DEFAULT_OPEN_TICKET: OpenTicketMode = "panel";

/**
 * `Preferences` as it reads once slice 0's `user_preferences.open_ticket` lands.
 *
 * Screen 02 says in so many words that the setting "lives in the preferences", and it
 * does not yet: the column is slice 0's, `core.ts` is not slice A's to edit, and the
 * field is optional here so that a `Preferences` from a server that predates the column
 * is still a valid argument. Integration moves `openTicket` onto `Preferences` itself
 * and this alias becomes a synonym nobody has to delete in a hurry.
 */
export type OpenTicketPreferences = Preferences & { openTicket?: OpenTicketMode };

/**
 * Which drawing `↵` gives, from the preferences, answering `"panel"` both for a stored
 * `'panel'` and for a preferences object that has never heard of the column.
 */
export function openTicketMode(preferences: OpenTicketPreferences): OpenTicketMode {
  // Compared against the vocabulary rather than cast to it: a value outside the closed
  // set is a server that drifted, and the panel is the answer that cannot lose work.
  return preferences.openTicket === "page" ? "page" : DEFAULT_OPEN_TICKET;
}

// --- the project page's feed -------------------------------------------------

/**
 * The closed vocabulary of `activity.kind`, verbatim from slice 0's `V8` check
 * constraint. Restated rather than imported because the column and this list are two
 * sides of one contract and the database is the side that refuses an unknown value.
 */
export const ACTIVITY_KINDS = [
  "created",
  "status_changed",
  "priority_changed",
  "assigned",
  "unassigned",
  "renamed",
  "scheduled",
  "archived",
  "commented",
  "labelled",
  "mirror_pushed",
] as const;

export type ActivityKind = (typeof ACTIVITY_KINDS)[number];

/** Who did it, as the feed prints them. `null` for a row whose actor was deleted. */
export type ActivityActor = { id: string; displayName: string };

/**
 * One row of the log. `payload` carries the before and after of a scalar change and
 * nothing else, so the feed reads it by name and never assumes a shape.
 */
export type ActivityRow = {
  id: string;
  entityType: "ticket" | "project" | "team" | "doc";
  entityId: string;
  actor: ActivityActor | null;
  kind: ActivityKind;
  payload: Record<string, unknown>;
  createdAt: string;
};

/** A Notion page Kanso references but never authors — `GET /api/docs`, unchanged. */
export type Doc = { id: string; notionPageId: string; title?: string; url?: string };

export const viewsApi = {
  /**
   * The ticket page's own resolution. By key rather than by id because the URL is the
   * identifier people read out loud, and a UUID in the address bar is not a link
   * anybody can dictate.
   */
  ticketByKey: ({ teamKey, number }: TicketKey) =>
    get<Ticket>(`/api/tickets/by-key/${encodeURIComponent(teamKey)}/${number}`),

  /**
   * Newest first — the index is `created_at DESC` and every reader of this list draws a
   * feed. Slice 0's endpoint; if it is not deployed yet this 404s, which is why the
   * hook asking for it does not retry and the feed says nothing rather than erroring.
   */
  activity: (entityType: ActivityRow["entityType"], entityId: string) =>
    get<ActivityRow[]>(
      `/api/activity?entityType=${encodeURIComponent(entityType)}&entityId=${encodeURIComponent(entityId)}`,
    ),

  docs: () => get<Doc[]>("/api/docs"),
};
