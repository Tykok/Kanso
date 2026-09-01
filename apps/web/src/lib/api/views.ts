import {
  API_URL,
  ApiError,
  getDevUser,
  OPEN_TICKET,
  type OpenTicket,
  type Preferences,
  type Ticket,
  type User,
} from "./core";
import { socialApi } from "./social";

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
 * A UUID, which is the other thing `/t/{key}` can be handed.
 *
 * It cannot be mistaken for an identifier: [KEY_SHAPE] admits at most eight characters
 * before the dash and digits after it, and this has four dashes and hex on both sides. So
 * one route serves both without the two ever having to be told apart by anything but their
 * own shape.
 */
const ID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const isTicketId = (key: string) => ID_SHAPE.test(key);

/**
 * What goes in the URL for this ticket.
 *
 * The identifier when it has one, because that is the half of a link somebody can read
 * out loud. Its id when it does not: a ticket no team has claimed has no identifier, and
 * the id is the address that never changes — including across the moment it gains a team
 * and is named for the first time, which is precisely when a link made of the identifier
 * would not have existed to break.
 */
export const ticketAddress = (ticket: { id: string; identifier?: string | null }) =>
  ticket.identifier ?? ticket.id;

/**
 * Where a ticket lives at page width. One function rather than a template literal at
 * each of the four call sites — the board card, the palette row, the project row and
 * the breadcrumb — so the route and the identifier cannot drift apart.
 */
export const ticketHref = (address: string) => `/t/${address}`;

// --- how `↵` opens a ticket --------------------------------------------------

/**
 * Slice A's names for the preference, kept as aliases of the ones `core.ts` carries now
 * that `user_preferences.open_ticket` has landed. Two vocabularies for one closed set is
 * how they drift; one of them being a synonym of the other is not.
 */
export const OPEN_TICKET_MODES = OPEN_TICKET;
export type OpenTicketMode = OpenTicket;

/** The panel, because it is the answer that cannot navigate away from unsaved work. */
export const DEFAULT_OPEN_TICKET: OpenTicketMode = "panel";

/**
 * Was `Preferences & { openTicket?: … }` while the column was still slice 0's to write.
 * The column exists, `Preferences` carries it, and this is the synonym the branch's own
 * comment predicted would be left behind.
 */
export type OpenTicketPreferences = Preferences;

/**
 * Which drawing `↵` gives, from the preferences, answering `"panel"` both for a stored
 * `'panel'` and for a preferences object from a server that predates the column.
 */
export function openTicketMode(preferences: OpenTicketPreferences): OpenTicketMode {
  // Compared against the vocabulary rather than cast to it: a value outside the closed
  // set is a server that drifted, and the panel is the answer that cannot lose work.
  return preferences.openTicket === "page" ? "page" : DEFAULT_OPEN_TICKET;
}

// --- the project page's feed -------------------------------------------------

/**
 * The log's shapes live in `api/social.ts`, beside the endpoint that serves them — slice A
 * restated them because `V8` had not landed when this branch was cut, and two definitions
 * of one wire shape is exactly the drift the split was supposed to prevent.
 *
 * `ActivityActor` stays as a name because the feed reads only two of its fields, but it is
 * the `User` the server actually sends rather than a narrower shape nothing enforces.
 */
export type { ActivityKind, ActivityRow } from "./social";
export { ACTIVITY_KINDS } from "./social";
export type ActivityActor = User;

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
   * The other resolution, for a ticket with no identifier to be read out loud. Same page,
   * same shape back; only the address differs.
   */
  ticketById: (id: string) => get<Ticket>(`/api/tickets/${encodeURIComponent(id)}`),

  /**
   * Newest first. Delegates rather than re-issuing the request: `socialApi` owns the
   * endpoint, and a second spelling of one URL is a second thing to fix when it moves.
   */
  activity: socialApi.activity,

  docs: () => get<Doc[]>("/api/docs"),
};
