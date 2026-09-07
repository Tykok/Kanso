import { dayValue, type Ticket } from "./api";
import type { PatchInput } from "./queries/core";
import { PRIORITY_LABELS, STATUS_LABELS } from "./status";

/**
 * What a patch the network could not carry looks like in the offline banner.
 *
 * Its own module, and not a corner of `queries/core.ts`, for the reason
 * `timeline-geometry.ts` gives: phrasing is pure, and pure is the only thing the node
 * project can test. The hook keeps the wiring, this keeps the words.
 *
 * `PatchInput` is imported as a type only — erased at build, so nothing here depends on
 * React Query at runtime and the import cannot become a cycle. The field list is worth
 * that: a third copy of it would be a summary that goes quietly silent the next time a
 * patchable field is added.
 */

export type PatchBody = Omit<PatchInput, "id">;

/**
 * The reference, the sentence and the request `withOfflineFallback` holds on disk.
 *
 * [id] and not `ticket.id`, because the row is optional and the request is not:
 * `findTicket` answers nothing for a ticket this tab has never listed, and a write that
 * cannot be described is still a write that must not be lost.
 */
export function heldWrite(
  id: string,
  ticket: Ticket | undefined,
  body: PatchBody,
): { reference: string; summary: string; request: { path: string; method: string; body: unknown } } {
  return {
    // A draft has no identifier to print — `Ticket.identifier` says why — and its title
    // is the only other thing a reader knows it by. Then the id, which names nothing to
    // a reader but heads a row they can still act on.
    reference: ticket?.identifier ?? ticket?.title ?? id,
    summary: summarise(body),
    request: { path: `/api/tickets/${id}`, method: "PATCH", body },
  };
}

/**
 * The three ways a patch can stop being in flight, which `undefined` cannot tell apart.
 *
 * `withOfflineFallback` answers `undefined` for a write it queued, and a mutation whose
 * request was refused has no row either — so the row alone reads a refusal and a queued
 * write as the same thing. They are opposites: one is a guess to roll back, the other a
 * guess to keep, because the write is on disk and still going to be sent.
 */
export type Settlement = "saved" | "held" | "refused";

export function settlementOf(saved: Ticket | undefined, error: unknown): Settlement {
  if (error) return "refused";
  return saved ? "saved" : "held";
}

/**
 * `status → In review`, and `due cleared` for a bound the patch takes off.
 *
 * The arrow points at the word that was on the control, never at the wire value: nobody
 * pressed a key called `in_review`. A field with no phrase of its own says only that it
 * was edited, which is enough to act on and shorter than a description in a banner row.
 */
function summarise({ unset = [], ...fields }: PatchBody): string {
  const changes = Object.entries(fields)
    .filter(([, value]) => value !== undefined)
    .map(([field, value]) => {
      // Archiving is a whole gesture, not a value someone chose: `archived → true` names
      // a field the reader has never seen, for a row that has left their list.
      if (field === "archived") return value ? "archived" : "unarchived";
      const shown = phrase(field, value);
      return shown === undefined ? `${field} edited` : `${field} → ${shown}`;
    });
  return [...changes, ...unset.map((field) => `${field} cleared`)].join(", ");
}

/** The word a reader saw, or nothing when this field has no short way of being said. */
function phrase(field: string, value: unknown): string | undefined {
  switch (field) {
    case "status":
      return STATUS_LABELS[value as keyof typeof STATUS_LABELS];
    case "priority":
      return PRIORITY_LABELS[value as keyof typeof PRIORITY_LABELS];
    case "estimate":
      return String(value);
    case "start":
    case "due":
      return dayValue(value as Parameters<typeof dayValue>[0]);
    default:
      return undefined;
  }
}
