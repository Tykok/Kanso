import { query, request, type User } from "./core";

/**
 * Comments, labels and the activity log — the three things the drawn screens assume
 * everywhere and the schema had none of. They live together because three slices consume
 * them and none owns them: the project page draws activity, the inbox routes mentions,
 * the saved view filters on labels, the contributor page badges them.
 */

/** The closed vocabulary the `activity` table's own CHECK enforces. */
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
export type ActivityEntity = "ticket" | "project" | "team" | "doc";

/**
 * One thing that happened, as the server recorded it.
 *
 * `payload` is deliberately loose: its shape depends on `kind` — `{ from, to }` for a
 * scalar, `{ field, from, to }` for a date, `{ userId }` for an assignment, `{ labelId,
 * name, attached }` for a label. A discriminated union per kind would be eleven types to
 * hold one sentence each, and the renderer switches on `kind` regardless. Absent keys read
 * as undefined: the server's mapper omits nulls rather than sending them.
 */
export type ActivityRow = {
  id: string;
  entityType: ActivityEntity;
  entityId: string;
  actor: User | null;
  kind: ActivityKind;
  payload: Record<string, unknown>;
  createdAt: string;
};

export type Comment = {
  id: string;
  author: User;
  body: string;
  /** Resolved when the comment was written, so a later rename cannot drop one. */
  mentions: User[];
  createdAt: string;
  updatedAt: string;
};

export type LabelColour = "indigo" | "blue" | "green" | "amber" | "rose" | "violet";

/** Team-scoped: two teams may both own the name `sync`, and neither wins. */
export type Label = {
  id: string;
  teamId: string;
  name: string;
  colour: LabelColour;
};

export const socialApi = {
  /** Newest first — every reader of this list draws a feed. */
  activity: (entityType: ActivityEntity, entityId: string, limit?: number) =>
    request<ActivityRow[]>(`/api/activity${query({ entityType, entityId, limit })}`),

  /** Oldest first — a thread is read downwards, unlike a feed. */
  comments: (ticketId: string) =>
    request<Comment[]>(`/api/comments${query({ ticketId })}`),

  comment: (body: { ticketId?: string; docId?: string; body: string }) =>
    request<Comment>("/api/comments", { method: "POST", body: JSON.stringify(body) }),

  deleteComment: (id: string) => request<void>(`/api/comments/${id}`, { method: "DELETE" }),

  teamLabels: (teamId: string) => request<Label[]>(`/api/teams/${teamId}/labels`),

  createLabel: (teamId: string, body: { name: string; colour?: LabelColour }) =>
    request<Label>(`/api/teams/${teamId}/labels`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  ticketLabels: (ticketId: string) => request<Label[]>(`/api/tickets/${ticketId}/labels`),

  /**
   * The whole set, like `/assignees`: a PUT that replaced one label would need the client
   * to know which of two concurrent edits it was racing, and it does not.
   */
  setTicketLabels: (ticketId: string, labelIds: string[]) =>
    request<Label[]>(`/api/tickets/${ticketId}/labels`, {
      method: "PUT",
      body: JSON.stringify(labelIds),
    }),
};
