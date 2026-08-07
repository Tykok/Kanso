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

export type Ticket = {
  id: string;
  identifier: string;
  number: number;
  teamId: string;
  title: string;
  description?: string;
  status: TicketStatus;
  priority: TicketPriority;
  startDate?: string;
  dueDate?: string;
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
  teamId?: string;
  archived: boolean;
  mirror: Mirror;
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
export type Me = { user: User; teamIds: string[]; preferences: Preferences };

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
    throw new ApiError(response.status, problem?.detail ?? response.statusText);
  }
  return response.json() as Promise<T>;
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

  teams: () => request<Team[]>("/api/teams"),
  createTeam: (body: { name: string; key?: string; parentTeamId?: string }) =>
    request<Team>("/api/teams", { method: "POST", body: JSON.stringify(body) }),

  projects: (teamId?: string) =>
    request<Project[]>(`/api/projects${teamId ? `?teamId=${teamId}&includeDescendants=true` : ""}`),

  tickets: (teamId?: string) =>
    request<Ticket[]>(
      `/api/tickets?limit=200${teamId ? `&teamId=${teamId}&includeDescendants=true` : ""}`,
    ),

  createTicket: (body: {
    teamId: string;
    title: string;
    status?: TicketStatus;
    priority?: TicketPriority;
    projectId?: string;
  }) => request<Ticket>("/api/tickets", { method: "POST", body: JSON.stringify(body) }),

  patchTicket: (
    id: string,
    body: Partial<{
      title: string;
      description: string;
      status: TicketStatus;
      priority: TicketPriority;
      dueDate: string;
      projectId: string;
      archived: boolean;
      unset: string[];
    }>,
  ) => request<Ticket>(`/api/tickets/${id}`, { method: "PATCH", body: JSON.stringify(body) }),

  deleteTicket: (id: string) => request<void>(`/api/tickets/${id}`, { method: "DELETE" }),

  users: () => request<User[]>("/api/users"),
  syncStatus: () => request<SyncStatus>("/api/admin/sync"),
};
