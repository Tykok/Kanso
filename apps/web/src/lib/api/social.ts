import { query, request, type User } from "./core";

/**
 * Comments, labels and the activity log — the three things the drawn screens assume
 * everywhere and the schema had none of. They live together because three slices consume
 * them and none owns them: the project page draws activity, the inbox routes mentions,
 * the saved view filters on labels, the contributor page badges them.
 */

/**
 * The closed vocabulary the `activity` table's own CHECK enforces.
 *
 * **The authority is the most recent migration that laid the list down, found by grep and
 * never by resemblance.** Today that is `V36__github.sql`, whose seventeen this matches —
 * and the two before it each said the same sentence about themselves: `V35` widened `V30`,
 * `V30` widened `V23`, back to `V8`'s original eleven. A migration numbered above `V36`
 * that re-states the CHECK is the authority instead.
 *
 * The house constrains a closed vocabulary in the database **and** in Kotlin, where
 * `ActivityKind` in `domain/Model.kt` guards the CHECK and the CHECK guards it. This is a
 * third copy, and it fell two kinds and one entity behind before anybody noticed — which is
 * what KAN-77 was. It is no longer unguarded: `social.test.ts` reads the CHECK out of the
 * newest migration that states it and requires this list to match it word for word, so a
 * migration that widens the vocabulary goes red in its own commit. `activitySentence` then
 * carries the other two layers, and its comment says what each is for.
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
  "carried_over",
  "health_posted",
  "estimated",
  /** `V30`'s. An API token revoked, on the feed of the account it belonged to. */
  "token_revoked",
  /** `V35`'s. One word for all four field types and for setting, changing and clearing. */
  "field_set",
  /**
   * `V36`'s kind, for the link between a pull request and a ticket. The transition a merge
   * causes is an ordinary `status_changed` with `payload.via_pr`, not this — see
   * `activitySentence`.
   *
   * Adding a word here is what makes `activitySentence`'s `switch` demand a branch for it,
   * and that is the whole fence: the switch has no `default`, so a kind in this list with
   * no case fails the build. It only ever fences what this list knows about, which is why
   * the unknown kind is caught a second way at run time.
   */
  "pull_request_linked",
] as const;

export type ActivityKind = (typeof ACTIVITY_KINDS)[number];

/**
 * What a feed can be drawn *for* — `activity_entity_type_chk`, whose authority is `V30`.
 *
 * `"user"` is the one value whose id names a person rather than a unit of work, and that
 * difference is a permissions difference rather than a taxonomic one: `ActivityController`
 * gates `ticket` and carries a rule of its own for this value, because an account's feed is
 * not something any member may already list. It arrived with `token_revoked` and was missed
 * here at the same time.
 */
export type ActivityEntity = "ticket" | "project" | "team" | "doc" | "user";

/**
 * One thing that happened, as the server recorded it.
 *
 * `payload` is deliberately loose: its shape depends on `kind` — `{ from, to }` for a
 * scalar, `{ field, from, to }` for a date, `{ userId }` for an assignment, `{ labelId,
 * name, attached }` for a label. A discriminated union per kind would be thirteen types to
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
