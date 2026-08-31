import type { NotionImportSchema } from "@/components/inbox/import-columns";
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

// --- screen 24, the Notion import -------------------------------------------

/**
 * A database the workspace search found, with its page count.
 *
 * `pagesExact` is false when the count stopped at the server's discovery bound rather
 * than at the end of the base: Notion answers no total for a data source, so a count is a
 * walk of a hundred pages at a time and a long enough base is read as "at least this
 * many". The distinction is on the wire because the alternative is a screen that says
 * "2000 pages" about a base holding nine thousand.
 */
export type NotionImportSource = {
  id: string;
  name: string;
  pages: number;
  pagesExact: boolean;
};

/**
 * What step 1 gets back.
 *
 * `available: false` is a first-class answer, not an error: an instance with no Notion
 * token has nothing to import from, and so does one whose API client cannot yet search
 * the workspace. Either way the dialog has a sentence to print rather than a spinner
 * that never resolves, and `reason` is that sentence.
 */
export type NotionImportSources = {
  available: boolean;
  reason?: string;
  sources: NotionImportSource[];
};

/**
 * One row of the mapping. An ignored base is absent, never `target: "ignore"`.
 *
 * `columns` says which of this base's Notion properties answers which field, keyed by the
 * field's wire string (`status`, `parentTeam`, `blockedBy`, …); `values` says, per field,
 * what each of that column's own options means in Kanso's vocabulary. `fallback` is where
 * this base's unlinked rows land — per base, because a base whose team cannot be resolved
 * may want a different destination from its neighbour.
 */
export type NotionImportPlanRow = {
  sourceId: string;
  target: "teams" | "projects" | "tickets" | "documents";
  columns: Record<string, string>;
  values: Record<string, Record<string, string>>;
  fallback: { teamId?: string; parentTeamId?: string; projectId?: string };
};

/**
 * What the preview and the import both take.
 *
 * `teamId` is required only for a plan holding a row that is not `teams` and has no
 * `fallback.teamId` of its own — an import of teams alone has no destination to ask about.
 * `people` is the person correspondence, keyed by the Notion person id and valued by the
 * Kanso account the reader chose, or null for one they left unmatched.
 */
export type NotionImportRequest = {
  teamId?: string;
  people: Record<string, string | null>;
  plan: NotionImportPlanRow[];
};

/** A base's name and how many of its pages the plan would write. */
export type NotionImportGroup = { name: string; pages: number };

/** What step 5 sends, and what it gets back before anything is written. */
export type NotionImportPreview = {
  teams: NotionImportGroup[];
  projects: NotionImportGroup[];
  folders: NotionImportGroup[];
  /**
   * Bases taking part in a mapped link that crosses into another kept base — bases, not
   * arrows, because a hundred relations between two databases is still two databases. Not
   * a count of dependencies: a `Projet` relation places a ticket in a project, and only a
   * `Blocked by` relation draws an arrow between two tickets.
   */
  linkedSources: number;
  /** Rows a relation would place — one per child page whose parent was found. */
  linkedByRelation: number;
  /**
   * Rows no relation places, which would land in a base's `fallback`. Always 0 from the
   * server today — `ImportPlanner.preview` takes no fallback into account — so nothing
   * here prints it.
   */
  fellBack: number;
  /** Properties Kanso has no column for. They land in an "imported from Notion" block. */
  unmappedProperties: string[];
  /** Pages Kanso cannot make a row out of — one with no title at all. Reported, not hidden. */
  skipped: number;
  /**
   * Pages a row already exists for. Distinct from `skipped`: these are adoptable, there is
   * simply already a Kanso row for them, and a second run would leave them alone. The one
   * number that stops somebody importing the same workspace twice.
   */
  alreadyImported: number;
};

/** A page the import reported instead of inventing a row for. */
export type NotionImportSkip = { source: string; pageId: string; reason: string };

/** What the import did, once it has done it. */
export type NotionImportResult = {
  started: boolean;
  teams: number;
  tickets: number;
  docs: number;
  projects: number;
  folders: number;
  dependencies: number;
  /** Relations with an end that resolved to nothing. Counted, never guessed at. */
  droppedRelations: number;
  /** Two sides of a relation that contradicted each other; the child won. */
  linkConflicts: number;
  /** A mapped person whose Kanso account no longer exists. The row was written unassigned. */
  droppedAssignees: number;
  alreadyImported: number;
  skipped: NotionImportSkip[];
};

export const notionImportApi = {
  sources: () => request<NotionImportSources>("/api/notion/import/sources"),

  /**
   * One base's columns, and Kanso's first guess at how to map them. Asked once per kept
   * base, so a base whose schema Notion refuses does not blank the screen.
   */
  schema: (sourceId: string, target: NotionImportPlanRow["target"]) =>
    request<NotionImportSchema>(
      `/api/notion/import/schema?sourceId=${encodeURIComponent(sourceId)}&target=${target}`,
    ),

  /**
   * Reads. Named `preview` rather than `dryRun` because that is what the button says,
   * and because nothing about it is a rehearsal of a write: it is the last read before
   * one, and the drawing's own promise is that nothing is written until it is confirmed.
   */
  preview: (body: NotionImportRequest) =>
    request<NotionImportPreview>("/api/notion/import/preview", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  /**
   * Writes, and the only call here that does.
   *
   * `teamId` is the one thing the mapping alone cannot supply: a ticket needs a team and a
   * per-team number, and a page written by hand in Notion has neither — which is why the
   * inbound poller refuses to adopt one at all (`architecture.md`, "Pages created in
   * Notion are not adopted"). An import is the case where somebody is present to answer,
   * so the answer is part of the request, and the server refuses a team the actor may not
   * write to before it reads a single page.
   */
  confirm: (body: NotionImportRequest) =>
    request<NotionImportResult>("/api/notion/import", {
      method: "POST",
      body: JSON.stringify(body),
    }),
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
