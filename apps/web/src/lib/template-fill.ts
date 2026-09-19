import type { ResolvedTemplate, TicketPriority } from "./api";

/**
 * The composer's fields that a template can reach. Not the whole form: `teamId`, `projectId`
 * and `assigneeId` are decisions about *this* piece of work, and `TemplateBody` deliberately
 * refuses to carry them — see `domain/TicketTemplate.kt`.
 */
export type Filled = {
  title: string;
  description: string;
  priority: TicketPriority;
  /** The select's own value, so `""` is "nobody sized this" and never `0`. */
  estimate: string;
  labelIds: string[];
  fieldValues: Record<string, string | number | boolean>;
};

export type FilledKey = keyof Filled;

/**
 * The keys the person has changed by hand since the composer opened.
 *
 * **This set is the whole of the feature's difficulty, and the reason it exists is one
 * failure:** the team selector sits above the picker and can move after a template is
 * chosen, which re-resolves. Without a record of what was typed, that second resolution
 * overwrites the sentence somebody wrote — the only way this feature can lose work. Keeping
 * it as a set of keys rather than diffing against the template's values is what makes
 * "they typed exactly what the template said" indistinguishable from "they left it alone",
 * which it should be: both mean the field is theirs now.
 */
export type Touched = Set<FilledKey>;

/** What the template says for each key, or `undefined` for "this template does not say". */
function fromTemplate(resolved: ResolvedTemplate): Partial<Filled> {
  const placed: Partial<Filled> = {};
  if (resolved.title !== null) placed.title = resolved.title;
  if (resolved.description !== null) placed.description = resolved.description;
  if (resolved.priority !== null) placed.priority = resolved.priority;
  // Stringified here and not on the wire: the wire carries a number off the Fibonacci scale,
  // and the select carries its own string. `""` stays the absence.
  if (resolved.estimate !== null) placed.estimate = String(resolved.estimate);
  if (resolved.labelIds.length > 0) placed.labelIds = resolved.labelIds;
  if (Object.keys(resolved.fieldValues).length > 0) placed.fieldValues = resolved.fieldValues;
  return placed;
}

/**
 * The form after a template is chosen, or chosen again.
 *
 * Three sources, in order: a key the person has touched keeps [current]; a key the template
 * names takes the template's; anything else returns to [seed].
 *
 * That last fallback is not tidiness. Re-picking has to *replace* the previous template's
 * contribution, not layer on top of it — otherwise a second template that says nothing about
 * the title leaves the first one's title on screen, and nothing on the form says where it
 * came from.
 *
 * Pure: no React, no fetch, no clock. The same reason `PrLinkParser` and `Cascade` are pure
 * on the other side — this decides what happens to something somebody wrote, so it has to be
 * assertable as a table of inputs rather than through a rendered component.
 */
export function applyTemplate(
  current: Filled,
  touched: Touched,
  resolved: ResolvedTemplate,
  seed: Filled,
): Filled {
  const placed = fromTemplate(resolved);
  const pick = <K extends FilledKey>(key: K): Filled[K] => {
    if (touched.has(key)) return current[key];
    const fromTpl = placed[key];
    return (fromTpl === undefined ? seed[key] : fromTpl) as Filled[K];
  };
  return {
    title: pick("title"),
    description: pick("description"),
    priority: pick("priority"),
    estimate: pick("estimate"),
    labelIds: pick("labelIds"),
    fieldValues: pick("fieldValues"),
  };
}

/**
 * The form with no template at all: the ordinary seeded state, minus nothing the person
 * typed. What `Escape` on the picker does, and what clearing the selection does.
 */
export function clearTemplate(current: Filled, touched: Touched, seed: Filled): Filled {
  const pick = <K extends FilledKey>(key: K): Filled[K] =>
    touched.has(key) ? current[key] : seed[key];
  return {
    title: pick("title"),
    description: pick("description"),
    priority: pick("priority"),
    estimate: pick("estimate"),
    labelIds: pick("labelIds"),
    fieldValues: pick("fieldValues"),
  };
}
