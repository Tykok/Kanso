// Type-only, so nothing of the store reaches the runtime bundle: the tickets
// endpoint is shaped by the scope, and restating that union here would let the
// two drift.
import type { Scope } from "@/store/ui";

export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export const TICKET_STATUSES = [
  "backlog",
  "todo",
  "in_progress",
  "in_review",
  "done",
  "canceled",
] as const;

export const TICKET_PRIORITIES = ["none", "low", "medium", "high", "urgent"] as const;

/**
 * `ProjectStatus`, server side (`domain/Model.kt`). Here rather than in the project
 * dialog, which used to hold it: the Notion import's columns step offers the same choice
 * when it maps a base of projects, and two copies of a closed vocabulary are two things
 * to keep in step with one enum.
 */
export const PROJECT_STATUSES = ["planned", "in_progress", "paused", "completed", "canceled"] as const;

/**
 * `ProjectHealth`, server side. Whether a project will land — a different question from
 * `PROJECT_STATUSES`, which says where its work is, and never derived from it: a project
 * can be `in_progress` and `off_track` at the same time, and that pair is the single most
 * useful thing this vocabulary can say.
 *
 * Three values and no fourth. A project nobody has assessed has `health: undefined`, which
 * is **not** `on_track` — "nobody has said" and "somebody said it is fine" are different
 * facts, and a client that draws the first as the second turns every project green on the
 * day the feature ships. Absence is drawn as absence everywhere below.
 */
export const PROJECT_HEALTHS = ["on_track", "at_risk", "off_track"] as const;

/**
 * The effort scale, and the whole of it: a truncated Fibonacci sequence the server
 * refuses anything outside of, in Kotlin and again by `tickets_estimate_chk`. Restated
 * here rather than fetched because it is a vocabulary, not data — the same reason
 * `TICKET_STATUSES` is a literal — and because a `<select>` has to be built from it
 * before any ticket has been loaded.
 *
 * Absent is not zero anywhere in this app: `estimate` is `undefined` for a ticket nobody
 * has sized, cleared by naming it in `unset`, and left out of every sum.
 */
export const EFFORT_POINTS = [1, 2, 3, 5, 8, 13] as const;
export type EffortPoints = (typeof EFFORT_POINTS)[number];

export type TicketStatus = (typeof TICKET_STATUSES)[number];
export type TicketPriority = (typeof TICKET_PRIORITIES)[number];
export type ProjectStatus = (typeof PROJECT_STATUSES)[number];
export type ProjectHealth = (typeof PROJECT_HEALTHS)[number];
export type SyncState = "pending" | "synced" | "failed" | "disabled";

export type Mirror = {
  notionPageId?: string;
  state: SyncState;
  syncedAt?: string;
};

/**
 * A date from the API. `hasTime: false` means the value names a *day*, not a
 * moment: render it without timezone conversion, or a reader west of UTC sees the
 * previous day. `at` is always a full instant so the server can do arithmetic on it.
 */
export type KansoInstant = { at: string; hasTime: boolean };

/**
 * The `YYYY-MM-DD` an `<input type="date">` wants, taken by slicing the ISO string.
 * Never `new Date(instant.at)`: that converts, and a day must not be converted.
 */
export const dayValue = (instant: KansoInstant | null | undefined): string =>
  instant ? instant.at.slice(0, 10) : "";

/** The inverse: a date input's value as a floating instant. */
export const fromDayValue = (value: string): KansoInstant | null =>
  value ? { at: `${value}T00:00:00Z`, hasTime: false } : null;

export type Ticket = {
  id: string;
  /**
   * `KAN-142`, and absent for a ticket no team has claimed yet. The identifier is a team
   * key and that team's counter, so a ticket outside every team has no name to print — the
   * card draws a "no team" badge where this would have gone, and [id] is what addresses it
   * until somebody files it.
   *
   * Optional rather than `| null`, like every other absent field on this row: the server
   * omits nulls, so what arrives is `undefined` and a `=== null` test would silently miss
   * every draft. The helpers that read these three use `== null` for the same reason.
   */
  identifier?: string;
  number?: number;
  teamId?: string;
  title: string;
  description?: string;
  status: TicketStatus;
  priority: TicketPriority;
  /** Points. Absent means nobody has sized it — never 0, which would be a real estimate. */
  estimate?: EffortPoints;
  start?: KansoInstant;
  due?: KansoInstant;
  projectId?: string;
  assigneeIds: string[];
  docIds: string[];
  archived: boolean;
  mirror: Mirror;
  createdAt: string;
  updatedAt: string;
};

export type Team = {
  id: string;
  name: string;
  key: string;
  parentTeamId?: string;
  archived: boolean;
  /**
   * Tickets ever filed in this team, not tickets it has — the server sends
   * `ticket_counter`, the allocator that makes KAN-14 the fourteenth, so it climbs on a
   * create and never comes back down on a delete.
   *
   * Which is the right answer for its one reader: `emptyReason` asks whether anything has
   * ever been filed anywhere, and an instance whose work has all been deleted is not on a
   * first run. Read it as a high-water mark and not as a counter to draw beside a name.
   */
  ticketCount: number;
  mirror: Mirror;
  /** The server's answer to "may this actor create a ticket here", from `TicketAccess`. */
  editable: boolean;
};

/** Wire values from `dev.kanso.domain.MemberRole`; nothing here names "lead". */
export type MemberRole = "member" | "admin";

export type TeamMemberRow = { user: User; role: MemberRole };

export type Project = {
  id: string;
  name: string;
  status: string;
  start?: KansoInstant;
  end?: KansoInstant;
  leadUserId?: string;
  teamId?: string;
  /**
   * The newest update's health, derived server-side and absent when nobody has posted
   * one. Read-only: it is not on `ProjectBody`, because there is no column to write —
   * changing a project's health means posting an update, which is a different endpoint
   * and a different permission.
   */
  health?: ProjectHealth;
  archived: boolean;
  mirror: Mirror;
};

/** One thing somebody said about how a project is going, on the date they said it. */
export type ProjectUpdate = {
  id: string;
  projectId: string;
  health: ProjectHealth;
  body: string;
  /** Null once the account is gone. The assessment it left behind is not. */
  author: User | null;
  at: string;
};

export type ProjectBody = {
  name: string;
  status?: string;
  /** Null clears the bound; the PUT replaces the project wholesale either way. */
  start?: KansoInstant | null;
  end?: KansoInstant | null;
  leadUserId?: string;
  teamId?: string;
};

// --- timeline ----------------------------------------------------------------

/** A project bound, plus whether anyone posted it — a derived one is not editable. */
export type TimelineBound = KansoInstant & { derived: boolean };

export type TimelineProject = {
  id: string;
  name: string;
  start?: TimelineBound;
  end?: TimelineBound;
};

export type TimelineTicket = {
  id: string;
  identifier: string;
  title: string;
  projectId?: string;
  status: TicketStatus;
  start?: KansoInstant;
  due?: KansoInstant;
  /** Absent for a ticket with no dependencies: it has no slack to report. */
  slackMinutes?: number;
  critical: boolean;
  late: boolean;
  /** Whose ticket this is, printed before the identifier on a context row. */
  teamKey: string;
  /** Drawn for reading: outside the scope, not selectable, never draggable. */
  context: boolean;
  /** The server's answer to "may this viewer move it". Never re-derived here. */
  editable: boolean;
};

export type TimelineDependency = {
  predecessorId: string;
  successorId: string;
  /** The cascade cannot repair this: the successor is done and starts too early. */
  violated: boolean;
  /** Broken now and repairable by moving the successor. Exclusive with `violated`. */
  overlap: boolean;
  /** The other end is outside this response, so the arrow is drawn as a stub. */
  outOfScope: boolean;
};

export type TimelineUnscheduled = { id: string; identifier: string; title: string };

export type TimelineView = {
  projects: TimelineProject[];
  tickets: TimelineTicket[];
  dependencies: TimelineDependency[];
  unscheduled: TimelineUnscheduled[];
  /** The scope hit `SCOPE_LIMIT`, so bars are missing and the chart has to say so. */
  truncated: boolean;
};

/** What a dependency write returns: the tickets its cascade moved. */
export type CascadeResult = { movedTicketIds: string[] };

/** What happens to what a team or a project holds when the container goes away. */
export type DispositionChoice = "take" | "keep";

export type DispositionCounts = { subTeams: number; projects: number; tickets: number };

/**
 * Both readings of what a container holds, because the plan decides which one is
 * true. `subTeams: "keep"` lets the sub-teams leave first with their own contents
 * untouched, so the operation reaches this container alone — `direct`.
 * `subTeams: "take"` takes the whole subtree, and every project and ticket in it is
 * destroyed, archived or renumbered — `subtree`. The two coincide for a project,
 * which holds no teams, and for a team with no children.
 */
export type DispositionContents = { direct: DispositionCounts; subtree: DispositionCounts };

/**
 * `counts` is what the modal displayed. Deleting sends it so the server can refuse
 * on drift; archiving may omit it, because archiving comes back.
 */
export type DispositionPlan = {
  subTeams: DispositionChoice;
  projects: DispositionChoice;
  tickets: DispositionChoice;
  ticketsTargetTeamId?: string;
  counts?: DispositionCounts;
};

export type InstanceRole = "owner" | "admin" | "member" | "viewer";

export type User = {
  id: string;
  email: string;
  displayName: string;
  avatarUrl?: string;
  notionPersonId?: string;
  instanceRole: InstanceRole;
  /** The ways this account can sign in. The UI refuses to remove the last one. */
  hasPassword: boolean;
  linkedProvider?: string;
};

export type AuthMode = {
  mode: "oidc" | "dev";
  /** Local email + password sign-in, available once an owner account exists. */
  passwordLoginEnabled: boolean;
  providers: { id: string; label: string; authorizeUrl: string }[];
};

// --- preferences -------------------------------------------------------------

export const THEMES = ["system", "light", "dark"] as const;
export const ACCENTS = ["indigo", "blue", "green", "amber", "rose", "violet"] as const;
export const DENSITIES = ["comfortable", "compact"] as const;

export type Theme = (typeof THEMES)[number];
export type Accent = (typeof ACCENTS)[number];
export type Density = (typeof DENSITIES)[number];

export const OPEN_TICKET = ["panel", "page"] as const;
export type OpenTicket = (typeof OPEN_TICKET)[number];

export type Preferences = {
  theme: Theme;
  accent: Accent;
  density: Density;
  /**
   * What `↵` on a row does: open the panel, or navigate to the ticket's own page.
   *
   * Screen 02 of the design bundle says in as many words that this setting "lives in the
   * preferences", and until slice 0 it did not. `⤢` and `⇧↵` expand the current ticket
   * without changing it — the preference is the default, not the only way through.
   */
  openTicket: OpenTicket;
  sidebarVisible: boolean;
  showSyncBadges: boolean;
  showStatusBar: boolean;
  defaultTeamId?: string;
  /** Set once the user has been through (or skipped) the preferences step. */
  onboardedAt?: string;
  /**
   * Points per working day, as this person estimates their own pace. Absent means they
   * never said — never `0`, which would be a claim that they deliver nothing.
   *
   * A seed, not a setting: once two of their team's cycles have closed, Kanso plans with
   * the measured number instead and keeps this one beside it as a reference. Whether it
   * is currently in force is `EffectiveVelocity.source`, not something to work out here.
   */
  declaredVelocity?: number;
};

export const DEFAULT_PREFERENCES: Preferences = {
  theme: "system",
  accent: "indigo",
  density: "comfortable",
  openTicket: "panel",
  sidebarVisible: true,
  showSyncBadges: true,
  showStatusBar: true,
};

// --- velocity ----------------------------------------------------------------

export const VELOCITY_SOURCES = ["declared", "measured", "none"] as const;
export type VelocitySource = (typeof VELOCITY_SOURCES)[number];

/**
 * One person's pace, with the arbitration already done.
 *
 * [source] is the whole reason this type exists rather than two loose numbers. The server
 * decides which of the declared and the measured value is in force; re-deriving that here
 * would be a second copy of the rule, free to disagree with the first the day it moves.
 *
 * Both numbers are always carried, whichever won: the screen shows the loser beside the
 * winner, and a lasting gap between them is information rather than an error.
 *
 * `perWorkingDay` is null exactly when `source` is `none` — null, never 0, because 0 is a
 * measurement ("delivers nothing") and null is the absence of one.
 */
export type EffectiveVelocity = {
  /** Absent — never `0` — when `source` is `none`. The API omits nulls rather than sending them. */
  perWorkingDay?: number;
  source: VelocitySource;
  declared?: number;
  measured?: number;
  /** How much history `measured` stands on. Zero means it could not be measured at all. */
  measuredCycles: number;
  /** Closed cycles still needed before the measurement takes over. Zero once it has. */
  cyclesUntilMeasured: number;
};

/**
 * How long a ticket should take, or which of three reasons Kanso will not say.
 *
 * A discriminated union on `basis`, so the four cases are four branches the compiler
 * counts. The three absences are deliberately not one nullable range: a screen that cannot
 * tell "nobody sized this" from "nobody is on this" points the reader at the wrong fix,
 * and an empty field reads as something that failed to load.
 *
 * There is no point estimate anywhere in this type, only the two ends. A field holding the
 * un-widened number would get printed, and a bare date off a three-cycle mean is exactly
 * what the range exists to prevent.
 */
export type TicketDuration =
  | {
      basis: "estimated";
      lowWorkingDays: number;
      highWorkingDays: number;
      points: number;
      assignees: number;
      /** Assignees with no known pace, and so the amount this range overstates by. */
      withoutVelocity: number;
    }
  // Carries nothing but the reason. The API omits nulls, so the four numbers are not
  // absent-and-null here — they are not on the wire at all, and the union is what makes
  // reaching for one a compile error rather than a `NaN` on the screen.
  | { basis: "no_estimate" | "no_assignee" | "no_velocity" };

/** Preferences travel with the session so the first paint needs one round trip, not two. */
export type Me = {
  user: User;
  teamIds: string[];
  preferences: Preferences;
  /** The API's build version. Compared against WEB_VERSION: a skew is worth seeing. */
  version: string;
};

// --- first-run setup ---------------------------------------------------------

export type IntegrationState = {
  configured: boolean;
  /**
   * Set from the environment rather than the wizard. The UI shows it read-only:
   * letting both write the same setting is how they end up disagreeing.
   */
  managedByEnvironment: boolean;
};

export type SetupState = {
  /** No owner yet: this instance has never been set up. */
  needsOwner: boolean;
  setupCompletedAt?: string;
  notion: IntegrationState & {
    parentPageId?: string;
    bootstrapped: boolean;
    /**
     * Whether a public integration exists for consent to be asked through — which is a
     * different question from `configured`. The pair makes the Connect button possible;
     * a token makes the mirror work. An instance can have either without the other, and
     * the screen has to tell "nothing set up" from "set up, nobody has consented yet".
     */
    appConfigured: boolean;
    /**
     * The integration comes from `NOTION_CLIENT_ID` / `NOTION_CLIENT_SECRET` rather than
     * from this screen. The opposite of `managedByEnvironment` in what it hides: a pinned
     * token leaves nothing to connect, a pinned integration leaves nothing to type — the
     * button is precisely what remains.
     */
    appManagedByEnvironment: boolean;
    /** The workspace a completed consent named. Absent when the token was pasted. */
    workspaceName?: string;
  };
  google: IntegrationState & { clientId?: string };
};

/** One page the integration can write under — what the parent-page picker offers. */
export type NotionParentPage = {
  id: string;
  /** Absent when the page has no title. Notion allows it; `pageLabel` names it. */
  title?: string;
  url?: string;
};

/**
 * What the picker gets back.
 *
 * Three states in one shape, and the step says something different about each:
 * `available: false` with a `reason` is "no workspace to search yet"; available with no
 * pages is "the integration exists and nobody has shared a page with it", which is the
 * silent failure the pasted id used to hide; available with pages is the list.
 */
export type NotionParentPages = {
  available: boolean;
  reason?: string;
  pages: NotionParentPage[];
};

export type InvitationLink = { url: string; expiresAt: string };

export type PendingInvitation = {
  id: string;
  email?: string;
  role: InstanceRole;
  createdAt: string;
  expiresAt: string;
  expired: boolean;
};

export type SyncStatus = {
  mirrorEnabled: boolean;
  bootstrapped: boolean;
  jobs: Record<string, number>;
  failed: { id: number; entity: string; entityId: string; attempts: number; error?: string }[];
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
    /**
     * The whole problem document. A 409 on a disposition carries the fresh
     * `counts` there, and the modal has to reopen on them.
     */
    readonly body?: unknown,
  ) {
    super(detail);
  }
}

/** Dev-mode identity, so one browser can act as several people while testing. */
const DEV_USER_KEY = "kanso.devUser";

export function getDevUser(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(DEV_USER_KEY);
}

export function setDevUser(email: string | null) {
  if (typeof window === "undefined") return;
  if (email) window.localStorage.setItem(DEV_USER_KEY, email);
  else window.localStorage.removeItem(DEV_USER_KEY);
}

/**
 * The one fetch every slice's client goes through.
 *
 * Exported rather than private because `api/core.ts` is no longer the only file that
 * talks to the API: each slice owns `api/<slice>.ts`. This is the single place that
 * attaches the session cookie, the dev-mode identity header and the `ApiError`
 * conversion, and re-implementing any of that per slice is how a screen ends up
 * silently unauthenticated. Three of the six branches copied it before this line
 * existed, which is the argument.
 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const devUser = getDevUser();
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    // The session cookie is the only credential; it also authenticates the
    // WebSocket handshake, so there is no token to juggle here.
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
export function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) search.set(key, String(value));
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : "";
}

export const api = {
  authMode: () => request<AuthMode>("/api/auth/mode"),
  me: () => request<Me>("/api/me"),
  logout: () => request<void>("/api/auth/logout", { method: "POST" }),

  login: (body: { email: string; password: string }) =>
    request<void>("/api/auth/login", { method: "POST", body: JSON.stringify(body) }),

  /** Public: the sign-in screen has to know whether this instance is set up yet. */
  setupState: () => request<SetupState>("/api/setup/state"),

  /** Claims the instance. Succeeds exactly once, whatever the client does. */
  createOwner: (body: { email: string; displayName: string; password: string }) =>
    request<Me>("/api/setup/owner", { method: "POST", body: JSON.stringify(body) }),

  /** The public integration's own credentials. Saving them connects nothing. */
  saveNotionApp: (body: { clientId: string; clientSecret?: string }) =>
    request<SetupState>("/api/setup/notion/app", { method: "POST", body: JSON.stringify(body) }),

  /**
   * Answers with the consent URL rather than redirecting to it: a redirect would be
   * followed by `fetch` and land here as an opaque CORS failure. The caller navigates
   * the window itself.
   */
  startNotionConnect: () =>
    request<{ url: string; redirectUri: string }>("/api/setup/notion/authorize", {
      method: "POST",
    }),

  saveNotion: (body: { token?: string; parentPageId: string }) =>
    request<SetupState>("/api/setup/notion", { method: "POST", body: JSON.stringify(body) }),

  /** Round trip to Notion before saving, so a bad token is caught in the wizard. */
  testNotion: (body: { token?: string; parentPageId?: string }) =>
    request<{ ok: boolean; detail: string }>("/api/setup/notion/test", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  /**
   * The pages the parent page can be picked from, instead of typed.
   *
   * The token travels in a header because the picker has to work before anything is
   * saved — the same reason `testNotion` takes one — and a GET has no body to put it in.
   * A query parameter would print the secret into every access log there is.
   */
  notionPages: ({ token }: { token?: string } = {}) =>
    request<NotionParentPages>("/api/setup/notion/pages", {
      headers: token ? { "X-Notion-Token": token } : {},
    }),

  bootstrapNotion: () => request<SetupState>("/api/admin/notion/bootstrap", { method: "POST" }),

  saveGoogle: (body: { clientId: string; clientSecret: string }) =>
    request<SetupState>("/api/setup/google", { method: "POST", body: JSON.stringify(body) }),

  /**
   * Round trip to Google before saving, so a mistyped secret is caught here rather
   * than at the first attempt to sign in with it. Either field may be omitted to
   * check what is already stored.
   */
  testGoogle: (body: { clientId?: string; clientSecret?: string }) =>
    request<{ ok: boolean; detail: string }>("/api/setup/google/test", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  completeSetup: () => request<SetupState>("/api/setup/complete", { method: "POST" }),

  createInvitation: (body: { email?: string; role?: InstanceRole }) =>
    request<InvitationLink>("/api/setup/invitations", { method: "POST", body: JSON.stringify(body) }),

  acceptInvitation: (body: {
    token: string;
    email: string;
    displayName: string;
    password: string;
  }) => request<Me>("/api/auth/accept-invitation", { method: "POST", body: JSON.stringify(body) }),

  // --- account -------------------------------------------------------------

  renameMe: (displayName: string) =>
    request<User>("/api/me", { method: "PUT", body: JSON.stringify({ displayName }) }),

  changePassword: (body: { currentPassword: string; newPassword: string }) =>
    request<void>("/api/me/password", { method: "PUT", body: JSON.stringify(body) }),

  setNotionIdentity: (notionPersonId: string | null) =>
    request<User>("/api/me/notion-identity", {
      method: "PUT",
      body: JSON.stringify({ notionPersonId }),
    }),

  /** Refused by the server when it would leave no way to sign in. */
  unlinkProvider: (provider: string) =>
    request<User>(`/api/me/identities/${provider}`, { method: "DELETE" }),

  // --- people --------------------------------------------------------------

  people: () => request<User[]>("/api/people"),

  setRole: (userId: string, role: InstanceRole) =>
    request<User>(`/api/people/${userId}/role`, { method: "PUT", body: JSON.stringify({ role }) }),

  pendingInvitations: () => request<PendingInvitation[]>("/api/people/invitations"),

  revokeInvitation: (id: string) =>
    request<void>(`/api/people/invitations/${id}`, { method: "DELETE" }),

  preferences: () => request<Preferences>("/api/me/preferences"),

  /**
   * Your own, and only your own. A cycle is one team's calendar, so `teamId` is required
   * — somebody in two teams has two paces measured against two different fortnights and
   * picking one for them would show a number measured against the wrong one.
   */
  velocity: (teamId: string) => request<EffectiveVelocity>(`/api/me/velocity${query({ teamId })}`),

  /**
   * Beside the ticket rather than on it: this costs a walk of the team's closed cycles and
   * a preferences read per assignee, which a list of two hundred rows should not pay to
   * render something only the detail view draws.
   */
  ticketDuration: (id: string) => request<TicketDuration>(`/api/tickets/${id}/duration`),

  /**
   * `onboarded: true` stamps the moment the wizard was finished. It is a command,
   * not a field: `onboardedAt` is a server timestamp, and without this flag the
   * routing guard would send the user back into the wizard they just completed.
   */
  savePreferences: (body: Partial<Preferences> & { onboarded?: boolean }) =>
    request<Preferences>("/api/me/preferences", { method: "PUT", body: JSON.stringify(body) }),

  // --- teams ---------------------------------------------------------------

  teams: (includeArchived = false) => request<Team[]>(`/api/teams${query({ includeArchived })}`),

  createTeam: (body: { name: string; key?: string; parentTeamId?: string }) =>
    request<Team>("/api/teams", { method: "POST", body: JSON.stringify(body) }),

  /** Reparenting is this call too: the parent is just another field. */
  updateTeam: (id: string, body: { name: string; key?: string; parentTeamId?: string }) =>
    request<Team>(`/api/teams/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  teamContents: (id: string) => request<DispositionContents>(`/api/teams/${id}/contents`),

  archiveTeam: (id: string, plan: DispositionPlan) =>
    request<Team>(`/api/teams/${id}/archive`, { method: "PUT", body: JSON.stringify(plan) }),

  unarchiveTeam: (id: string) => request<Team>(`/api/teams/${id}/unarchive`, { method: "POST" }),

  deleteTeam: (id: string, plan: DispositionPlan) =>
    request<void>(`/api/teams/${id}`, { method: "DELETE", body: JSON.stringify(plan) }),

  teamMembers: (teamId: string) => request<TeamMemberRow[]>(`/api/teams/${teamId}/members`),

  addTeamMember: (teamId: string, userId: string, role: MemberRole) =>
    request<TeamMemberRow[]>(`/api/teams/${teamId}/members`, {
      method: "POST",
      body: JSON.stringify({ userId, role }),
    }),

  removeTeamMember: (teamId: string, userId: string) =>
    request<void>(`/api/teams/${teamId}/members/${userId}`, { method: "DELETE" }),

  // --- projects ------------------------------------------------------------

  /**
   * Called once with no team: the sidebar draws every project to build its tree,
   * and a query per team would be one request per row to render it.
   */
  projects: (opts: { teamId?: string; includeArchived?: boolean } = {}) =>
    request<Project[]>(
      `/api/projects${query({
        teamId: opts.teamId,
        includeDescendants: opts.teamId === undefined ? undefined : true,
        includeArchived: opts.includeArchived,
      })}`,
    ),

  createProject: (body: ProjectBody) =>
    request<Project>("/api/projects", { method: "POST", body: JSON.stringify(body) }),

  /** Omitting `teamId` is how a project becomes transverse. */
  updateProject: (id: string, body: ProjectBody) =>
    request<Project>(`/api/projects/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  projectContents: (id: string) => request<DispositionContents>(`/api/projects/${id}/contents`),

  archiveProject: (id: string, plan: DispositionPlan) =>
    request<Project>(`/api/projects/${id}/archive`, { method: "PUT", body: JSON.stringify(plan) }),

  unarchiveProject: (id: string) =>
    request<Project>(`/api/projects/${id}/unarchive`, { method: "POST" }),

  deleteProject: (id: string, plan: DispositionPlan) =>
    request<void>(`/api/projects/${id}`, { method: "DELETE", body: JSON.stringify(plan) }),

  /** Newest first — the reader wants what is true now, and the rest as context under it. */
  projectUpdates: (id: string) => request<ProjectUpdate[]>(`/api/projects/${id}/updates`),

  /**
   * No date on the way in: the server dates an update when it is written. A caller-supplied
   * one would let somebody backfill a history nobody lived through, which is the only thing
   * that would make this record unreadable as evidence.
   */
  postProjectUpdate: (id: string, body: { health: ProjectHealth; body: string }) =>
    request<ProjectUpdate>(`/api/projects/${id}/updates`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // --- tickets -------------------------------------------------------------

  /**
   * A team scope includes its descendants, so a parent shows the work of its
   * sub-teams. A project scope needs no team: a project may span several, or none.
   */
  tickets: (scope: Scope, includeArchived = false) =>
    request<Ticket[]>(
      `/api/tickets${query({
        limit: 200,
        teamId: scope.kind === "team" ? scope.id : undefined,
        includeDescendants: scope.kind === "team" ? true : undefined,
        projectId: scope.kind === "project" ? scope.id : undefined,
        includeArchived,
      })}`,
    ),

  /**
   * One row. What a realtime event is worth fetching: the event names an id, and
   * refetching the list it happens to be in to learn what changed about it is the
   * round trip `lib/realtime-events.ts` exists to avoid.
   */
  ticket: (id: string) => request<Ticket>(`/api/tickets/${id}`),

  /** The drafts: tickets no team has claimed, which are in no other list this API serves. */
  drafts: () => request<Ticket[]>("/api/tickets/drafts"),

  createTicket: (body: {
    /** Absent files a draft — see `Ticket.identifier` for what that costs it. */
    teamId?: string;
    title: string;
    status?: TicketStatus;
    priority?: TicketPriority;
    estimate?: EffortPoints;
    projectId?: string;
    assigneeIds?: string[];
  }) => request<Ticket>("/api/tickets", { method: "POST", body: JSON.stringify(body) }),

  patchTicket: (
    id: string,
    body: Partial<{
      title: string;
      description: string;
      status: TicketStatus;
      priority: TicketPriority;
      estimate: EffortPoints;
      start: KansoInstant;
      due: KansoInstant;
      projectId: string;
      /** Attaching a draft to a team. There is no way back: the server refuses `unset`. */
      teamId: string;
      archived: boolean;
      unset: string[];
    }>,
  ) => request<Ticket>(`/api/tickets/${id}`, { method: "PATCH", body: JSON.stringify(body) }),

  deleteTicket: (id: string) => request<void>(`/api/tickets/${id}`, { method: "DELETE" }),

  // --- timeline ------------------------------------------------------------

  /**
   * One GET for the whole screen. Neither filter is a page: bounds, slack and the
   * arrows are computed together, so they have to arrive together.
   */
  timeline: (scope: Scope) =>
    request<TimelineView>(
      `/api/timeline${query({
        teamId: scope.kind === "team" ? scope.id : undefined,
        projectId: scope.kind === "project" ? scope.id : undefined,
      })}`,
    ),

  /** `{id}` is the successor; the body names what it now waits on. */
  linkDependency: (successorId: string, predecessorId: string) =>
    request<CascadeResult>(`/api/tickets/${successorId}/dependencies`, {
      method: "POST",
      body: JSON.stringify({ predecessorId }),
    }),

  unlinkDependency: (successorId: string, predecessorId: string) =>
    request<void>(`/api/tickets/${successorId}/dependencies/${predecessorId}`, {
      method: "DELETE",
    }),

  users: () => request<User[]>("/api/users"),
  syncStatus: () => request<SyncStatus>("/api/admin/sync"),
};
