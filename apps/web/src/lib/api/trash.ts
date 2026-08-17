/**
 * Slice E — screen 26, the trash and the archives.
 *
 * The wire shapes come straight from `dev.kanso.trash.TrashController`. Nothing is
 * re-derived here: the countdown in particular is a number the server computed against
 * the clock its own retention sweep reads, and a browser recomputing it from a timestamp
 * would disagree by a whole day for anyone far enough from UTC.
 */

import { request } from "./core";

/** Closed, and closed again by `V11`'s CHECK. Three of the four have no table yet. */
export const TRASH_KINDS = ["ticket", "doc", "view", "folder"] as const;
export type TrashKind = (typeof TRASH_KINDS)[number];

/** What the deleted thing holds, and whether the delete reaches it. */
export type TrashHoldingKind = "blocks" | "mentionedTickets" | "linkedDocs";

export type TrashHolding = {
  kind: TrashHoldingKind;
  count: number;
  /** False when the thing goes and this stays — the drawing's load-bearing detail. */
  cascades: boolean;
};

/** Where a restore puts it back. Named, so no button ever has to say "somewhere". */
export type TrashParent = { kind: string; id?: string; name: string };

export type TrashItem = {
  kind: TrashKind;
  id: string;
  /** `KAN-121 · SVG seal` — what anybody would call it out loud. */
  label: string;
  parent?: TrashParent;
  holds: TrashHolding[];
  deletedAt?: string;
  deletedBy?: { id: string; displayName: string };
  /** Whole days left of the retention window. Absent for an archive, which has no clock. */
  daysLeft?: number;
};

export type Trash = {
  trash: TrashItem[];
  archives: TrashItem[];
  /** So the screen prints the rule rather than hardcoding a number the server owns. */
  retentionDays: number;
};

const path = (kind: TrashKind, id: string) => `/api/trash/${kind}/${id}`;

export const trashApi = {
  load: () => request<Trash>("/api/trash"),
  restore: (kind: TrashKind, id: string) =>
    request<void>(`${path(kind, id)}/restore`, { method: "POST" }),
  /** Out of the trash, into the archives: the countdown off by decision. */
  archive: (kind: TrashKind, id: string) =>
    request<void>(`${path(kind, id)}/archive`, { method: "POST" }),
  /** For good. The one exit that does not come back. */
  purge: (kind: TrashKind, id: string) => request<void>(path(kind, id), { method: "DELETE" }),
};
