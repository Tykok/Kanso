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

export type TicketStatus = (typeof TICKET_STATUSES)[number];
export type TicketPriority = (typeof TICKET_PRIORITIES)[number];
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
  identifier: string;
  number: number;
  teamId: string;
  title: string;
  description?: string;
  status: TicketStatus;
  priority: TicketPriority;
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
  ticketCount: number;
  mirror: Mirror;
};

export type Project = {
  id: string;
  name: string;
  status: string;
  start?: KansoInstant;
  end?: KansoInstant;
  leadUserId?: string;
  teamId?: string;
  archived: boolean;
  mirror: Mirror;
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

export type InstanceRole = "owner" | "admin" | "member";

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

export type Preferences = {
  theme: Theme;
  accent: Accent;
  density: Density;
  sidebarVisible: boolean;
  showSyncBadges: boolean;
  showStatusBar: boolean;
  defaultTeamId?: string;
  /** Set once the user has been through (or skipped) the preferences step. */
  onboardedAt?: string;
};

export const DEFAULT_PREFERENCES: Preferences = {
  theme: "system",
  accent: "indigo",
  density: "comfortable",
  sidebarVisible: true,
  showSyncBadges: true,
  showStatusBar: true,
};

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
  notion: IntegrationState & { parentPageId?: string; bootstrapped: boolean };
  google: IntegrationState & { clientId?: string };
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

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
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
function query(params: Record<string, string | number | boolean | undefined>): string {
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

  saveNotion: (body: { token?: string; parentPageId: string }) =>
    request<SetupState>("/api/setup/notion", { method: "POST", body: JSON.stringify(body) }),

  /** Round trip to Notion before saving, so a bad token is caught in the wizard. */
  testNotion: (body: { token?: string; parentPageId?: string }) =>
    request<{ ok: boolean; detail: string }>("/api/setup/notion/test", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  bootstrapNotion: () => request<SetupState>("/api/admin/notion/bootstrap", { method: "POST" }),

  saveGoogle: (body: { clientId: string; clientSecret: string }) =>
    request<SetupState>("/api/setup/google", { method: "POST", body: JSON.stringify(body) }),

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

  createTicket: (body: {
    teamId: string;
    title: string;
    status?: TicketStatus;
    priority?: TicketPriority;
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
      start: KansoInstant;
      due: KansoInstant;
      projectId: string;
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
