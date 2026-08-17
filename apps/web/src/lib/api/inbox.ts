import { API_URL, ApiError, getDevUser } from "./core";

/**
 * Slice D's client: the inbox, and the one raw sender the offline queue replays
 * through.
 *
 * `core.ts` keeps its `request` helper private and `core.ts` is not this branch's file,
 * so this one is restated rather than imported — fifteen lines against an edit to a
 * file five other branches are also forbidden to touch. `API_URL`, `ApiError` and
 * `getDevUser` are exported and are reused, so the parts that could actually disagree
 * (the base URL, the error shape, the dev identity header) have one definition.
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

/**
 * The six sentences the inbox is drawn to say, plus the seventh the chooser opens on.
 * Closed here and again by a `CHECK` in `V13`, the pattern `TICKET_STATUSES` follows.
 */
export const NOTIFICATION_KINDS = [
  "assigned",
  "mentioned",
  "sync_failed",
  "status_moved",
  "comment_replied",
  "project_slipped",
  "conflict",
] as const;

export const INBOX_TABS = ["all", "assigned", "mentions", "failures"] as const;

export type NotificationKind = (typeof NOTIFICATION_KINDS)[number];
export type InboxTab = (typeof INBOX_TABS)[number];

export type Notification = {
  /** Absent for a failed mirror push: nothing stored it, so nothing marks it read. */
  id?: string;
  kind: NotificationKind;
  entityType: "ticket" | "project" | "doc" | "team";
  entityId: string;
  /** `KAN-142`. Absent for anything with no per-team number. */
  reference?: string;
  /** The thing's own name. Absent once the thing itself has been deleted. */
  subject?: string;
  actor?: { id: string; displayName: string };
  /** Whatever the kind needs and no join could recover — see `V13`. */
  payload: Record<string, unknown>;
  readAt?: string;
  createdAt: string;
};

export type InboxCounts = {
  all: number;
  assigned: number;
  mentions: number;
  failures: number;
  unread: number;
};

export type Inbox = { rows: Notification[]; counts: InboxCounts };

/** A conflict's payload, once the kind has been checked. */
export type ConflictDetail = {
  field: string;
  mine: string;
  theirs: string;
  theirActor?: string;
  theirEditedAt?: string;
};

export function conflictDetail(notification: Notification): ConflictDetail | undefined {
  const { field, mine, theirs, theirActor, theirEditedAt } = notification.payload;
  if (typeof field !== "string" || typeof mine !== "string" || typeof theirs !== "string") {
    return undefined;
  }
  return {
    field,
    mine,
    theirs,
    theirActor: typeof theirActor === "string" ? theirActor : undefined,
    theirEditedAt: typeof theirEditedAt === "string" ? theirEditedAt : undefined,
  };
}

export const inboxApi = {
  inbox: (tab: InboxTab = "all") =>
    request<Inbox>(`/api/notifications?tab=${tab}`),

  markRead: (id: string) => request<void>(`/api/notifications/${id}/read`, { method: "POST" }),

  markAllRead: () => request<{ read: number }>("/api/notifications/read-all", { method: "POST" }),

  /** Puts every failed push back in the queue. The `Retry` on a failure row. */
  retryFailedPushes: () =>
    request<{ requeued: number }>("/api/admin/sync/retry-failed", { method: "POST" }),
};

/**
 * One arbitrary write, sent as it was recorded.
 *
 * This is what the offline queue replays with, which is why it takes a path and a verb
 * rather than being one of the named calls above: a queued write is a request that was
 * already decided, weeks ago in the worst case, and re-deriving it from a typed
 * argument list would mean the queue's contents depending on today's client.
 */
export const sendRaw = (path: string, method: string, body?: unknown) =>
  request<unknown>(path, {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
